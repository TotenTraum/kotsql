package dev.ksql.ktor

import dev.ksql.SqlErrorKind
import dev.ksql.SqlException
import dev.ksql.postgres.PostgresDriver
import dev.ksql.query
import dev.ksql.spi.Drivers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Needs `KSQL_TEST_SCRAM_URL`, without a query string, reaching the server in a way that requires a password. The
 * official image trusts anything from localhost, including a wrong password, so inside its network use the container's
 * IP address. `system_user` proves that SCRAM has really run rather than been skipped.
 */
class ScramIntegrationTest {
    private val base = testEnv("KSQL_TEST_SCRAM_URL")

    private fun scramTest(block: suspend (String) -> Unit) = runTest(timeout = 60.seconds) {
        val url = base ?: return@runTest println("KSQL_TEST_SCRAM_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block(url)
        }
    }

    private suspend fun authenticatedAs(url: String): Any? {
        val connection = connect(url)
        try { return connection.query("SELECT system_user").toList().single()[0] } finally { connection.close() }
    }

    @Test fun rightPassword() = scramTest { url ->
        assertEquals("scram-sha-256:ksql", authenticatedAs("$url?sslmode=disable"))
        assertEquals("scram-sha-256:ksql", authenticatedAs("$url?sslmode=require"))
    }

    @Test fun wrongPassword() = scramTest { url ->
        val e = assertFailsWith<SqlException> { authenticatedAs(url.replace(":ksql@", ":wrong@") + "?sslmode=disable") }
        assertEquals("28P01", e.sqlState)
        assertEquals(SqlErrorKind.AUTHENTICATION, e.kind)
    }
}
