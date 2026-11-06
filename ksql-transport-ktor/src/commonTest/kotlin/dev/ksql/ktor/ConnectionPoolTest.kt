package dev.ksql.ktor

import dev.ksql.SqlConnection
import dev.ksql.SqlErrorKind
import dev.ksql.SqlException
import dev.ksql.SqlResult
import dev.ksql.TransactionState
import dev.ksql.mysql.MysqlDriver
import dev.ksql.pool.ConnectionPool
import dev.ksql.pool.PoolConfig
import dev.ksql.postgres.PostgresDriver
import dev.ksql.query
import dev.ksql.spi.Drivers
import dev.ksql.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class ConnectionPoolTest {
    private val postgresUrl = testEnv("KSQL_TEST_URL")
    private val mysqlUrl = testEnv("KSQL_TEST_MYSQL_HOST_PORT")?.let { "mysql://root:ksql@$it/ksql?sslmode=disable&allowPublicKeyRetrieval=true" }

    private fun poolTest(config: PoolConfig = PoolConfig(maxSize = 2, acquireTimeout = 5.seconds), block: suspend (ConnectionPool) -> Unit) = runTest(timeout = 120.seconds) {
        val url = postgresUrl ?: return@runTest println("KSQL_TEST_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            val pool = connectionPool(url, config)
            try { block(pool) } finally { pool.close() }
        }
    }

    private suspend fun SqlConnection.backend(): Any? = query("SELECT pg_backend_pid()").toList().single()[0]

    @Test fun reusesTheSameConnection() = poolTest { pool ->
        val first = pool.withConnection { backend() }
        repeat(10) { assertEquals(first, pool.withConnection { backend() }) }
        val stats = pool.stats()
        assertEquals(1, stats.created); assertEquals(1, stats.idle); assertEquals(0, stats.inUse)
    }

    @Test fun neverExceedsMaxSizeAndQueues() = poolTest(PoolConfig(maxSize = 4, acquireTimeout = 60.seconds)) { pool ->
        val peak = AtomicInt(0)
        val current = AtomicInt(0)
        coroutineScope {
            List(200) {
                async {
                    pool.withConnection {
                        val now = current.addAndFetch(1)
                        while (true) {
                            val seen = peak.load()
                            if (now <= seen || peak.compareAndSet(seen, now)) break
                        }
                        query("SELECT 1").toList()
                        current.addAndFetch(-1)
                    }
                }
            }.awaitAll()
        }
        assertTrue(peak.load() <= 4, "peak ${peak.load()}")
        assertTrue(pool.stats().created <= 4, "created ${pool.stats().created}")
        // And the server agrees.
        val open = pool.withConnection { query("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'ksql'").toList().single()[0] as Long }
        assertTrue(open >= 1)
    }

    @Test fun waitersAreServedInOrder() = poolTest(PoolConfig(maxSize = 1, acquireTimeout = 30.seconds)) { pool ->
        val holder = pool.acquire()
        val order = ArrayList<Int>()
        coroutineScope {
            val waiters = List(5) { i ->
                launch {
                    pool.withConnection { order += i }
                }.also { delay(30) }
            }
            holder.close()
            waiters.forEach { it.join() }
        }
        assertEquals(listOf(0, 1, 2, 3, 4), order)
    }

    @Test fun acquireTimesOutWithAnExplanation() = poolTest(PoolConfig(maxSize = 1, acquireTimeout = 100.milliseconds)) { pool ->
        val holder = pool.acquire()
        val e = assertFailsWith<SqlException> { pool.acquire() }
        assertEquals("HYT00", e.sqlState); assertEquals(SqlErrorKind.TIMEOUT, e.kind); assertTrue("1 of 1 in use" in e.message!!, e.message)
        holder.close()
        // The timed out waiter hasn't lost anyone their place.
        pool.withConnection { assertEquals(1, query("SELECT 1").toList().single()[0]) }
    }

    @Test fun rollsBackAnOpenTransactionOnReturn() = poolTest(PoolConfig(maxSize = 1)) { pool ->
        pool.update("DROP TABLE IF EXISTS pool_tx")
        pool.update("CREATE TABLE pool_tx(a int)")
        pool.withConnection {
            update("BEGIN")
            update("INSERT INTO pool_tx VALUES (1)")
            assertEquals(TransactionState.ACTIVE, transactionState)
        }
        pool.withConnection {
            assertEquals(TransactionState.IDLE, transactionState)
            assertEquals(0L, query("SELECT count(*) FROM pool_tx").toList().single()[0])
        }
        // A failed transaction, in which only a rollback works.
        val backend = pool.withConnection {
            val id = backend()
            update("BEGIN")
            assertFailsWith<SqlException> { update("SELEC") }
            assertEquals(TransactionState.FAILED, transactionState)
            id
        }
        pool.withConnection { assertEquals(backend, backend()) }
        pool.update("DROP TABLE pool_tx")
    }

    @Test fun transactionHelperCommitsAndRollsBack() = poolTest { pool ->
        pool.update("DROP TABLE IF EXISTS pool_h")
        pool.update("CREATE TABLE pool_h(a int)")
        pool.transaction { update("INSERT INTO pool_h VALUES (1)") }
        assertFailsWith<IllegalStateException> { pool.transaction { update("INSERT INTO pool_h VALUES (2)"); error("boom") } }
        assertEquals(1L, pool.query("SELECT count(*) FROM pool_h").toList().single()[0])
        pool.update("DROP TABLE pool_h")
    }

    @Test fun unreadResultsDoNotReachTheNextHolder() = poolTest(PoolConfig(maxSize = 1)) { pool ->
        pool.withConnection {
            val cursor = execute("SELECT generate_series(1, 5)")
            assertEquals(1, (cursor.nextResult() as SqlResult.Rows).next()!![0])
            // A paged one too, with a portal open on the server.
            execute("SELECT generate_series(1, 100000)", options = dev.ksql.ExecuteOptions(fetchSize = 10)).nextResult()
        }
        pool.withConnection { assertEquals(7, query("SELECT 7").toList().single()[0]) }
        assertEquals(1, pool.stats().created)
    }

    @Test fun streamingQueryHoldsAndReturnsItsConnection() = poolTest(PoolConfig(maxSize = 1)) { pool ->
        assertEquals((1..5).toList(), pool.query("SELECT generate_series(1, 5)").toList().map { it[0] })
        // Stopping early, in a page of a big result.
        assertEquals(listOf(1, 2), pool.query("SELECT generate_series(1, 100000)", fetchSize = 10).take(2).toList().map { it[0] })
        assertEquals(0, pool.stats().inUse); assertEquals(1, pool.stats().created)
    }

    @Test fun aReturnedConnectionIsUselessAndCannotDisturbTheNextHolder() = poolTest(PoolConfig(maxSize = 1)) { pool ->
        val stale = pool.acquire()
        stale.close()
        stale.close() // harmless
        assertTrue(stale.isClosed)
        assertEquals("08003", assertFailsWith<SqlException> { stale.query("SELECT 1").toList() }.sqlState)
        assertEquals(1, pool.stats().idle)
        pool.withConnection {
            coroutineScope {
                launch { delay(100); stale.cancel() } // must not interrupt this query
                assertEquals(1L, query("SELECT count(*) FROM pg_sleep(0.3)").toList().single()[0])
            }
        }
    }

    @Test fun replacesAConnectionThatDied() = poolTest(PoolConfig(maxSize = 1, validateAfterIdle = 0.milliseconds)) { pool ->
        val first = pool.withConnection { backend() }
        connect(postgresUrl!!).let { killer ->
            killer.query("SELECT pg_terminate_backend($1)", listOf(first)).toList()
            killer.close()
        }
        delay(100)
        // Found out by validation, and the caller never knows.
        assertNotEquals(first, pool.withConnection { backend() })
        assertEquals(1, pool.stats().discarded)
    }

    @Test fun dropsAConnectionThatBrokeInUse() = poolTest(PoolConfig(maxSize = 1)) { pool ->
        val first = pool.withConnection { backend() }
        assertFailsWith<SqlException> {
            pool.withConnection {
                query("SELECT pg_terminate_backend(pg_backend_pid())").toList()
                query("SELECT 1").toList()
            }
        }
        assertEquals(0, pool.stats().idle)
        assertNotEquals(first, pool.withConnection { backend() })
    }

    @Test fun aCancelledCallerGivesTheConnectionBack() = poolTest(PoolConfig(maxSize = 1, acquireTimeout = 5.seconds)) { pool ->
        // Cancelled in the middle of a statement, which loses that connection, but not its place in the pool.
        assertFailsWith<CancellationException> { withTimeout(200) { pool.withConnection { query("SELECT pg_sleep(30)").toList() } } }
        assertEquals(0, pool.stats().inUse)
        pool.withConnection { assertEquals(1, query("SELECT 1").toList().single()[0]) }
        // Cancelled while waiting in the queue.
        val holder = pool.acquire()
        coroutineScope {
            val waiter = launch { pool.withConnection { error("never") } }
            delay(50)
            assertEquals(1, pool.stats().waiting)
            waiter.cancelAndJoin()
            assertEquals(0, pool.stats().waiting)
        }
        holder.close()
        pool.withConnection { assertEquals(1, query("SELECT 1").toList().single()[0]) }
    }

    @Test fun aFailedConnectLeavesThePlaceFree() = runTest(timeout = 60.seconds) {
        val url = postgresUrl ?: return@runTest
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            val attempts = AtomicInt(0)
            val pool = ConnectionPool({
                if (attempts.addAndFetch(1) == 1) throw SqlException("server down", "08001")
                connect(url)
            }, PoolConfig(maxSize = 1, acquireTimeout = 5.seconds))
            assertEquals("08001", assertFailsWith<SqlException> { pool.acquire() }.sqlState)
            pool.withConnection { assertEquals(1, query("SELECT 1").toList().single()[0]) }
            pool.close()
        }
    }

    @Test fun onConnectSetsUpEachConnection() = poolTest(PoolConfig(maxSize = 2, onConnect = { update("SET application_name = 'from_on_connect'") })) { pool ->
        pool.withConnection { assertEquals("from_on_connect", query("SHOW application_name").toList().single()[0]) }
    }

    @Test fun replacesConnectionsPastTheirLifetime() = poolTest(PoolConfig(maxSize = 1, maxLifetime = 100.milliseconds)) { pool ->
        val first = pool.withConnection { backend() }
        delay(150)
        assertNotEquals(first, pool.withConnection { backend() })
    }

    @Test fun maintenanceKeepsMinSizeAndClosesIdleOnes() = poolTest(
        PoolConfig(maxSize = 3, minSize = 1, maxIdleTime = 100.milliseconds, maintenanceInterval = 50.milliseconds),
    ) { pool ->
        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val holders = List(3) { launch { pool.withConnection { gate.await() } } }
            delay(300)
            assertEquals(3, pool.stats().inUse)
            gate.complete(Unit)
            holders.forEach { it.join() }
        }
        delay(400)
        val stats = pool.stats()
        assertEquals(1, stats.idle, stats.toString()); assertEquals(2, stats.discarded, stats.toString())
    }

    @Test fun minSizeIsReadyInTheBackground() = poolTest(PoolConfig(maxSize = 3, minSize = 2, maintenanceInterval = 50.milliseconds)) { pool ->
        pool.withConnection { query("SELECT 1").toList() }
        delay(300)
        assertEquals(2, pool.stats().idle); assertEquals(2, pool.stats().created)
    }

    @Test fun closedPoolRefusesAndClosesWhatComesBack() = poolTest(PoolConfig(maxSize = 2)) { pool ->
        val held = pool.acquire()
        pool.withConnection { query("SELECT 1").toList() }
        pool.close()
        assertEquals("08003", assertFailsWith<SqlException> { pool.acquire() }.sqlState)
        assertEquals(0, pool.stats().idle)
        // Work in progress goes on, and only then is the connection closed.
        assertEquals(1, held.query("SELECT 1").toList().single()[0])
        held.close()
        assertEquals(0, pool.stats().idle); assertEquals(0, pool.stats().inUse)
        pool.close() // harmless
    }

    @Test fun rejectsBadConfiguration() {
        assertFailsWith<IllegalArgumentException> { PoolConfig(maxSize = 0) }
        assertFailsWith<IllegalArgumentException> { PoolConfig(maxSize = 2, minSize = 3) }
    }

    @Test fun worksWithMysqlToo() = runTest(timeout = 60.seconds) {
        val url = mysqlUrl ?: return@runTest println("KSQL_TEST_MYSQL_HOST_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            val pool = connectionPool(url, PoolConfig(maxSize = 3, acquireTimeout = 30.seconds))
            try {
                pool.update("DROP TABLE IF EXISTS pool_my")
                pool.update("CREATE TABLE pool_my(a INT) ENGINE=InnoDB")
                pool.withConnection {
                    update("START TRANSACTION")
                    update("INSERT INTO pool_my VALUES (1)")
                    execute("SELECT * FROM pool_my").nextResult()
                }
                assertEquals(0L, pool.query("SELECT COUNT(*) FROM pool_my").toList().single()[0])
                coroutineScope {
                    List(60) { i -> async { pool.transaction { update("INSERT INTO pool_my VALUES (?)", listOf(i)) } } }.awaitAll()
                }
                assertEquals(60L, pool.query("SELECT COUNT(*) FROM pool_my").toList().single()[0])
                assertTrue(pool.stats().created <= 3)
                assertFalse(pool.withConnection { isClosed })
                pool.update("DROP TABLE pool_my")
            } finally {
                pool.close()
            }
        }
    }
}
