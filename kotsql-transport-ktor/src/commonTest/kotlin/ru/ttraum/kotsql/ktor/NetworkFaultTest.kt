package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.connect
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.pool.ConnectionPool
import ru.ttraum.kotsql.pool.PoolConfig
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import ru.ttraum.kotsql.transaction
import ru.ttraum.kotsql.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

internal class Fault(
    val readLimit: Long = Long.MAX_VALUE,
    val writeLimit: Long = Long.MAX_VALUE,
    /** What happens at the limit: the peer hanging up, the network failing, or nothing arriving any more. */
    val kind: Kind = Kind.HANG_UP,
    val oneByteReads: Boolean = false,
) {
    enum class Kind { HANG_UP, RESET, STALL }
}

/** The platform's real transport, up to the point where the network is made to fail. */
@OptIn(ExperimentalAtomicApi::class)
internal class FaultyFactory(private val fault: () -> Fault) : TransportFactory {
    val opened = AtomicInt(0)
    val closed = AtomicInt(0)
    var bytesRead = 0L
    var bytesWritten = 0L

    override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
        val inner = DefaultTransport.connect(host, port, connectTimeoutMillis)
        opened.incrementAndFetch()
        return Faulty(inner, fault())
    }

    private inner class Faulty(private val inner: Transport, private val fault: Fault) : Transport {
        private var read = 0L
        private var written = 0L
        private var isClosed = false
        override val isSecure get() = inner.isSecure
        override fun setReadTimeout(millis: Long) = inner.setReadTimeout(millis)

        private suspend fun fail(): Nothing = when (fault.kind) {
            Fault.Kind.RESET -> throw RuntimeException("Connection reset by the test")
            Fault.Kind.STALL -> awaitCancellation()
            Fault.Kind.HANG_UP -> throw SqlException("The server closed the connection", "08006")
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val left = fault.readLimit - read
            if (left <= 0) {
                if (fault.kind == Fault.Kind.HANG_UP) return -1
                fail()
            }
            val wanted = if (fault.oneByteReads) 1 else minOf(length.toLong(), left).toInt()
            val n = inner.read(buffer, offset, wanted)
            if (n > 0) { read += n; bytesRead += n }
            return n
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            val left = fault.writeLimit - written
            if (left < length) {
                if (left > 0) inner.write(buffer, offset, left.toInt())
                inner.flush()
                written = fault.writeLimit
                fail()
            }
            inner.write(buffer, offset, length)
            written += length
            bytesWritten += length
        }

        override suspend fun flush() = inner.flush()
        override suspend fun startTls(options: TlsOptions) = inner.startTls(options)
        override suspend fun close() {
            if (!isClosed) {
                isClosed = true
                closed.incrementAndFetch()
            }
            inner.close()
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
class NetworkFaultTest {
    private val postgresUrl = testEnv("KOTSQL_TEST_URL")
    private val mysqlUrl = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")?.let { "mysql://root:kotsql@$it/kotsql?sslmode=disable&allowPublicKeyRetrieval=true" }

    private fun faultTest(name: String, url: String?, block: suspend (String) -> Unit) = runTest(timeout = 600.seconds) {
        if (url == null) return@runTest println("No server for $name; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            block(url)
        }
    }

    private suspend fun plain(url: String, block: suspend SqlConnection.() -> Unit) {
        val connection = connect(url)
        try { connection.block() } finally { connection.close() }
    }

    /** What must hold whenever the network fails, at whichever point of this exchange. */
    private suspend fun SqlConnection.exchange(table: String, key: Int) {
        transaction {
            update("INSERT INTO $table (k, v) VALUES ($key, 'first')")
            update("INSERT INTO $table (k, v) VALUES ($key, 'second')")
        }
        assertEquals(2L, (query("SELECT count(*) FROM $table WHERE k = $key").toList().single()[0] as Number).toLong())
    }

    /** Fails the network at every position of the exchange, in turn. */
    private suspend fun sweep(url: String, table: String, kind: Fault.Kind, reading: Boolean) {
        plain(url) {
            update("DROP TABLE IF EXISTS $table")
            update("CREATE TABLE $table (k INT, v VARCHAR(16))")
        }
        val measure = FaultyFactory { Fault() }
        connect(url, measure).let { connection -> try { connection.exchange(table, 0) } finally { connection.close() } }
        val total = if (reading) measure.bytesRead else measure.bytesWritten
        assertTrue(total > 0)

        // Each stall takes as long as the driver is willing to wait for its attempt to stop the statement.
        val step = maxOf(1L, total / if (kind == Fault.Kind.STALL) 25 else 120)
        var key = 1
        var position = 0L
        while (position < total) {
            val at = position
            val factory = FaultyFactory { if (reading) Fault(readLimit = at, kind = kind) else Fault(writeLimit = at, kind = kind) }
            var connection: SqlConnection? = null
            val failure = try {
                withTimeout(if (kind == Fault.Kind.STALL) 300 else 20_000) {
                    connection = connect(url, factory)
                    connection?.exchange(table, key)
                }
                null
            } catch (e: TimeoutCancellationException) {
                if (kind != Fault.Kind.STALL) fail("Hung with the network failing at byte $at")
                e
            } catch (e: SqlException) {
                e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail("At byte $at: expected a SqlException, got $e\n${e.stackTraceToString()}")
            }
            if (at == 0L) assertTrue(failure != null, "The network failing from the start went unnoticed")
            if (failure != null) connection?.let { assertTrue(it.isClosed, "Still open at byte $at after: $failure") }
            connection?.close()
            assertEquals(factory.opened.load(), factory.closed.load(), "A transport left open at byte $at")
            plain(url) {
                val count = (query("SELECT count(*) FROM $table WHERE k = $key").toList().single()[0] as Number).toLong()
                assertTrue(count == 0L || count == 2L, "Half a transaction at byte $at: $count rows")
            }
            key++
            position += step
        }
        plain(url) { update("DROP TABLE $table") }
    }

    @Test fun postgresReadsHangUp() = faultTest("postgres", postgresUrl) { sweep(it, "fault_a", Fault.Kind.HANG_UP, reading = true) }
    @Test fun postgresReadsReset() = faultTest("postgres", postgresUrl) { sweep(it, "fault_b", Fault.Kind.RESET, reading = true) }
    @Test fun postgresReadsStall() = faultTest("postgres", postgresUrl) { sweep(it, "fault_c", Fault.Kind.STALL, reading = true) }
    @Test fun postgresWritesReset() = faultTest("postgres", postgresUrl) { sweep(it, "fault_d", Fault.Kind.RESET, reading = false) }
    @Test fun mysqlReadsHangUp() = faultTest("mysql", mysqlUrl) { sweep(it, "fault_a", Fault.Kind.HANG_UP, reading = true) }
    @Test fun mysqlReadsReset() = faultTest("mysql", mysqlUrl) { sweep(it, "fault_b", Fault.Kind.RESET, reading = true) }
    @Test fun mysqlReadsStall() = faultTest("mysql", mysqlUrl) { sweep(it, "fault_c", Fault.Kind.STALL, reading = true) }
    @Test fun mysqlWritesReset() = faultTest("mysql", mysqlUrl) { sweep(it, "fault_d", Fault.Kind.RESET, reading = false) }

    /** Messages split at every byte take the slow paths of everything that reads them. */
    private suspend fun oneByteAtATime(url: String, table: String) {
        plain(url) {
            update("DROP TABLE IF EXISTS $table")
            update("CREATE TABLE $table (k INT, v VARCHAR(16))")
        }
        val factory = FaultyFactory { Fault(oneByteReads = true) }
        val connection = connect(url, factory)
        try {
            connection.exchange(table, 1)
            assertEquals(listOf("first", "second"), connection.query("SELECT v FROM $table WHERE k = 1 ORDER BY v").toList().map { it[0] })
        } finally {
            connection.close()
        }
        plain(url) { update("DROP TABLE $table") }
    }

    @Test fun postgresOneByteAtATime() = faultTest("postgres", postgresUrl) { oneByteAtATime(it, "fault_e") }
    @Test fun mysqlOneByteAtATime() = faultTest("mysql", mysqlUrl) { oneByteAtATime(it, "fault_e") }

    /** A pool on a network that keeps failing hands out what works, keeps its counts straight, and recovers once it's over. */
    private suspend fun poolOnFailingNetwork(url: String) {
        var failing = true
        val random = Random(20261106)
        val factory = FaultyFactory {
            when {
                !failing -> Fault()
                else -> Fault(readLimit = random.nextLong(200, 6_000), kind = if (random.nextBoolean()) Fault.Kind.HANG_UP else Fault.Kind.RESET)
            }
        }
        val pool = ConnectionPool({ connect(url, factory) }, PoolConfig(maxSize = 4, acquireTimeout = 5.seconds, validateAfterIdle = 0.seconds))
        val succeeded = AtomicInt(0)
        val failed = AtomicInt(0)
        try {
            coroutineScope {
                repeat(8) {
                    launch {
                        repeat(100) {
                            try {
                                assertEquals(1, (pool.query("SELECT 1").toList().single()[0] as Number).toInt())
                                succeeded.incrementAndFetch()
                            } catch (e: SqlException) {
                                failed.incrementAndFetch()
                            }
                        }
                    }
                }
            }
            println("On the failing network: ${succeeded.load()} succeeded, ${failed.load()} failed")
            assertTrue(failed.load() > 0, "The network never failed, so this shows nothing")
            assertTrue(succeeded.load() > 0)
            failing = false
            // A connection from before may still have some way to go until it fails, and each failure gets rid of one.
            var inARow = 0
            var attempts = 0
            while (inARow < 50) {
                if (++attempts > 5_000) fail("Not recovered after $attempts attempts")
                inARow = if (runCatching { pool.query("SELECT 1").toList() }.isSuccess) inARow + 1 else 0
            }
            val stats = pool.stats()
            assertEquals(0, stats.inUse)
            assertEquals(0, stats.waiting)
        } finally {
            pool.close()
        }
        assertEquals(factory.opened.load(), factory.closed.load(), "Transports left open")
    }

    @Test fun postgresPoolOnFailingNetwork() = faultTest("postgres", postgresUrl) { poolOnFailingNetwork(it) }
    @Test fun mysqlPoolOnFailingNetwork() = faultTest("mysql", mysqlUrl) { poolOnFailingNetwork(it) }

    private suspend fun givingUpStopsTheStatement(url: String, sleep: String, running: String) {
        val connection = connect(url)
        assertFailsWith<TimeoutCancellationException> { withTimeout(200) { connection.query(sleep).toList() } }
        assertTrue(connection.isClosed)
        repeat(50) {
            var left = -1L
            plain(url) { left = (query(running).toList().single()[0] as Number).toLong() }
            if (left == 0L) return
            delay(100)
        }
        fail("The statement goes on after its caller has given up")
    }

    @Test fun postgresGivingUpStopsTheStatement() = faultTest("postgres", postgresUrl) {
        givingUpStopsTheStatement(it, "SELECT pg_sleep(30.125)", "SELECT count(*) FROM pg_stat_activity WHERE query = 'SELECT pg_sleep(30.125)' AND state = 'active'")
    }

    @Test fun mysqlGivingUpStopsTheStatement() = faultTest("mysql", mysqlUrl) {
        givingUpStopsTheStatement(it, "SELECT SLEEP(30.125)", "SELECT count(*) FROM information_schema.processlist WHERE info = 'SELECT SLEEP(30.125)' AND command <> 'Sleep'")
    }

    /** The server sees the connection end too, rather than holding it for a client that has gone. */
    @Test fun nothingLeftOnTheServer() = faultTest("postgres", postgresUrl) { url ->
        val application = "kotsql_fault_${Random.nextInt(1_000_000)}"
        val tagged = "$url&applicationName=$application"
        repeat(20) { i ->
            val factory = FaultyFactory { Fault(readLimit = 500L + i * 40, kind = Fault.Kind.RESET) }
            runCatching {
                val connection = connect(tagged, factory)
                try { repeat(20) { connection.query("SELECT 1").toList() } } finally { connection.close() }
            }
            assertEquals(factory.opened.load(), factory.closed.load())
        }
        var left = -1L
        repeat(50) {
            plain(url) {
                left = (query("SELECT count(*) FROM pg_stat_activity WHERE application_name = '$application'").toList().single()[0] as Number).toLong()
            }
            if (left == 0L) return@faultTest
            delay(100)
        }
        fail("$left connections are still there")
    }
}
