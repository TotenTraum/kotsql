package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.InternalKotsqlApi
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.transport.TlsOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/** A client certificate with what comes with it. */
@InternalKotsqlApi
public class ClientCertificate(public val chain: Array<X509Certificate>, public val key: PrivateKey)

/** Loads the client certificate of [options], if it has one, from PEM files. A key protected by a password isn't supported. */
@InternalKotsqlApi
public fun loadClientCertificate(options: TlsOptions): ClientCertificate? {
    val certPath = options.certPath
    val keyPath = options.keyPath
    if (certPath == null && keyPath == null) return null
    if (certPath == null || keyPath == null) throw SqlException("A client certificate takes both sslcert and sslkey", "08001")
    try {
        val chain = File(certPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .map { it as X509Certificate }.toTypedArray()
        if (chain.isEmpty()) throw SqlException("No certificates found in '$certPath'", "08001")
        return ClientCertificate(chain, parsePrivateKey(File(keyPath).readText(), chain[0].publicKey.algorithm, keyPath))
    } catch (e: IOException) {
        throw SqlException("Cannot load the client certificate: ${e.message}", "08001", cause = e)
    } catch (e: GeneralSecurityException) {
        throw SqlException("Cannot load the client certificate: ${e.message}", "08001", cause = e)
    }
}

private fun parsePrivateKey(pem: String, algorithm: String, path: String): PrivateKey {
    fun body(label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        val from = pem.indexOf(begin)
        if (from < 0) return null
        val to = pem.indexOf("-----END $label-----", from)
        if (to < 0) throw SqlException("Malformed private key in '$path'", "08001")
        return Base64.getMimeDecoder().decode(pem.substring(from + begin.length, to))
    }
    val pkcs8 = body("PRIVATE KEY") ?: body("RSA PRIVATE KEY")?.let(::pkcs1ToPkcs8) ?: throw SqlException(
        if ("ENCRYPTED" in pem) "The private key in '$path' is protected by a password, which isn't supported"
        else "The private key in '$path' isn't in PKCS#8 or PKCS#1 PEM format",
        "08001",
    )
    return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

/** Java reads only PKCS#8, which is PKCS#1 with a header that says it's RSA. */
private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
    fun der(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            content.size < 0x10000 -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
            else -> byteArrayOf(0x83.toByte(), (content.size shr 16).toByte(), (content.size shr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
    val version = byteArrayOf(0x02, 0x01, 0x00)
    val rsaEncryption = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00)
    return der(0x30, version + rsaEncryption + der(0x04, pkcs1))
}

/** What presents the client certificate of [options] to a server, or null if it has none. */
internal fun clientKeyManagers(options: TlsOptions): Array<KeyManager>? {
    val certificate = loadClientCertificate(options) ?: return null
    val password = CharArray(0)
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setKeyEntry("client", certificate.key, password, certificate.chain)
    }
    return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }.keyManagers
}

/**
 * The `tls-server-end-point` channel binding of RFC 5929: a hash of the server's certificate, by the algorithm of its
 * signature, with SHA-256 in place of anything weaker. Null when that algorithm doesn't name a hash, as for RSASSA-PSS.
 */
@InternalKotsqlApi
public fun tlsServerEndPoint(certificate: Certificate): ByteArray? {
    val signature = (certificate as? X509Certificate)?.sigAlgName?.uppercase() ?: return null
    val digest = when {
        signature.startsWith("SHA512") -> "SHA-512"
        signature.startsWith("SHA384") -> "SHA-384"
        signature.startsWith("SHA224") -> "SHA-224"
        signature.startsWith("SHA256") || signature.startsWith("SHA1") || signature.startsWith("MD5") -> "SHA-256"
        else -> return null
    }
    return MessageDigest.getInstance(digest).digest(ByteArrayInputStream(certificate.encoded).readBytes())
}
