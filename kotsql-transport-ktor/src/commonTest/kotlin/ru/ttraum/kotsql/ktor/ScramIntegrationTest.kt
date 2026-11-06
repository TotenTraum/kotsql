package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Needs `KOTSQL_TEST_SCRAM_URL`, without a query string, reaching the server in a way that requires a password. The
 * official image trusts anything from localhost, including a wrong password, so inside its network use the container's
 * IP address. `system_user` proves that SCRAM has really run rather than been skipped.
 */
class ScramIntegrationTest {
    private val base = testEnv("KOTSQL_TEST_SCRAM_URL")

    private fun scramTest(block: suspend (String) -> Unit) = runTest(timeout = 60.seconds) {
        val url = base ?: return@runTest println("KOTSQL_TEST_SCRAM_URL is not set; skipping")
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
        assertEquals("scram-sha-256:kotsql", authenticatedAs("$url?sslmode=disable"))
        assertEquals("scram-sha-256:kotsql", authenticatedAs("$url?sslmode=require"))
    }

    /** The roles of `tools/run-client-tests.sh`, whose passwords are in another form than the same ones typed here. */
    @Test fun passwordsAreNormalized() = scramTest { url ->
        val address = url.substringAfter('@')
        // A no-break space and a soft hyphen, in the password that the server holds as "pass word".
        assertEquals("scram-sha-256:kotsql_prep", authenticatedAs("postgresql://kotsql_prep:pass%20word@$address?sslmode=disable"))
        assertEquals("scram-sha-256:kotsql_prep", authenticatedAs("postgresql://kotsql_prep:pa%C2%ADss%C2%A0word@$address?sslmode=disable"))
    }

    /** The composed and the decomposed "é", which only a platform with normalization can tell are one. */
    @Test fun passwordsAreComposed() = scramTest { url ->
        if (testEnv("KOTSQL_TEST_NFKC") == null) return@scramTest println("No normalization on this platform; skipping")
        val address = url.substringAfter('@')
        assertEquals("scram-sha-256:kotsql_nfkc", authenticatedAs("postgresql://kotsql_nfkc:caf%C3%A9@$address?sslmode=disable"))
        assertEquals("scram-sha-256:kotsql_nfkc", authenticatedAs("postgresql://kotsql_nfkc:cafe%CC%81@$address?sslmode=disable"))
    }

    @Test fun wrongPassword() = scramTest { url ->
        val e = assertFailsWith<SqlException> { authenticatedAs(url.replace(":kotsql@", ":wrong@") + "?sslmode=disable") }
        assertEquals("28P01", e.sqlState)
        assertEquals(SqlErrorKind.AUTHENTICATION, e.kind)
    }
}
