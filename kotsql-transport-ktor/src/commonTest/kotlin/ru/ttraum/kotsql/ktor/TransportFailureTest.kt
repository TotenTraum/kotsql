package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** How the platform's default transport behaves when things go wrong. Needs `KOTSQL_TEST_URL`, with a query string. */
class TransportFailureTest {
    private val url = testEnv("KOTSQL_TEST_URL")

    private fun failureTest(block: suspend (String) -> Unit) = runTest(timeout = 120.seconds) {
        val base = url ?: return@runTest println("KOTSQL_TEST_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block(base)
        }
    }

    @Test fun nothingListening() = failureTest {
        // Port 1 is reserved and nothing listens there.
        assertEquals("08001", assertFailsWith<SqlException> { connect("postgresql://u:p@localhost:1/x?sslmode=disable") }.sqlState)
    }

    @Test fun unknownHost() = failureTest {
        val e = assertFailsWith<SqlException> { connect("postgresql://u:p@no-such-host.invalid:5432/x?sslmode=disable") }
        assertEquals("08001", e.sqlState)
    }

    @Test fun socketTimeoutClosesTheConnection() = failureTest { base ->
        val connection = connect("$base&socketTimeout=300")
        val e = assertFailsWith<SqlException> { connection.query("SELECT pg_sleep(30)").toList() }
        assertEquals("08006", e.sqlState)
        assertTrue(connection.isClosed)
        // Not a hang or a crash: it just reports being closed.
        assertEquals("08003", assertFailsWith<SqlException> { connection.query("SELECT 1").toList() }.sqlState)
    }

    @Test fun serverEndingTheSession() = failureTest { base ->
        val connection = connect(base)
        val other = connect(base)
        val pid = connection.query("SELECT pg_backend_pid()").toList().single()[0]
        other.query("SELECT pg_terminate_backend($1)", listOf(pid)).toList()
        other.close()
        delay(100)
        assertFailsWith<SqlException> { connection.query("SELECT 1").toList() }
        assertTrue(connection.isClosed)
    }

    @Test fun cancelledCoroutineLeavesNoBrokenStateBehind() = failureTest { base ->
        val connection = connect(base)
        // Cancelling inside a call isn't supported for continuing on that connection, but it must fail cleanly: as the
        // cancellation that it is, for the caller's own timeout to behave as it should.
        assertFailsWith<TimeoutCancellationException> { withTimeout(200) { connection.query("SELECT pg_sleep(30)").toList() } }
        assertTrue(connection.isClosed)
        val second = connect(base)
        assertEquals(null, withTimeoutOrNull(200) { second.query("SELECT pg_sleep(30)").toList() })
        assertTrue(second.isClosed)
        connection.close()
        val fresh = connect(base)
        assertEquals(1, fresh.query("SELECT 1").toList().single()[0])
        fresh.close()
    }

    /** The socket's own timeout and a caller's withTimeout are different things, and neither is taken for the other. */
    @Test fun aSocketTimeoutIsNotACancellation() = failureTest { base ->
        val connection = connect("$base&socketTimeout=200")
        // Longer than the socket's timeout: it's the driver that reports this, as a failed connection.
        val e = withTimeoutOrNull(10_000) { assertFailsWith<SqlException> { connection.query("SELECT pg_sleep(30)").toList() } }
        assertEquals("08006", e?.sqlState)
        // Shorter: it's the caller's.
        val other = connect("$base&socketTimeout=10000")
        assertFailsWith<TimeoutCancellationException> { withTimeout(100) { other.query("SELECT pg_sleep(30)").toList() } }
    }

    @Test fun closingTwiceIsHarmless() = failureTest { base ->
        val connection = connect(base)
        connection.close()
        connection.close()
        assertTrue(connection.isClosed)
    }

    @Test fun manyConnectionsAtOnce() = failureTest { base ->
        val results = coroutineScope {
            List(64) { i ->
                launch {
                    repeat(5) {
                        val connection = connect(base)
                        try {
                            assertEquals(i, connection.query("SELECT $i").toList().single()[0])
                        } finally {
                            connection.close()
                        }
                    }
                }
            }
        }
        assertFalse(results.any { it.isCancelled })
    }
}
