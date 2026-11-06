package dev.ksql.ktor

import dev.ksql.SqlException
import dev.ksql.TlsMode
import dev.ksql.TlsOptions
import dev.ksql.spi.SocketTransport
import io.ktor.network.sockets.Connection
import io.ktor.network.tls.TlsException
import io.ktor.network.tls.tls
import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext

internal actual suspend fun upgradeToTls(plain: SocketStream, options: TlsOptions, context: CoroutineContext): ByteStream = try {
    val socket = Connection(plain.socket, plain.input, plain.output).tls(context) {
        val chainCheck = SocketTransport.trustManager(options) as X509TrustManager
        trustManager = if (options.mode == TlsMode.VERIFY_FULL) HostCheckingTrustManager(chainCheck, options.serverName) else chainCheck
        // Only for SNI, which takes no IP addresses. ktor's own host check is not relied upon: it accepts a certificate
        // that lists no names at all, and any certificate without an IP entry when connecting by IP.
        if (options.mode == TlsMode.VERIFY_FULL && !isIpLiteral(options.serverName)) serverName = options.serverName
    }
    SocketStream(socket)
} catch (e: TlsException) {
    throw SqlException("TLS handshake failed: ${e.message}", "08001", cause = e)
} catch (e: CertificateException) {
    throw SqlException("TLS handshake failed: ${e.message}", "08001", cause = e)
}

/** Checks the chain, then requires the leaf certificate to name [host]. */
private class HostCheckingTrustManager(private val delegate: X509TrustManager, private val host: String) : X509TrustManager by delegate {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        val leaf = chain.firstOrNull() ?: throw CertificateException("The server sent no certificate")
        if (!certificateMatchesHost(leaf.subjectAlternativeNames.orEmpty().map { it[0] as Int to it[1].toString() }, leaf.subjectX500Principal.name, host)) {
            throw CertificateException("The server certificate does not match the host '$host'")
        }
    }
}

private const val SAN_DNS = 2
private const val SAN_IP = 7

/**
 * RFC 6125 matching. An IP address must be listed as an IP entry. A name must match a DNS entry, or the subject's common
 * name only if the certificate has no DNS entries at all, as libpq does.
 */
internal fun certificateMatchesHost(altNames: List<Pair<Int, String>>, subjectDn: String, host: String): Boolean {
    if (isIpLiteral(host)) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return altNames.any { (type, value) ->
            type == SAN_IP && runCatching { InetAddress.getByName(value) }.getOrNull() == address
        }
    }
    val dnsNames = altNames.filter { it.first == SAN_DNS }.map { it.second }
    if (dnsNames.isNotEmpty()) return dnsNames.any { hostMatches(it, host) }
    val commonName = javax.naming.ldap.LdapName(subjectDn).rdns.lastOrNull { it.type.equals("CN", ignoreCase = true) }?.value?.toString()
    return commonName != null && hostMatches(commonName, host)
}

private fun hostMatches(pattern: String, host: String): Boolean {
    val p = pattern.trimEnd('.').lowercase()
    val h = host.trimEnd('.').lowercase()
    if (!p.startsWith("*.")) return p == h
    // A wildcard stands for exactly one whole label, and needs at least two labels after it.
    val suffix = p.substring(1)
    return suffix.count { it == '.' } >= 2 && h.endsWith(suffix) && h.length > suffix.length && '.' !in h.removeSuffix(suffix)
}
