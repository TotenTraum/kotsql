package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.pool.ConnectionPool
import ru.ttraum.kotsql.pool.PoolConfig
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** A connection's bytes, plain or encrypted. */
internal interface ByteStream {
    /** The `tls-server-end-point` channel binding, if this stream is encrypted and can tell. */
    val tlsServerEndPoint: ByteArray? get() = null

    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun flush()
    suspend fun close()
}

/** The bytes of a socket. */
internal class SocketStream(
    val socket: Socket,
    val input: ByteReadChannel = socket.openReadChannel(),
    val output: ByteWriteChannel = socket.openWriteChannel(),
    override val tlsServerEndPoint: ByteArray? = null,
) : ByteStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = input.readAvailable(buffer, offset, length)
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = output.writeFully(buffer, offset, offset + length)
    override suspend fun flush() = output.flush()
    override suspend fun close() { runCatching { socket.close() } }
}

/** Non-blocking transport on ktor sockets, for every platform. */
class KtorTransport private constructor(internal val socket: Socket, private val lease: SelectorPool.Lease) : Transport {
    private val plain = SocketStream(socket)
    private var stream: ByteStream = plain
    private var readTimeout = 0L

    /** Owns TLS background work, which must outlive the coroutine that happened to open the connection. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override var isSecure = false
        private set

    override val tlsServerEndPoint: ByteArray? get() = stream.tlsServerEndPoint

    override fun setReadTimeout(millis: Long) { readTimeout = millis }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readTimeout <= 0) return stream.read(buffer, offset, length)
        // Not catching TimeoutCancellationException, which could be the caller's own withTimeout, and is theirs to get.
        return withTimeoutOrNull(readTimeout) { stream.read(buffer, offset, length) }
            ?: throw SqlException("Timed out waiting for the server", "08006")
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = stream.write(buffer, offset, length)
    override suspend fun flush() = stream.flush()

    override suspend fun startTls(options: TlsOptions) {
        stream.flush()
        stream = upgradeToTls(plain, options, scope.coroutineContext)
        isSecure = true
    }

    override suspend fun close() {
        try {
            stream.close()
            scope.cancel()
        } finally {
            lease.release()
        }
    }

    companion object Factory : TransportFactory {
        internal val selectors by lazy { SelectorPool(socketsPerSelector) }

        override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
            // A second try only for a selector found dead, which a new one replaces.
            repeat(2) { attempt ->
                val lease = selectors.acquire()
                var connected = false
                try {
                    val socket = withTimeoutOrNull(connectTimeoutMillis) {
                        aSocket(lease.selector.manager).tcp().connect(host, port) { noDelay = true; keepAlive = true }
                    } ?: throw SqlException("Timed out connecting to $host:$port", "08001")
                    applySocketOptions(socket)
                    connected = true
                    return KtorTransport(socket, lease)
                } catch (e: SqlException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isSelectorClosed(e)) {
                        lease.selectorDied()
                        if (attempt == 0) return@repeat
                    }
                    throw SqlException("Cannot connect to $host:$port: ${e.message}", "08001", cause = e)
                } finally {
                    if (!connected) lease.release()
                }
            }
            error("unreachable")
        }
    }
}

/** Sets the options that the socket builder doesn't apply on this platform. */
internal expect fun applySocketOptions(socket: Socket)

/**
 * Performs the handshake and returns the encrypted stream.
 *
 * @param context where a long-running TLS session may keep its coroutines
 */
internal expect suspend fun upgradeToTls(plain: SocketStream, options: TlsOptions, context: CoroutineContext): ByteStream

internal fun isIpLiteral(host: String): Boolean =
    ':' in host || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)

/** The fastest non-blocking transport for this platform that has shown to work: epoll on Linux, ktor sockets elsewhere. */
expect val DefaultTransport: TransportFactory

/** Connects with [DefaultTransport]. On non-JVM targets, register the driver first with `Drivers.register`. */
suspend fun connect(url: String, codecs: CodecRegistry = CodecRegistry.Global): SqlConnection = ru.ttraum.kotsql.transport.connect(url, DefaultTransport, codecs)

/** A pool of connections to [url], over [DefaultTransport]. */
fun connectionPool(url: String, config: PoolConfig = PoolConfig(), codecs: CodecRegistry = CodecRegistry.Global) =
    ConnectionPool({ connect(url, codecs) }, config)
