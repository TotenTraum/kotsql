package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.transport.TlsOptions
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.InterruptedByTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Non-blocking transport on the JDK's asynchronous sockets. Writes happen on the calling thread and reads complete on one
 * event thread per connection, so a query takes a single thread switch. ktor's sockets pass data through a reader and a
 * writer coroutine and a selector thread, for about nine switches per query and several times the CPU, as measured by
 * CostProbe in kotsql-benchmark.
 *
 * Whatever a coroutine does after resuming from [read] runs on the event thread, unless it has a dispatcher of its own. It
 * must not block there: that would hold up the other connections sharing the thread.
 */
public class AsyncSocketTransport private constructor(private val channel: AsynchronousSocketChannel, private val port: Int) : Transport {
    private val out = ByteWriter(8 * 1024)
    private var readTimeout = 0L
    private var tls: TlsStream? = null
    override val isSecure: Boolean get() = tls != null

    override fun setReadTimeout(millis: Long) { readTimeout = millis }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = tls ?: return rawRead(ByteBuffer.wrap(buffer, offset, length))
        return stream.read(buffer, offset, length)
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Unit = out.bytes(buffer, offset, length)

    override suspend fun flush() {
        val data = ByteBuffer.wrap(out.buffer, 0, out.size)
        val stream = tls
        if (stream != null) stream.write(data) else rawWrite(data)
        out.clear()
    }

    override var tlsServerEndPoint: ByteArray? = null
        private set

    override suspend fun startTls(options: TlsOptions) {
        flush()
        try {
            val engine = SocketTransport.Factory.sslContext(options).createSSLEngine(options.serverName, port)
            engine.useClientMode = true
            if (options.mode == TlsMode.VERIFY_FULL) {
                engine.sslParameters = engine.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            val stream = TlsStream(engine)
            stream.handshake()
            tls = stream
            tlsServerEndPoint = engine.session.peerCertificates.firstOrNull()?.let(::tlsServerEndPoint)
        } catch (e: SSLException) {
            throw SqlException("TLS handshake failed: ${e.message}", "08001", cause = e)
        }
    }

    override suspend fun close() { runCatching { channel.close() } }

    // A single handler for all operations, with the continuation as the attachment, saves an allocation per read and write.
    private object Completion : CompletionHandler<Int, CancellableContinuation<Int>> {
        override fun completed(result: Int, continuation: CancellableContinuation<Int>) = continuation.resume(result)
        override fun failed(e: Throwable, continuation: CancellableContinuation<Int>) = continuation.resumeWithException(
            if (e is InterruptedByTimeoutException) SqlException("Timed out waiting for the server", "08006", cause = e) else e,
        )
    }

    private suspend fun rawRead(target: ByteBuffer): Int = suspendCancellableCoroutine { continuation ->
        // An abandoned operation can't be taken back, and the stream is out of step after it anyway.
        continuation.invokeOnCancellation { runCatching { channel.close() } }
        channel.read(target, readTimeout, TimeUnit.MILLISECONDS, continuation, Completion)
    }

    private suspend fun rawWrite(data: ByteBuffer) {
        while (data.hasRemaining()) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { channel.close() } }
                channel.write(data, continuation, Completion)
            }
        }
    }

    /** TLS over the raw operations. Not for concurrent use, like the connection above it. */
    private inner class TlsStream(private val engine: SSLEngine) {
        private val networkIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).flip()
        private val networkOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
        private val plainIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).flip()

        suspend fun handshake() {
            engine.beginHandshake()
            finishHandshake()
        }

        /** Runs while the engine has something to do. TLS 1.3 also needs this after the handshake, for session tickets. */
        private suspend fun finishHandshake() {
            while (true) {
                when (engine.handshakeStatus!!) {
                    HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> return
                    HandshakeStatus.NEED_TASK -> while (true) (engine.delegatedTask ?: break).run()
                    HandshakeStatus.NEED_WRAP -> wrap(EMPTY)
                    HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> when (unwrap().status!!) {
                        Status.BUFFER_UNDERFLOW -> if (!receive()) throw SSLException("the server closed the connection")
                        Status.CLOSED -> throw SSLException("the server closed the connection")
                        Status.OK, Status.BUFFER_OVERFLOW -> Unit
                    }
                }
            }
        }

        private fun unwrap(): SSLEngineResult {
            plainIn.compact()
            try {
                return engine.unwrap(networkIn, plainIn)
            } finally {
                plainIn.flip()
            }
        }

        /** Reads more ciphertext. Returns false at end of stream. */
        private suspend fun receive(): Boolean {
            networkIn.compact()
            try {
                return rawRead(networkIn) >= 0
            } finally {
                networkIn.flip()
            }
        }

        private suspend fun wrap(data: ByteBuffer) {
            networkOut.clear()
            val result = engine.wrap(data, networkOut)
            if (result.status == Status.CLOSED) throw SSLException("the connection is closed")
            networkOut.flip()
            rawWrite(networkOut)
        }

        suspend fun write(data: ByteBuffer) {
            while (data.hasRemaining()) wrap(data)
        }

        suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (plainIn.hasRemaining()) {
                    val count = minOf(plainIn.remaining(), length)
                    plainIn.get(buffer, offset, count)
                    return count
                }
                when (unwrap().status!!) {
                    Status.OK -> finishHandshake()
                    Status.BUFFER_UNDERFLOW -> if (!receive()) return -1
                    Status.CLOSED -> return -1
                    // Both buffers have the sizes that the session asks for, and this one has just been emptied.
                    Status.BUFFER_OVERFLOW -> throw SSLException("the server sent a record that is too large")
                }
            }
        }
    }

    public companion object Factory : TransportFactory {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /**
         * One group per thread, with connections assigned in turn, rather than one group with many threads: those hand
         * every event from the thread waiting on the selector to another one, an extra thread switch per read.
         */
        private val groups: List<AsynchronousChannelGroup> by lazy {
            List(Runtime.getRuntime().availableProcessors().coerceAtLeast(2)) { i ->
                AsynchronousChannelGroup.withFixedThreadPool(1) { task -> Thread(task, "kotsql-io-$i").apply { isDaemon = true } }
            }
        }
        private val nextGroup = AtomicInteger()

        private object Connected : CompletionHandler<Void?, CancellableContinuation<Unit>> {
            override fun completed(result: Void?, continuation: CancellableContinuation<Unit>) = continuation.resume(Unit)
            override fun failed(e: Throwable, continuation: CancellableContinuation<Unit>) = continuation.resumeWithException(e)
        }

        override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
            // Name resolution blocks, and the caller may be on an event thread.
            val address = withContext(Dispatchers.IO) { InetSocketAddress(host, port) }
            if (address.isUnresolved) throw SqlException("Cannot connect to $host:$port: unknown host", "08001")
            val channel = AsynchronousSocketChannel.open(groups[Math.floorMod(nextGroup.getAndIncrement(), groups.size)])
            try {
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true)
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
                withTimeoutOrNull(connectTimeoutMillis) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { runCatching { channel.close() } }
                        channel.connect(address, continuation, Connected)
                    }
                } ?: throw SqlException("Timed out connecting to $host:$port", "08001")
                return AsyncSocketTransport(channel, port)
            } catch (e: SqlException) {
                runCatching { channel.close() }
                throw e
            } catch (e: CancellationException) {
                runCatching { channel.close() }
                throw e
            } catch (e: IOException) {
                runCatching { channel.close() }
                throw SqlException("Cannot connect to $host:$port: ${e.message}", "08001", cause = e)
            }
        }
    }
}
