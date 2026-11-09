package ru.ttraum.kotsql

public enum class TlsMode(public val id: String) {
    /** Never encrypt. */
    DISABLE("disable"),
    /** Encrypt if the server supports it; no certificate checks. Does not protect from an active attacker. */
    PREFER("prefer"),
    /** Always encrypt; no certificate checks. */
    REQUIRE("require"),
    /** Always encrypt and verify the certificate chain. */
    VERIFY_CA("verify-ca"),
    /** Always encrypt and verify both the certificate chain and the host name. */
    VERIFY_FULL("verify-full");

    public companion object {
        public fun of(id: String): TlsMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: throw SqlException("Unknown TLS mode '$id'; expected one of ${entries.map { it.id }}", "08001")
    }
}

/** Which servers will do, among several. A primary accepts writes; a secondary is a read-only replica. */
public enum class TargetServerType(public val id: String) {
    ANY("any"),
    PRIMARY("primary"),
    SECONDARY("secondary"),

    /** A secondary if there is one in reach, otherwise whichever responds. */
    PREFER_SECONDARY("preferSecondary");

    public companion object {
        public fun of(id: String): TargetServerType = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: throw SqlException("Unknown target server type '$id'; expected one of ${entries.map { it.id }}", "08001")
    }
}

public data class ServerAddress(val host: String, val port: Int? = null)

/**
 * @param properties driver-specific settings; settings known to [ConnectionConfig.parse] are removed from it
 */
public data class ConnectionConfig(
    val scheme: String,
    val host: String = "localhost",
    val port: Int? = null,
    val database: String? = null,
    val user: String? = null,
    val password: String? = null,
    val tlsMode: TlsMode = TlsMode.PREFER,
    val tlsRootCertPath: String? = null,
    val tlsCertPath: String? = null,
    val tlsKeyPath: String? = null,
    val connectTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 0,
    val applicationName: String? = "kotsql",
    val properties: Map<String, String> = emptyMap(),
    val codecs: CodecRegistry = CodecRegistry.Global,
    /** Servers to try after [host], in order. */
    val alternateServers: List<ServerAddress> = emptyList(),
    val targetServerType: TargetServerType = TargetServerType.ANY,
    /** Tries the servers in a random order, to spread connections over them. */
    val loadBalanceHosts: Boolean = false,
) {
    val servers: List<ServerAddress> get() = listOf(ServerAddress(host, port)) + alternateServers

    /** Masks the password. */
    override fun toString(): String =
        "ConnectionConfig($scheme://${servers.joinToString(",") { a -> a.host + (a.port?.let { ":$it" } ?: "") }}/${database ?: ""}, user=$user, tls=${tlsMode.id})"

    public companion object {
        /**
         * Parses `scheme://[user[:password]@]host[:port][,host[:port]...][/database][?key=value&...]`. Recognized keys are
         * `user`, `password`, `sslmode`, `sslrootcert`, `sslcert`, `sslkey`, `connectTimeout` (ms), `socketTimeout` (ms), `applicationName`,
         * `targetServerType` and `loadBalanceHosts`.
         * Components are percent-decoded.
         */
        public fun parse(url: String): ConnectionConfig {
            fun bad(): Nothing = throw SqlException("Malformed connection URL (expected scheme://host[:port]/database)", "08001")
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) bad()
            val scheme = url.substring(0, schemeEnd)
            var rest = url.substring(schemeEnd + 3)
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?')
            val database = rest.substringAfter('/', "").ifEmpty { null }?.let(::percentDecode)
            var authority = rest.substringBefore('/')
            var user: String? = null
            var password: String? = null
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                val credentials = authority.substring(0, at)
                authority = authority.substring(at + 1)
                user = percentDecode(credentials.substringBefore(':'))
                if (':' in credentials) password = percentDecode(credentials.substringAfter(':'))
            }
            val servers = parseServers(authority)
            val props = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) for (pair in query.split('&')) {
                if (pair.isNotEmpty()) props[percentDecode(pair.substringBefore('='))] = percentDecode(pair.substringAfter('=', ""))
            }
            return ConnectionConfig(
                scheme = scheme,
                host = servers[0].host,
                port = servers[0].port,
                database = database,
                user = user,
                password = password,
                alternateServers = servers.drop(1),
            ).withProperties(props)
        }

        /** Parses `host[:port][,host[:port]...]`, with an IPv6 address in brackets. */
        public fun parseServers(text: String): List<ServerAddress> = text.split(',').map { server ->
            fun bad(): Nothing = throw SqlException("Malformed server address '$server' (expected host[:port])", "08001")
            val host: String
            val portText: String?
            if (server.startsWith("[")) {
                val close = server.indexOf(']')
                if (close < 0) bad()
                host = server.substring(1, close)
                portText = server.substring(close + 1).removePrefix(":").ifEmpty { null }
            } else {
                host = server.substringBefore(':')
                portText = if (':' in server) server.substringAfter(':') else null
            }
            ServerAddress(host.ifEmpty { "localhost" }, portText?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: bad() })
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                    out.add(s.substring(i + 1, i + 3).toInt(16).toByte())
                    i += 3
                } else {
                    out.addAll(c.toString().encodeToByteArray().asList())
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }
    }

    /** Applies settings given as key/value pairs, as in a URL query or `java.util.Properties`. */
    public fun withProperties(props: Map<String, String>): ConnectionConfig {
        var c = this
        val extra = LinkedHashMap(c.properties)
        for ((k, v) in props) when (k) {
            "user" -> c = c.copy(user = v)
            "password" -> c = c.copy(password = v)
            "sslmode" -> c = c.copy(tlsMode = TlsMode.of(v))
            "sslrootcert" -> c = c.copy(tlsRootCertPath = v)
            "sslcert" -> c = c.copy(tlsCertPath = v)
            "sslkey" -> c = c.copy(tlsKeyPath = v)
            "connectTimeout" -> c = c.copy(connectTimeoutMillis = millis(k, v))
            "socketTimeout" -> c = c.copy(socketTimeoutMillis = millis(k, v))
            "applicationName", "application_name" -> c = c.copy(applicationName = v)
            "targetServerType" -> c = c.copy(targetServerType = TargetServerType.of(v))
            "loadBalanceHosts" -> c = c.copy(loadBalanceHosts = v.toBoolean())
            else -> extra[k] = v
        }
        return c.copy(properties = extra)
    }

    private fun millis(key: String, v: String): Long =
        v.toLongOrNull()?.takeIf { it >= 0 } ?: throw SqlException("Invalid value of $key: '$v'", "08001")
}
