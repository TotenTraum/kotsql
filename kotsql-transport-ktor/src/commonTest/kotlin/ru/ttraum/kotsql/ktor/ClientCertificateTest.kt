package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.connect
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** What the same tests do on the other transports of a platform that has them. */
internal val tlsTransports: List<TransportFactory> get() = listOf(DefaultTransport) + extraTransports

internal expect val extraTransports: List<TransportFactory>

/** Those that can present a certificate with an EC key. */
internal expect val ecCertificateTransports: List<TransportFactory>

/** Reports a wrong certificate hash to SCRAM, as the connection of an attacker in the middle would do. */
private class WrongBinding(private val factory: TransportFactory) : TransportFactory {
    override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
        val inner = factory.connect(host, port, connectTimeoutMillis)
        return object : Transport by inner {
            override val tlsServerEndPoint: ByteArray? get() = inner.tlsServerEndPoint?.let { ByteArray(it.size) }
            override suspend fun startTls(options: TlsOptions) = inner.startTls(options)
        }
    }
}

/** Needs the certificates and the roles of `tools/run-client-tests.sh`. */
class ClientCertificateTest {
    private val certs = testEnv("KOTSQL_TEST_CERTS")
    private val port = testEnv("KOTSQL_TEST_TLS_PORT")
    private val scramUrl = testEnv("KOTSQL_TEST_SCRAM_URL")
    private val md5Address = testEnv("KOTSQL_TEST_MD5_ADDRESS")
    private val mysqlAddress = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")

    private fun tlsTest(block: suspend () -> Unit) = runTest(timeout = 60.seconds) {
        if (certs == null || port == null) return@runTest println("KOTSQL_TEST_CERTS is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            block()
        }
    }

    private suspend fun identity(url: String, transports: TransportFactory): String {
        val connection = connect(url, transports)
        try {
            return connection.query("SELECT system_user").first()[0].toString()
        } finally {
            connection.close()
        }
    }

    private fun certUrl(cert: String?, key: String? = cert) = "postgresql://kotsql_cert@localhost:$port/kotsql?sslmode=require" +
        (cert?.let { "&sslcert=$certs/$it.crt" } ?: "") + (key?.let { "&sslkey=$certs/$it.key" } ?: "")

    @Test fun postgresRsaCertificate() = tlsTest {
        for (t in tlsTransports) assertEquals("cert:CN=kotsql_cert", identity(certUrl("rsa"), t), "$t")
    }

    @Test fun postgresEcCertificate() = tlsTest {
        for (t in ecCertificateTransports) assertEquals("cert:CN=kotsql_cert", identity(certUrl("ec"), t), "$t")
        for (t in tlsTransports - ecCertificateTransports.toSet()) {
            val e = assertFailsWith<SqlException>("$t") { identity(certUrl("ec"), t) }
            assertEquals("08001", e.sqlState, e.message)
        }
    }

    @Test fun postgresNoCertificateIsRefused() = tlsTest {
        for (t in tlsTransports) {
            assertEquals(SqlErrorKind.AUTHENTICATION, assertFailsWith<SqlException>("$t") { identity(certUrl(null), t) }.kind)
        }
    }

    @Test fun aKeyOfAnotherCertificate() = tlsTest {
        for (t in tlsTransports) assertFailsWith<SqlException>("$t") {
            val url = "postgresql://kotsql_cert@localhost:$port/kotsql?sslmode=require&sslcert=$certs/rsa.crt&sslkey=$certs/ec.key"
            identity(url, t)
        }
    }

    @Test fun aCertificateWithoutAKey() = tlsTest {
        for (t in tlsTransports) {
            val e = assertFailsWith<SqlException>("$t") { identity(certUrl("rsa", null), t) }
            assertEquals("08001", e.sqlState, e.message)
        }
    }

    @Test fun missingFiles() = tlsTest {
        for (t in tlsTransports) {
            val e = assertFailsWith<SqlException>("$t") { identity("postgresql://kotsql_cert@localhost:$port/kotsql?sslmode=require&sslcert=$certs/none.crt&sslkey=$certs/none.key", t) }
            assertEquals("08001", e.sqlState, e.message)
        }
    }

    /** The server's own CA, whose keys come in PKCS#1 from MySQL. */
    @Test fun mysqlCertificate() = tlsTest {
        val address = mysqlAddress ?: return@tlsTest println("KOTSQL_TEST_MYSQL_HOST_PORT is not set; skipping")
        val base = "mysql://kotsql_x509:kotsql@$address/kotsql?sslmode=require&allowPublicKeyRetrieval=true"
        for (t in tlsTransports) {
            val connection = connect("$base&sslcert=$certs/mysql.crt&sslkey=$certs/mysql.key", t)
            try {
                assertEquals(1, (connection.query("SELECT 1").first()[0] as Number).toInt())
            } finally {
                connection.close()
            }
            assertEquals(SqlErrorKind.AUTHENTICATION, assertFailsWith<SqlException>("$t") { connect(base, t) }.kind)
        }
    }

    // ---- Channel binding ----

    private fun scramTls(options: String) = scramUrl!! + "?sslmode=require" + options

    @Test fun bindingWorks() = tlsTest {
        if (scramUrl == null) return@tlsTest
        for (t in tlsTransports) {
            assertEquals("scram-sha-256:kotsql", identity(scramTls("&channel_binding=require"), t), "$t")
            assertEquals("scram-sha-256:kotsql", identity(scramTls(""), t), "$t")
            assertEquals("scram-sha-256:kotsql", identity(scramTls("&channel_binding=disable"), t), "$t")
        }
    }

    /** The server takes the certificate's hash from the client's proof, so a wrong one shows that it really is checked. */
    @Test fun aWrongCertificateIsNoticedByTheServer() = tlsTest {
        if (scramUrl == null) return@tlsTest
        for (t in tlsTransports) {
            for (options in listOf("&channel_binding=require", "")) {
                val e = assertFailsWith<SqlException>("$t") { identity(scramTls(options), WrongBinding(t)) }
                assertEquals(SqlErrorKind.AUTHENTICATION, e.kind, e.message)
            }
            assertEquals("scram-sha-256:kotsql", identity(scramTls("&channel_binding=disable"), WrongBinding(t)), "$t")
        }
    }

    @Test fun bindingNeedsEncryption() = tlsTest {
        if (scramUrl == null) return@tlsTest
        for (t in tlsTransports) {
            val e = assertFailsWith<SqlException>("$t") { identity(scramUrl + "?sslmode=disable&channel_binding=require", t) }
            assertEquals(SqlErrorKind.AUTHENTICATION, e.kind, e.message)
        }
    }

    /** A server asking for anything but SCRAM gets nothing that it could use against the real one. */
    @Test fun bindingRefusesWeakerMethods() = tlsTest {
        val address = md5Address ?: return@tlsTest
        for (t in tlsTransports) {
            val e = assertFailsWith<SqlException>("$t") { identity("postgresql://kotsql_md5:kotsql@$address/kotsql?sslmode=require&channel_binding=require", t) }
            assertEquals("28000", e.sqlState, e.message)
            assertTrue("channel_binding" in e.message.orEmpty(), e.message)
        }
    }

    @Test fun invalidBindingSetting() = tlsTest {
        if (scramUrl == null) return@tlsTest
        assertNotEquals(null, assertFailsWith<SqlException> { identity(scramTls("&channel_binding=maybe"), DefaultTransport) }.message)
    }
}
