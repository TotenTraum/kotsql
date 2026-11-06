package dev.ksql

enum class TlsMode(val id: String) {
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

    companion object {
        fun of(id: String): TlsMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: throw SqlException("Unknown TLS mode '$id'; expected one of ${entries.map { it.id }}", "08001")
    }
}

data class TlsOptions(val mode: TlsMode, val serverName: String, val rootCertPath: String? = null)

/**
 * @param properties driver-specific settings; settings known to [ConnectionConfig.parse] are removed from it
 */
data class ConnectionConfig(
    val scheme: String,
    val host: String = "localhost",
    val port: Int? = null,
    val database: String? = null,
    val user: String? = null,
    val password: String? = null,
    val tlsMode: TlsMode = TlsMode.PREFER,
    val tlsRootCertPath: String? = null,
    val connectTimeoutMillis: Long = 10_000,
    val socketTimeoutMillis: Long = 0,
    val applicationName: String? = "ksql",
    val properties: Map<String, String> = emptyMap(),
    val codecs: CodecRegistry = CodecRegistry.Global,
) {
    /** Masks the password. */
    override fun toString(): String =
        "ConnectionConfig($scheme://$host${port?.let { ":$it" } ?: ""}/${database ?: ""}, user=$user, tls=${tlsMode.id})"

    companion object {
        /**
         * Parses `scheme://[user[:password]@]host[:port][/database][?key=value&...]`. Recognized keys are `user`,
         * `password`, `sslmode`, `sslrootcert`, `connectTimeout` (ms), `socketTimeout` (ms) and `applicationName`.
         * Components are percent-decoded.
         */
        fun parse(url: String): ConnectionConfig {
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
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) bad()
                host = authority.substring(1, close)
                portText = authority.substring(close + 1).removePrefix(":").ifEmpty { null }
            } else {
                host = authority.substringBefore(':')
                portText = if (':' in authority) authority.substringAfter(':') else null
            }
            val port = portText?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: bad() }
            val props = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) for (pair in query.split('&')) {
                if (pair.isNotEmpty()) props[percentDecode(pair.substringBefore('='))] = percentDecode(pair.substringAfter('=', ""))
            }
            return ConnectionConfig(scheme = scheme, host = host.ifEmpty { "localhost" }, port = port, database = database, user = user, password = password)
                .withProperties(props)
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
    fun withProperties(props: Map<String, String>): ConnectionConfig {
        var c = this
        val extra = LinkedHashMap(c.properties)
        for ((k, v) in props) when (k) {
            "user" -> c = c.copy(user = v)
            "password" -> c = c.copy(password = v)
            "sslmode" -> c = c.copy(tlsMode = TlsMode.of(v))
            "sslrootcert" -> c = c.copy(tlsRootCertPath = v)
            "connectTimeout" -> c = c.copy(connectTimeoutMillis = millis(k, v))
            "socketTimeout" -> c = c.copy(socketTimeoutMillis = millis(k, v))
            "applicationName", "application_name" -> c = c.copy(applicationName = v)
            else -> extra[k] = v
        }
        return c.copy(properties = extra)
    }

    private fun millis(key: String, v: String): Long =
        v.toLongOrNull()?.takeIf { it >= 0 } ?: throw SqlException("Invalid value of $key: '$v'", "08001")
}
