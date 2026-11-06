@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.ktor.openssl.EVP_get_digestbyname
import ru.ttraum.kotsql.ktor.openssl.OBJ_nid2sn
import ru.ttraum.kotsql.ktor.openssl.EVP_sha256
import ru.ttraum.kotsql.ktor.openssl.OBJ_find_sigid_algs
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_check_private_key
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_use_PrivateKey_file
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_use_certificate_chain_file
import ru.ttraum.kotsql.ktor.openssl.X509_digest
import ru.ttraum.kotsql.ktor.openssl.X509_get_signature_nid
import ru.ttraum.kotsql.ktor.openssl.BIO
import ru.ttraum.kotsql.ktor.openssl.BIO_ctrl_pending
import ru.ttraum.kotsql.ktor.openssl.BIO_new
import ru.ttraum.kotsql.ktor.openssl.BIO_read
import ru.ttraum.kotsql.ktor.openssl.BIO_s_mem
import ru.ttraum.kotsql.ktor.openssl.BIO_write
import ru.ttraum.kotsql.ktor.openssl.ERR_clear_error
import ru.ttraum.kotsql.ktor.openssl.ERR_error_string_n
import ru.ttraum.kotsql.ktor.openssl.ERR_get_error
import ru.ttraum.kotsql.ktor.openssl.SSL
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_ctrl
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_free
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_load_verify_file
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_new
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_set_default_verify_paths
import ru.ttraum.kotsql.ktor.openssl.SSL_CTX_set_verify
import ru.ttraum.kotsql.ktor.openssl.SSL_ctrl
import ru.ttraum.kotsql.ktor.openssl.SSL_do_handshake
import ru.ttraum.kotsql.ktor.openssl.SSL_free
import ru.ttraum.kotsql.ktor.openssl.SSL_get0_param
import ru.ttraum.kotsql.ktor.openssl.SSL_get1_peer_certificate
import ru.ttraum.kotsql.ktor.openssl.SSL_get_error
import ru.ttraum.kotsql.ktor.openssl.SSL_get_verify_result
import ru.ttraum.kotsql.ktor.openssl.SSL_new
import ru.ttraum.kotsql.ktor.openssl.SSL_read
import ru.ttraum.kotsql.ktor.openssl.SSL_set1_host
import ru.ttraum.kotsql.ktor.openssl.SSL_set_bio
import ru.ttraum.kotsql.ktor.openssl.SSL_set_connect_state
import ru.ttraum.kotsql.ktor.openssl.SSL_set_hostflags
import ru.ttraum.kotsql.ktor.openssl.SSL_write
import ru.ttraum.kotsql.ktor.openssl.TLS_client_method
import ru.ttraum.kotsql.ktor.openssl.X509_VERIFY_PARAM_set1_ip_asc
import ru.ttraum.kotsql.ktor.openssl.X509_free
import ru.ttraum.kotsql.ktor.openssl.X509_verify_cert_error_string
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import kotlin.coroutines.CoroutineContext

// Checked against the headers by tools/check-openssl-decls.sh.
private const val SSL_VERIFY_NONE = 0
private const val SSL_VERIFY_PEER = 1
private const val SSL_ERROR_WANT_READ = 2
private const val SSL_ERROR_WANT_WRITE = 3
private const val SSL_ERROR_ZERO_RETURN = 6
private const val SSL_CTRL_SET_TLSEXT_HOSTNAME = 55
private const val TLSEXT_NAMETYPE_HOST_NAME = 0L
private const val SSL_CTRL_SET_MIN_PROTO_VERSION = 123
private const val TLS1_2_VERSION = 0x0303L
private const val X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS = 4u
private const val X509_V_OK = 0L
private const val SSL_FILETYPE_PEM = 1
private const val NID_MD5 = 4
private const val NID_SHA1 = 64
private const val EVP_MAX_MD_SIZE = 64

private const val BUFFER_SIZE = 16 * 1024

/** Where operating systems keep their CA bundle. The OpenSSL build's own default directory need not exist on this machine. */
private val SYSTEM_CA_BUNDLES = listOf(
    "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu, Arch
    "/etc/pki/tls/certs/ca-bundle.crt", // Fedora, RHEL
    "/etc/ssl/ca-bundle.pem", // openSUSE
    "/etc/ssl/cert.pem", // macOS, Alpine
)

internal actual suspend fun upgradeToTls(plain: SocketStream, options: TlsOptions, context: CoroutineContext): ByteStream {
    val stream = OpenSslStream(plain, options)
    try {
        stream.handshake()
    } catch (e: Throwable) {
        stream.close()
        throw e
    }
    return stream
}

/**
 * TLS with OpenSSL working purely on memory buffers: it never touches the socket. [inner] carries the encrypted bytes,
 * so all waiting stays non-blocking.
 *
 * OpenSSL's error queue is per thread and a coroutine may change threads when it suspends, so each OpenSSL call and the
 * look at its errors must happen with no suspension in between. Not safe for concurrent use, like the connection above it.
 */
internal class OpenSslStream(private val inner: ByteStream, private val options: TlsOptions) : ByteStream {
    private val ctx: CPointer<SSL_CTX>
    private val ssl: CPointer<SSL>
    private val fromNetwork: CPointer<BIO> // ciphertext going into OpenSSL
    private val toNetwork: CPointer<BIO> // ciphertext coming out of OpenSSL
    private val buffer = ByteArray(BUFFER_SIZE)
    private var closed = false

    init {
        ERR_clear_error()
        ctx = SSL_CTX_new(TLS_client_method()) ?: throw tlsError("Cannot create a TLS context")
        var created: CPointer<SSL>? = null
        try {
            configureContext()
            created = SSL_new(ctx) ?: throw tlsError("Cannot create a TLS session")
            val read = BIO_new(BIO_s_mem()) ?: throw tlsError("Cannot allocate a buffer")
            val write = BIO_new(BIO_s_mem())
            if (write == null) {
                SSL_set_bio(created, read, read) // hands the only BIO to the session, so that SSL_free frees it
                throw tlsError("Cannot allocate a buffer")
            }
            SSL_set_bio(created, read, write)
            fromNetwork = read
            toNetwork = write
            SSL_set_connect_state(created)
            configureSession(created)
        } catch (e: Throwable) {
            created?.let(::SSL_free)
            SSL_CTX_free(ctx)
            throw e
        }
        ssl = created
    }

    private fun loadClientCertificate() {
        val cert = options.certPath
        val key = options.keyPath
        if (cert == null && key == null) return
        if (cert == null || key == null) throw SqlException("A client certificate takes both sslcert and sslkey", "08001")
        if (SSL_CTX_use_certificate_chain_file(ctx, cert) != 1) throw tlsError("Cannot load the client certificate in '$cert'")
        if (SSL_CTX_use_PrivateKey_file(ctx, key, SSL_FILETYPE_PEM) != 1) throw tlsError("Cannot load the private key in '$key'")
        if (SSL_CTX_check_private_key(ctx) != 1) throw tlsError("The private key in '$key' isn't the client certificate's")
    }

    private fun configureContext() {
        if (SSL_CTX_ctrl(ctx, SSL_CTRL_SET_MIN_PROTO_VERSION, TLS1_2_VERSION, null) != 1L) throw tlsError("Cannot set the TLS version")
        loadClientCertificate()
        when (options.mode) {
            // PREFER and REQUIRE encrypt without authenticating the server, by definition of those modes.
            TlsMode.DISABLE, TlsMode.PREFER, TlsMode.REQUIRE -> SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, null)
            TlsMode.VERIFY_CA, TlsMode.VERIFY_FULL -> {
                loadTrustedCertificates()
                SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null)
            }
        }
    }

    private fun loadTrustedCertificates() {
        val root = options.rootCertPath
        if (root != null) {
            if (SSL_CTX_load_verify_file(ctx, root) != 1) throw tlsError("Cannot load the certificates in '$root'")
            return
        }
        // Honours SSL_CERT_FILE and SSL_CERT_DIR, and finds nothing if they are unset and the build's directory is absent.
        SSL_CTX_set_default_verify_paths(ctx)
        ERR_clear_error()
        if (getenv("SSL_CERT_FILE") != null || getenv("SSL_CERT_DIR") != null) return
        SYSTEM_CA_BUNDLES.firstOrNull { access(it, F_OK) == 0 }?.let { bundle ->
            if (SSL_CTX_load_verify_file(ctx, bundle) != 1) throw tlsError("Cannot load the certificates in '$bundle'")
        }
    }

    private fun configureSession(session: CPointer<SSL>) {
        val host = options.serverName
        val isIp = isIpLiteral(host)
        // Server Name Indication takes no IP addresses.
        if (!isIp) memScoped {
            if (SSL_ctrl(session, SSL_CTRL_SET_TLSEXT_HOSTNAME, TLSEXT_NAMETYPE_HOST_NAME, host.cstr.ptr) != 1L) {
                throw tlsError("Cannot set the server name")
            }
        }
        if (options.mode != TlsMode.VERIFY_FULL) return
        val hostSet = if (isIp) {
            X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(session), host)
        } else {
            SSL_set_hostflags(session, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS)
            SSL_set1_host(session, host)
        }
        // Without this the handshake would succeed for a certificate of any host, so it must not be ignored.
        if (hostSet != 1) throw tlsError("Cannot set '$host' as the host to verify")
    }

    override var tlsServerEndPoint: ByteArray? = null
        private set

    /** RFC 5929: a hash of the certificate by the algorithm of its signature, with SHA-256 in place of anything weaker. */
    private fun computeServerEndPoint(): ByteArray? = memScoped {
        val certificate = SSL_get1_peer_certificate(ssl) ?: return null
        try {
            val digestNid = alloc<IntVar>()
            if (OBJ_find_sigid_algs(X509_get_signature_nid(certificate), digestNid.ptr, null) != 1) return null
            val digest = if (digestNid.value == NID_MD5 || digestNid.value == NID_SHA1) EVP_sha256() else EVP_get_digestbyname(OBJ_nid2sn(digestNid.value)?.toKString())
            if (digest == null) return null
            val hash = allocArray<UByteVar>(EVP_MAX_MD_SIZE)
            val length = alloc<UIntVar>()
            if (X509_digest(certificate, digest, hash, length.ptr) != 1) return null
            ByteArray(length.value.toInt()) { hash[it].toByte() }
        } finally {
            X509_free(certificate)
            ERR_clear_error()
        }
    }

    suspend fun handshake() {
        while (true) {
            ERR_clear_error()
            val result = SSL_do_handshake(ssl)
            val failure = if (result == 1) 0 else SSL_get_error(ssl, result)
            val message = if (result == 1 || failure == SSL_ERROR_WANT_READ || failure == SSL_ERROR_WANT_WRITE) null else handshakeFailure()
            sendPending()
            when {
                message != null -> throw SqlException(message, "08001")
                result == 1 -> break
                failure == SSL_ERROR_WANT_READ && !receive() ->
                    throw SqlException("TLS handshake failed: the server closed the connection", "08001")
            }
        }
        inner.flush()
        checkPeer()
        tlsServerEndPoint = computeServerEndPoint()
    }

    private fun handshakeFailure(): String {
        val verify = SSL_get_verify_result(ssl)
        val reason = if (verify != X509_V_OK) "certificate verification failed: " + X509_verify_cert_error_string(verify)?.toKString() else lastError()
        return "TLS handshake failed: $reason"
    }

    /** A last line of defence: a verifying mode must never end up connected to an unverified server. */
    private fun checkPeer() {
        if (options.mode != TlsMode.VERIFY_CA && options.mode != TlsMode.VERIFY_FULL) return
        val certificate = SSL_get1_peer_certificate(ssl)
            ?: throw SqlException("TLS handshake failed: the server sent no certificate", "08001")
        X509_free(certificate)
        val verify = SSL_get_verify_result(ssl)
        if (verify != X509_V_OK) {
            throw SqlException("TLS handshake failed: certificate verification failed: " + X509_verify_cert_error_string(verify)?.toKString(), "08001")
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            ERR_clear_error()
            val n = buffer.usePinned { SSL_read(ssl, it.addressOf(offset), length) }
            if (n > 0) return n
            val failure = SSL_get_error(ssl, n)
            if (failure == SSL_ERROR_ZERO_RETURN) return -1
            if (failure != SSL_ERROR_WANT_READ && failure != SSL_ERROR_WANT_WRITE) {
                throw SqlException("TLS failure while reading: ${lastError()}", "08006")
            }
            // Reading can produce output too, such as a response to a key update.
            if (sendPending()) inner.flush()
            if (failure == SSL_ERROR_WANT_READ && !receive()) return -1
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            ERR_clear_error()
            val n = buffer.usePinned { SSL_write(ssl, it.addressOf(offset + done), length - done) }
            if (n <= 0) {
                val failure = SSL_get_error(ssl, n)
                if (failure != SSL_ERROR_WANT_READ && failure != SSL_ERROR_WANT_WRITE) {
                    throw SqlException("TLS failure while writing: ${lastError()}", "08006")
                }
                sendPending()
                inner.flush()
                if (failure == SSL_ERROR_WANT_READ && !receive()) throw SqlException("The server closed the connection", "08006")
            } else {
                done += n
            }
            sendPending()
        }
    }

    override suspend fun flush() {
        sendPending()
        inner.flush()
    }

    /** Moves ciphertext from OpenSSL to the socket. Returns whether there was any. */
    private suspend fun sendPending(): Boolean {
        var sent = false
        while (BIO_ctrl_pending(toNetwork) > 0u) {
            val n = buffer.usePinned { BIO_read(toNetwork, it.addressOf(0), buffer.size) }
            if (n <= 0) break
            inner.write(buffer, 0, n)
            sent = true
        }
        if (sent) inner.flush()
        return sent
    }

    /** Moves ciphertext from the socket to OpenSSL. Returns false at end of stream. */
    private suspend fun receive(): Boolean {
        val n = inner.read(buffer, 0, buffer.size)
        if (n < 0) return false
        if (n > 0) {
            val written = buffer.usePinned { BIO_write(fromNetwork, it.addressOf(0), n) }
            if (written != n) throw SqlException("TLS failure: out of memory", "08006")
        }
        return true
    }

    private fun lastError(): String {
        val code = ERR_get_error()
        if (code == 0uL) return "unknown error"
        return memScoped {
            val text = allocArray<ByteVar>(256)
            ERR_error_string_n(code, text, 256u.convert())
            text.toKString()
        }
    }

    private fun tlsError(what: String) = SqlException("$what: ${lastError()}", "08001")

    override suspend fun close() {
        if (closed) return
        closed = true
        // No close_notify: the protocol above has already said goodbye, and truncation can't harm it at this point.
        inner.close()
        SSL_free(ssl) // frees both BIOs
        SSL_CTX_free(ctx)
    }
}
