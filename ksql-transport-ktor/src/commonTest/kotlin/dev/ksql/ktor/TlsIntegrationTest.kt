package dev.ksql.ktor

import dev.ksql.SqlConnection
import dev.ksql.SqlException
import dev.ksql.postgres.PostgresDriver
import dev.ksql.query
import dev.ksql.spi.Drivers
import dev.ksql.transaction
import dev.ksql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Needs a server with TLS whose certificate names `localhost` only, as in compose.yaml:
 * - KSQL_TEST_TLS_PORT: its port on localhost
 * - KSQL_TEST_TLS_ROOT: a file with its certificate
 * - KSQL_TEST_TLS_OTHER_ROOT: a file with an unrelated certificate
 * - KSQL_TEST_TLS_OTHER_HOST: optionally, another name that reaches it
 */
class TlsIntegrationTest {
    private val port = testEnv("KSQL_TEST_TLS_PORT")
    private val root = testEnv("KSQL_TEST_TLS_ROOT")
    private val otherRoot = testEnv("KSQL_TEST_TLS_OTHER_ROOT")

    private fun url(host: String, mode: String, rootCert: String? = null) =
        "postgresql://ksql:ksql@$host:$port/ksql?sslmode=$mode" + (rootCert?.let { "&sslrootcert=$it" } ?: "")

    private fun tlsTest(block: suspend () -> Unit) = runTest(timeout = 120.seconds) {
        if (port == null) return@runTest println("KSQL_TEST_TLS_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block()
        }
    }

    private suspend fun <T> using(url: String, block: suspend SqlConnection.() -> T): T {
        val connection = connect(url)
        try { return connection.block() } finally { connection.close() }
    }

    private suspend fun SqlConnection.usesTls(): Boolean =
        query("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").toList().single()[0] as Boolean

    private suspend fun assertRefused(url: String, expected: String): SqlException {
        val e = assertFailsWith<SqlException>(url) { using(url) { query("SELECT 1").toList() } }
        assertContains(e.message!!, expected, ignoreCase = true)
        return e
    }

    @Test fun modesThatDoNotVerify() = tlsTest {
        assertEquals(false, using(url("localhost", "disable")) { usesTls() })
        assertEquals(true, using(url("localhost", "prefer")) { usesTls() })
        assertEquals(true, using(url("localhost", "require")) { usesTls() })
    }

    @Test fun verifyCaChecksTheChain() = tlsTest {
        // Found in the trusted file; the host name doesn't matter to this mode.
        assertEquals(true, using(url("127.0.0.1", "verify-ca", root)) { usesTls() })
        assertEquals(true, using(url("localhost", "verify-ca", root)) { usesTls() })
        // Self-signed, so not trusted by default; and a store with other certificates doesn't help.
        assertRefused(url("localhost", "verify-ca"), "TLS handshake failed")
        assertRefused(url("localhost", "verify-ca", otherRoot), "TLS handshake failed")
    }

    @Test fun verifyFullChecksTheChainAndTheHost() = tlsTest {
        assertEquals(true, using(url("localhost", "verify-full", root)) { usesTls() })
        assertEquals(true, using(url("LOCALHOST", "verify-full", root)) { usesTls() })
        // The chain alone is not enough: the certificate has no entry for an IP address...
        assertRefused(url("127.0.0.1", "verify-full", root), "TLS handshake failed")
        // ...nor for another name of the same server...
        testEnv("KSQL_TEST_TLS_OTHER_HOST")?.let { assertRefused(url(it, "verify-full", root), "TLS handshake failed") }
        // ...and the right host with an untrusted chain fails as well.
        assertRefused(url("localhost", "verify-full", otherRoot), "TLS handshake failed")
        assertRefused(url("localhost", "verify-full"), "TLS handshake failed")
    }

    @Test fun unreadableRootCertificateIsAnError() = tlsTest {
        assertRefused(url("localhost", "verify-full", "/no/such/file.pem"), "/no/such/file.pem")
    }

    @Test fun sqlStateTellsItIsAConnectionFailure() = tlsTest {
        assertEquals("08001", assertRefused(url("127.0.0.1", "verify-full", root), "TLS handshake failed").sqlState)
    }

    @Test fun passesLargeDataBothWays() = tlsTest {
        using(url("localhost", "require")) {
            // Larger than a TLS record (16 KB), the stream's buffer and the socket's, with multi-byte characters.
            val big = "дата-😀-".repeat(200_000)
            assertEquals(big, query("SELECT $1::text", listOf(big)).toList().single()[0])
            val sum = query("SELECT generate_series(1, 300000)").fold(0L) { acc, row -> acc + (row[0] as Int) }
            assertEquals(45000150000L, sum)
            val paged = query("SELECT repeat('x', 5000) FROM generate_series(1, 2000)", fetchSize = 100).map { (it[0] as String).length }.toList()
            assertEquals(List(2000) { 5000 }, paged)
        }
    }

    @Test fun regularWorkOverTls() = tlsTest {
        using(url("localhost", "verify-full", root)) {
            update("CREATE TEMP TABLE t(a int)")
            transaction { update("INSERT INTO t VALUES (1), (2)") }
            assertEquals(2L, query("SELECT count(*) FROM t").toList().single()[0])
            assertEquals("42601", assertFailsWith<SqlException> { query("SELEC").toList() }.sqlState)
            assertEquals(listOf(1, 2), query("SELECT generate_series(1, 100000)", fetchSize = 10).take(2).map { it[0] as Int }.toList())
            assertTrue(ping())
        }
    }

    @Test fun cancelWorksOverTls() = tlsTest {
        using(url("localhost", "require")) {
            val connection = this
            withContext(Dispatchers.Default) {
                launch {
                    delay(300)
                    connection.cancel()
                }
                val e = assertFailsWith<SqlException> { query("SELECT pg_sleep(30)").toList() }
                assertEquals("57014", e.sqlState)
            }
            assertEquals(1, query("SELECT 1").toList().single()[0])
        }
    }

    @Test fun manyHandshakesDoNotInterfere() = tlsTest {
        withContext(Dispatchers.Default) {
            List(20) { i ->
                launch { assertEquals(i, using(url("localhost", "verify-full", root)) { query("SELECT $i").toList().single()[0] }) }
            }.forEach { it.join() }
        }
    }
}
