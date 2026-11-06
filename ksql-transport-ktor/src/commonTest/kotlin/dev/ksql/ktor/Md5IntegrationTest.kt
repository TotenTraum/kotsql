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
 * Needs `KSQL_TEST_MD5_ADDRESS` (`host:port`) of a server with the roles and the `pg_hba.conf` entry that
 * `tools/run-client-tests.sh` creates. `system_user` proves that the md5 method has really run rather than been skipped.
 */
class Md5IntegrationTest {
    private val address = testEnv("KSQL_TEST_MD5_ADDRESS")

    private fun md5Test(block: suspend (String) -> Unit) = runTest(timeout = 60.seconds) {
        val hostPort = address ?: return@runTest println("KSQL_TEST_MD5_ADDRESS is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block(hostPort)
        }
    }

    private suspend fun authenticatedAs(url: String): Any? {
        val connection = connect(url)
        try { return connection.query("SELECT system_user").toList().single()[0] } finally { connection.close() }
    }

    @Test fun rightPassword() = md5Test { address ->
        assertEquals("md5:ksql_md5", authenticatedAs("postgresql://ksql_md5:ksql@$address/ksql?sslmode=disable"))
        assertEquals("md5:ksql_md5", authenticatedAs("postgresql://ksql_md5:ksql@$address/ksql?sslmode=require"))
    }

    /** The hash covers the UTF-8 bytes of both the user name and the password. "ksql_мд5" and "пароль", percent-encoded. */
    @Test fun nonAsciiCredentials() = md5Test { address ->
        val user = "ksql_%D0%BC%D0%B45"
        val password = "%D0%BF%D0%B0%D1%80%D0%BE%D0%BB%D1%8C"
        assertEquals("md5:ksql_мд5", authenticatedAs("postgresql://$user:$password@$address/ksql?sslmode=disable"))
    }

    @Test fun wrongPassword() = md5Test { address ->
        val e = assertFailsWith<SqlException> { authenticatedAs("postgresql://ksql_md5:wrong@$address/ksql?sslmode=disable") }
        assertEquals("28P01", e.sqlState)
        assertEquals(SqlErrorKind.AUTHENTICATION, e.kind)
    }

    @Test fun missingPassword() = md5Test { address ->
        val e = assertFailsWith<SqlException> { authenticatedAs("postgresql://ksql_md5@$address/ksql?sslmode=disable") }
        assertEquals("28P01", e.sqlState)
        assertEquals(SqlErrorKind.AUTHENTICATION, e.kind)
    }
}
