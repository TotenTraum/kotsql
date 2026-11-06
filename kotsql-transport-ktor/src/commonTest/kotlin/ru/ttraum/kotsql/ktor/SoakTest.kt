package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.pool.ConnectionPool
import ru.ttraum.kotsql.pool.PoolConfig
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Memory in use: the heap after a collection on the JVM, the peak resident size on Native, where OpenSSL's memory counts too. */
internal expect fun memoryInUse(): Long

/**
 * Hours of ordinary traffic and ordinary trouble, to find what only shows over time: memory, connections, a pool's counts.
 * Runs only with `KOTSQL_SOAK_SECONDS` set: see `tools/run-soak.sh`.
 */
@OptIn(ExperimentalAtomicApi::class)
class SoakTest {
    private val seconds = testEnv("KOTSQL_SOAK_SECONDS")?.toInt()
    private val postgresUrl = testEnv("KOTSQL_TEST_URL")
    private val mysqlUrl = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")?.let { "mysql://root:kotsql@$it/kotsql?sslmode=disable&allowPublicKeyRetrieval=true" }

    private fun soak(url: String?, sleep: String, count: String) = runTest(timeout = ((seconds ?: 0) + 120).seconds) {
        val duration = (seconds ?: return@runTest println("KOTSQL_SOAK_SECONDS is not set; skipping")).seconds
        if (url == null) return@runTest println("No server; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            val table = "soak_${Random.nextInt(1_000_000)}"
            val pool = ConnectionPool(
                { connect(url) },
                // Connections come and go throughout, as that's where a lot can leak.
                PoolConfig(maxSize = 8, acquireTimeout = 30.seconds, maxLifetime = 2.seconds, maxIdleTime = 1.seconds, maintenanceInterval = 500.milliseconds),
            )
            val operations = AtomicLong(0)
            val failures = AtomicLong(0)
            val started = TimeSource.Monotonic.markNow()
            val samples = ArrayList<Long>()
            try {
                pool.update("CREATE TABLE $table (k INT, v VARCHAR(255))")
                coroutineScope {
                    val workers = List(16) { worker ->
                        launch {
                            val random = Random(worker)
                            while (started.elapsedNow() < duration) {
                                try {
                                    when (random.nextInt(100)) {
                                        in 0..49 -> assertEquals(1, (pool.query("SELECT 1").first()[0] as Number).toInt())
                                        in 50..69 -> pool.transaction {
                                            update("INSERT INTO $table (k, v) VALUES (${random.nextInt(1000)}, 'value')")
                                            update("DELETE FROM $table WHERE k = ${random.nextInt(1000)}")
                                        }
                                        in 70..84 -> pool.query("SELECT k, v FROM $table WHERE k < ?".replace("?", "${random.nextInt(1000)}")).toList()
                                        in 85..92 -> runCatching { pool.update("SELECT * FROM no_such_table_here") }
                                        // A big value, which takes the paths that small ones don't.
                                        in 93..96 -> assertEquals(1_000_000, (pool.query("SELECT repeat('x', 1000000)").first()[0] as String).length)
                                        // A caller giving up in the middle of a call: the connection has to go, and only it.
                                        else -> try {
                                            withTimeout(5) { pool.query(sleep).toList() }
                                        } catch (e: TimeoutCancellationException) {
                                            // As intended.
                                        }
                                    }
                                    operations.incrementAndFetch()
                                } catch (e: SqlException) {
                                    if (e.kind == SqlErrorKind.ROLLBACK) continue // deadlocks are for the workload to answer for
                                    failures.incrementAndFetch()
                                    println("Failed: $e")
                                }
                            }
                        }
                    }
                    launch {
                        while (workers.any { it.isActive }) {
                            delay(duration / 10)
                            samples += memoryInUse()
                        }
                    }
                }
                println("$url: ${operations.load()} operations, ${failures.load()} failures, memory ${samples.map { it / (1024 * 1024) }} MB, ${pool.stats()}")
                assertEquals(0, failures.load(), "Nothing here should fail")
                val stats = pool.stats()
                assertEquals(0, stats.inUse)
                assertEquals(0, stats.waiting)
                // Having warmed up by a third of the way, memory must not keep growing from there.
                val settled = samples[samples.size / 3]
                assertTrue(samples.last() <= settled + settled / 3 + 32 * 1024 * 1024, "Memory keeps growing: $samples")
                pool.update("DROP TABLE $table")
            } finally {
                pool.close()
            }
            // Only the connections of others remain on the server.
            val connection = connect(url)
            try {
                var left = -1L
                repeat(50) {
                    left = (connection.query(count).toList().single()[0] as Number).toLong()
                    if (left <= 1) return@withContext
                    delay(200)
                }
                kotlin.test.fail("$left connections are still on the server")
            } finally {
                connection.close()
            }
        }
    }

    @Test fun postgres() = soak(
        postgresUrl, "SELECT pg_sleep(10)",
        "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'kotsql' AND state IS NOT NULL",
    )

    @Test fun mysql() = soak(
        mysqlUrl, "SELECT SLEEP(10)",
        "SELECT count(*) FROM information_schema.processlist WHERE user = 'root' AND command <> 'Sleep' OR id = CONNECTION_ID()",
    )
}
