@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, ObsoleteWorkersApi::class)

package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.linux.EPOLLIN
import platform.linux.EPOLLONESHOT
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_KEEPALIVE
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

private const val IPPROTO_TCP = 6
private const val TCP_NODELAY = 1
private const val EVENTS_PER_WAIT = 64
private const val EVENT_LOOPS = 4

/**
 * Waits for sockets to become ready. Sockets are registered with `EPOLLONESHOT` and re-armed from whichever thread wants
 * to wait, which the kernel allows while the loop is in `epoll_wait`, so that waiting needs no wake-up of the loop at all.
 */
private class EventLoop(index: Int) {
    val epoll = epoll_create1(0).also {
        if (it < 0) throw SqlException(
            "Cannot create an epoll instance: ${errorText()}",
            "08001"
        )
    }

    /** Copied on change, so that looking up a socket for each event takes no lock. Connections come and go rarely. */
    private val sockets = AtomicReference(emptyMap<ULong, EpollStream>())

    init {
        Worker.start(name = "kotsql-io-$index").executeAfter(0L) { run() }
    }

    fun add(id: ULong, stream: EpollStream) {
        while (true) {
            val current = sockets.load()
            if (sockets.compareAndSet(current, current + (id to stream))) return
        }
    }

    fun remove(id: ULong) {
        while (true) {
            val current = sockets.load()
            if (sockets.compareAndSet(current, current - id)) return
        }
    }

    private fun run() = memScoped {
        val events = allocArray<epoll_event>(EVENTS_PER_WAIT)
        while (true) {
            val ready = epoll_wait(epoll, events, EVENTS_PER_WAIT, -1)
            if (ready < 0) {
                if (errno == EINTR) continue
                error("epoll_wait failed: ${errorText()}")
            }
            val known = sockets.load()
            for (i in 0 until ready) known[events[i].data.u64]?.onReady()
        }
    }
}

private fun errorText(): String = strerror(errno)?.toKString() ?: "error $errno"

private val eventLoops by lazy { List(EVENT_LOOPS) { EventLoop(it) } }
private val nextId = AtomicLong(0)
private val nextLoop = AtomicInt(0)

/** A non-blocking socket. Sends and receives on the calling thread, and only waits through the loop when it has to. */
private class EpollStream(private val descriptor: Int, private val loop: EventLoop) : ByteStream {
    private val id = nextId.addAndFetch(1).toULong()
    private val waiter = AtomicReference<CancellableContinuation<Unit>?>(null)
    private val closed = AtomicInt(0)

    init {
        loop.add(id, this)
        control(EPOLL_CTL_ADD, EPOLLONESHOT)
    }

    private fun control(operation: Int, events: UInt) = memScoped {
        val event = alloc<epoll_event>()
        event.events = events
        event.data.u64 = id
        if (epoll_ctl(loop.epoll, operation, descriptor, event.ptr) != 0) {
            throw SqlException("Cannot watch the socket: ${errorText()}", "08006")
        }
    }

    fun onReady() {
        waiter.exchange(null)?.resume(Unit)
    }

    private suspend fun waitFor(events: UInt) = suspendCancellableCoroutine { continuation ->
        // An interrupted wait leaves the protocol out of step, so the connection can't be saved anyway.
        continuation.invokeOnCancellation {
            waiter.store(null)
            shutdown(descriptor, SHUT_RDWR)
        }
        waiter.store(continuation)
        control(EPOLL_CTL_MOD, events or EPOLLONESHOT)
    }

    suspend fun waitForConnection() {
        waitFor(EPOLLOUT)
        memScoped {
            val failure = alloc<IntVar>()
            val size = alloc<socklen_tVar>()
            size.value = sizeOf<IntVar>().convert()
            getsockopt(descriptor, SOL_SOCKET, SO_ERROR, failure.ptr, size.ptr)
            if (failure.value != 0) throw SqlException(
                strerror(failure.value)?.toKString() ?: "error ${failure.value}", "08001"
            )
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val count = buffer.usePinned {
                recv(
                    descriptor,
                    it.addressOf(offset),
                    length.convert(),
                    0
                ).toInt()
            }
            when {
                count > 0 -> return count
                count == 0 -> return -1
                errno == EAGAIN -> waitFor(EPOLLIN)
                errno != EINTR -> throw SqlException(
                    "Cannot read from the server: ${errorText()}",
                    "08006"
                )
            }
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val count = buffer.usePinned {
                send(
                    descriptor,
                    it.addressOf(offset + done),
                    (length - done).convert(),
                    MSG_NOSIGNAL
                ).toInt()
            }
            when {
                count >= 0 -> done += count
                errno == EAGAIN -> waitFor(EPOLLOUT)
                errno != EINTR -> throw SqlException(
                    "Cannot write to the server: ${errorText()}",
                    "08006"
                )
            }
        }
    }

    override suspend fun flush() = Unit // nothing is buffered here

    override suspend fun close() {
        if (closed.exchange(1) != 0) return
        loop.remove(id)
        shutdown(descriptor, SHUT_RDWR)
        close(descriptor)
        waiter.exchange(null)
            ?.resumeWithException(SqlException("The connection is closed", "08003"))
    }
}

/** Sockets on `epoll`, without ktor's selector thread and its reader and writer coroutines. */
class EpollTransport private constructor(private val plain: EpollStream) : Transport {
    private var stream: ByteStream = plain
    private var readTimeout = 0L
    override var isSecure = false
        private set

    override val tlsServerEndPoint: ByteArray? get() = stream.tlsServerEndPoint

    override fun setReadTimeout(millis: Long) {
        readTimeout = millis
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (readTimeout <= 0) return stream.read(buffer, offset, length)
        // Not catching TimeoutCancellationException, which could be the caller's own withTimeout, and is theirs to get.
        return withTimeoutOrNull(readTimeout) { stream.read(buffer, offset, length) }
            ?: throw SqlException("Timed out waiting for the server", "08006")
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        stream.write(buffer, offset, length)

    override suspend fun flush() = stream.flush()

    override suspend fun startTls(options: TlsOptions) {
        val secure = OpenSslStream(plain, options)
        try {
            secure.handshake()
        } catch (e: Throwable) {
            secure.close()
            throw e
        }
        stream = secure
        isSecure = true
    }

    override suspend fun close() = stream.close()

    companion object Factory : TransportFactory {
        override suspend fun connect(
            host: String,
            port: Int,
            connectTimeoutMillis: Long
        ): Transport {
            // Name resolution blocks, and the caller may be on an event thread.
            val addresses = withContext(Dispatchers.IO) { resolve(host, port) }
            var failure: Exception? = null
            try {
                for (address in addresses) {
                    try {
                        val stream = withTimeoutOrNull(connectTimeoutMillis) { open(address) }
                        if (stream != null) return EpollTransport(stream)
                        failure = SqlException("timed out", "08001")
                    } catch (e: SqlException) {
                        failure = e
                    }
                }
            } finally {
                addresses.free()
            }
            throw SqlException(
                "Cannot connect to $host:$port: ${failure?.message ?: "no addresses"}",
                "08001",
                cause = failure
            )
        }

        private suspend fun open(address: ResolvedAddress): EpollStream {
            val descriptor = socket(address.family, SOCK_STREAM, 0)
            if (descriptor < 0) throw SqlException(
                "Cannot create a socket: ${errorText()}",
                "08001"
            )
            var stream: EpollStream? = null
            try {
                fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL) or O_NONBLOCK)
                setFlag(descriptor, IPPROTO_TCP, TCP_NODELAY)
                setFlag(descriptor, SOL_SOCKET, SO_KEEPALIVE)
                val loop = eventLoops[nextLoop.fetchAndAdd(1).mod(eventLoops.size)]
                stream = EpollStream(descriptor, loop)
                if (connect(descriptor, address.sockaddr, address.length) != 0) {
                    if (errno != EINPROGRESS) throw SqlException(errorText(), "08001")
                    stream.waitForConnection()
                }
                return stream
            } catch (e: Throwable) {
                if (stream != null) stream.close() else close(descriptor)
                throw e
            }
        }

        private fun setFlag(descriptor: Int, level: Int, option: Int) = memScoped {
            val on = alloc<IntVar>()
            on.value = 1
            setsockopt(descriptor, level, option, on.ptr, sizeOf<IntVar>().convert())
        }

        private fun resolve(host: String, port: Int): ResolvedAddresses = memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            val result = allocPointerTo<addrinfo>()
            val code = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
            if (code != 0) throw SqlException(
                "Cannot connect to $host:$port: ${gai_strerror(code)?.toKString()}",
                "08001"
            )
            ResolvedAddresses(result.value!!)
        }
    }
}

private class ResolvedAddress(
    val family: Int,
    val sockaddr: kotlinx.cinterop.CPointer<platform.posix.sockaddr>,
    val length: UInt
)

/** The result of `getaddrinfo`, valid until [free]. */
private class ResolvedAddresses(private val head: kotlinx.cinterop.CPointer<addrinfo>) :
    Iterable<ResolvedAddress> {
    override fun iterator() = iterator {
        var node: kotlinx.cinterop.CPointer<addrinfo>? = head
        while (node != null) {
            val info = node.pointed
            yield(ResolvedAddress(info.ai_family, info.ai_addr!!, info.ai_addrlen.convert()))
            node = info.ai_next
        }
    }

    fun free() = freeaddrinfo(head)
}

actual val DefaultTransport: TransportFactory get() = EpollTransport
