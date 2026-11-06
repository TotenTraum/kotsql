package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.transport.TlsOptions
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Blocking transport on `java.net.Socket`. Its "suspending" calls never suspend, as [runUnsuspended] requires.
 * It parks rather than pins virtual threads.
 */
class SocketTransport private constructor(private var socket: Socket) : Transport {
    private var input: InputStream = socket.getInputStream()
    private var output: OutputStream = socket.getOutputStream().buffered(16 * 1024)
    override var isSecure = false
        private set

    override fun setReadTimeout(millis: Long) { socket.soTimeout = millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        try { input.read(buffer, offset, length) } catch (e: SocketTimeoutException) {
            throw SqlException("Timed out waiting for the server", "08006", cause = e)
        }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) = output.write(buffer, offset, length)
    override suspend fun flush() = output.flush()

    override var tlsServerEndPoint: ByteArray? = null
        private set

    override suspend fun startTls(options: TlsOptions) {
        output.flush()
        try {
            val factory = sslFactory(options)
            val ssl = factory.createSocket(socket, options.serverName, socket.port, true) as SSLSocket
            if (options.mode == TlsMode.VERIFY_FULL) {
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            ssl.startHandshake()
            socket = ssl
            input = ssl.inputStream
            output = ssl.outputStream.buffered(16 * 1024)
            isSecure = true
            tlsServerEndPoint = ssl.session.peerCertificates.firstOrNull()?.let(::tlsServerEndPoint)
        } catch (e: SSLException) {
            throw SqlException("TLS handshake failed: ${e.message}", "08001", cause = e)
        }
    }

    override suspend fun close() { runCatching { socket.close() } }

    companion object Factory : TransportFactory {
        override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (e: java.io.IOException) {
                runCatching { socket.close() }
                throw SqlException("Cannot connect to $host:$port: ${e.message}", "08001", cause = e)
            }
            return SocketTransport(socket)
        }

        /** The JVM's TLS setup for [options]; also used by other JVM transports. */
        fun sslContext(options: TlsOptions): SSLContext = SSLContext.getInstance("TLS").apply {
            init(clientKeyManagers(options), arrayOf(trustManager(options)), null)
        }

        private fun sslFactory(options: TlsOptions): SSLSocketFactory = sslContext(options).socketFactory

        private fun loadRootCertificates(path: String): KeyStore {
            val certificates = try {
                FileInputStream(path).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            } catch (e: java.io.IOException) {
                throw SqlException("Cannot load the certificates in '$path': ${e.message}", "08001", cause = e)
            } catch (e: java.security.cert.CertificateException) {
                throw SqlException("Cannot load the certificates in '$path': ${e.message}", "08001", cause = e)
            }
            // An empty store would refuse every server, so say what is actually wrong.
            if (certificates.isEmpty()) throw SqlException("No certificates found in '$path'", "08001")
            return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
            }
        }

        fun trustManager(options: TlsOptions): TrustManager = when (options.mode) {
            TlsMode.VERIFY_CA, TlsMode.VERIFY_FULL -> {
                val store = options.rootCertPath?.let(::loadRootCertificates)
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().first()
            }
            else -> object : X509TrustManager {
                // PREFER and REQUIRE encrypt without authenticating the server, by definition of those modes.
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        }
    }
}
