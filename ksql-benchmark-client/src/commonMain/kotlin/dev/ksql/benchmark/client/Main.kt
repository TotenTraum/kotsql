package dev.ksql.benchmark.client

import dev.ksql.BatchItem
import dev.ksql.SqlConnection
import dev.ksql.SqlDecimal
import dev.ksql.connect
import dev.ksql.pool.ConnectionPool
import dev.ksql.pool.PoolConfig
import dev.ksql.postgres.PostgresDriver
import dev.ksql.query
import dev.ksql.spi.Drivers
import dev.ksql.spi.TransportFactory
import dev.ksql.transaction
import dev.ksql.update
import kotlinx.coroutines.Dispatchers
import dev.ksql.postgres.postgres
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The transports available on this platform, by name. */
expect fun transports(): Map<String, TransportFactory>
expect val platformName: String
expect fun environment(name: String): String?
expect fun readTextFile(path: String): String?
expect fun runMain(block: suspend () -> Unit)

private const val ROWS = 100_000
private const val SELECT_ONE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id = $1"
private const val SELECT_RANGE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id > $1 ORDER BY id LIMIT 1000"
private const val SELECT_ALL = "SELECT id, name, amount, created, active, tag FROM bench_rows"
private const val INSERT = "INSERT INTO bench_insert (id, name, amount, created) VALUES ($1, $2, $3, $4)"
private const val UPDATE = "UPDATE bench_counter SET n = n + 1 WHERE id = $1"

/** Keeps the decoded values alive, so that decoding can't be optimized away. */
private var sink = 0

private suspend fun SqlConnection.selectAndDecode(sql: String, params: List<Any?>, fetchSize: Int = 0) {
    query(sql, params, fetchSize).collect { row ->
        for (i in 0..5) sink += row[i].hashCode()
    }
}

private fun randomId(bound: Int) = Random.nextLong(1, bound.toLong())

private val INSERTED_AMOUNT = SqlDecimal("10")
private val INSERTED_AT = LocalDateTime(2024, 1, 1, 12, 0)

private val RAW_COPY_DATA = (1..1000).joinToString("") { "$it\tinserted\t10\t2024-01-01 12:00:00\n" }.encodeToByteArray()

private class Scenario(val name: String, val connections: Int, val op: suspend SqlConnection.(nextId: () -> Long) -> Unit)

private val SCENARIOS = listOf(
    Scenario("selectOne", 1) { selectAndDecode(SELECT_ONE, listOf(randomId(ROWS))) },
    Scenario("selectOne8", 8) { selectAndDecode(SELECT_ONE, listOf(randomId(ROWS))) },
    Scenario("selectOne32", 32) { selectAndDecode(SELECT_ONE, listOf(randomId(ROWS))) },
    Scenario("select1000Rows", 1) { selectAndDecode(SELECT_RANGE, listOf(randomId(ROWS - 1000))) },
    Scenario("streamAllRows", 1) { selectAndDecode(SELECT_ALL, emptyList(), fetchSize = 1000) },
    Scenario("insertOne", 1) { nextId -> update(INSERT, listOf(nextId(), "inserted", INSERTED_AMOUNT, INSERTED_AT)) },
    Scenario("insertBatch1000", 1) { nextId ->
        executeBatch(List(1000) { BatchItem(INSERT, listOf(nextId(), "inserted", INSERTED_AMOUNT, INSERTED_AT)) })
    },
    Scenario("copyIn1000", 1) { nextId ->
        postgres.copyInRows(
            "bench_insert",
            listOf("id", "name", "amount", "created"),
            flow { repeat(1000) { emit(listOf(nextId(), "inserted", INSERTED_AMOUNT, INSERTED_AT)) } },
        )
    },
    // COPY with the data already encoded, which shows what the encoding of rows costs compared with the server's part.
    Scenario("copyInRaw1000", 1) {
        postgres.copyIn("COPY bench_insert (id, name, amount, created) FROM STDIN", flow { emit(RAW_COPY_DATA) })
    },
    Scenario("transaction", 1) {
        transaction {
            selectAndDecode(SELECT_ONE, listOf(randomId(ROWS)))
            update(UPDATE, listOf(randomId(64).toInt()))
        }
    },
)

private suspend fun seed(c: SqlConnection) {
    c.update(
        """CREATE TABLE IF NOT EXISTS bench_rows (
             id bigint PRIMARY KEY, name text NOT NULL, amount numeric(12, 2) NOT NULL, created timestamp NOT NULL,
             active boolean NOT NULL, tag uuid NOT NULL)""",
    )
    c.update(
        """INSERT INTO bench_rows
           SELECT i, 'name-' || i, i * 1.25, TIMESTAMP '2024-01-01' + i * INTERVAL '1 minute', i % 2 = 0, md5(i::text)::uuid
           FROM generate_series(1, $ROWS) i ON CONFLICT DO NOTHING""",
    )
    c.update("ANALYZE bench_rows")
    c.update("CREATE TABLE IF NOT EXISTS bench_counter (id int PRIMARY KEY, n bigint NOT NULL)")
    c.update("INSERT INTO bench_counter SELECT i, 0 FROM generate_series(1, 64) i ON CONFLICT DO NOTHING")
}

/** Runs [op] in a loop on each connection until the time is up, and returns operations per second. */
private suspend fun measure(connections: List<SqlConnection>, seconds: Double, op: suspend SqlConnection.(() -> Long) -> Unit): Double {
    val counts = LongArray(connections.size)
    val start = TimeSource.Monotonic.markNow()
    val results = kotlinx.coroutines.coroutineScope {
        connections.mapIndexed { index, connection ->
            launch {
                var id = Random.nextLong(1, 1L shl 40)
                val nextId = { ++id }
                var done = 0L
                while (start.elapsedNow().inWholeMilliseconds < seconds * 1000) {
                    connection.op(nextId)
                    done++
                }
                counts[index] = done
            }
        }
    }
    results.forEach { it.join() }
    return counts.sum() / start.elapsedNow().inWholeMicroseconds.toDouble() * 1_000_000
}

/**
 * Arguments: transport, sslmode, warm-up iterations, measured iterations, seconds per iteration, and optionally the
 * names of the scenarios to run. Prints `RESULT` lines that tools/benchmark-client-report.py reads.
 */
fun main(args: Array<String>) = runMain {
    val started = TimeSource.Monotonic.markNow()
    val transportName = args[0]
    val sslmode = args[1]
    val warmup = args[2].toInt()
    val iterations = args[3].toInt()
    val seconds = args[4].toDouble()
    val only = args.drop(5).toSet()
    val transport = transports()[transportName] ?: error("No transport '$transportName' on $platformName")
    if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
    val url = "postgresql://ksql:ksql@${environment("BENCH_HOST") ?: "localhost"}:${environment("BENCH_PORT") ?: "55432"}/ksql?sslmode=$sslmode"
    val label = "$platformName $transportName $sslmode"

    val dispatcher = if (environment("BENCH_DISPATCHER") == "unconfined") Dispatchers.Unconfined else Dispatchers.Default
    withContext(dispatcher) {
        // The first query of a fresh process, which is where the JVM pays for not having warmed up.
        val first = connect(url, transport)
        first.selectAndDecode("SELECT 1, 2, 3, 4, 5, 6", emptyList())
        println("RESULT $label firstQueryMillis ${started.elapsedNow().inWholeMilliseconds}")
        seed(first)
        first.close()

        val coldConnect = TimeSource.Monotonic.markNow()
        var connects = 0
        while (coldConnect.elapsedNow().inWholeMilliseconds < seconds * 1000 * (warmup + iterations)) {
            connect(url, transport).close()
            connects++
        }
        println("RESULT $label connect ${connects / coldConnect.elapsedNow().inWholeMicroseconds.toDouble() * 1_000_000}")

        // What a pool saves: a new connection, with its authentication, for every query.
        for ((name, callers) in listOf("connectPerQuery" to 1, "connectPerQuery8" to 8)) {
            if (only.isNotEmpty() && name !in only) continue
            suspend fun run(duration: Double): Double {
                val start = TimeSource.Monotonic.markNow()
                val counts = LongArray(callers)
                coroutineScope {
                    repeat(callers) { i ->
                        launch {
                            var done = 0L
                            while (start.elapsedNow().inWholeMilliseconds < duration * 1000) {
                                val connection = connect(url, transport)
                                try {
                                    connection.selectAndDecode(SELECT_ONE, listOf(randomId(ROWS)))
                                } finally {
                                    connection.close()
                                }
                                done++
                            }
                            counts[i] = done
                        }
                    }
                }
                return counts.sum() / start.elapsedNow().inWholeMicroseconds.toDouble() * 1_000_000
            }
            repeat(warmup) { run(seconds) }
            println("RESULT $label $name ${List(iterations) { run(seconds) }.joinToString(" ")}")
        }

        // Many coroutines sharing fewer connections, which is what a pool is for and what its overhead shows in.
        if (only.isEmpty() || "pool32on8" in only || "pool8on8" in only) {
            val pool = ConnectionPool({ connect(url, transport) }, PoolConfig(maxSize = 8, acquireTimeout = 60.seconds))
            for ((name, callers) in listOf("pool8on8" to 8, "pool32on8" to 32)) {
                if (only.isNotEmpty() && name !in only) continue
                suspend fun run(duration: Double): Double {
                    val start = TimeSource.Monotonic.markNow()
                    val counts = LongArray(callers)
                    coroutineScope {
                        repeat(callers) { i ->
                            launch {
                                var done = 0L
                                while (start.elapsedNow().inWholeMilliseconds < duration * 1000) {
                                    pool.withConnection { selectAndDecode(SELECT_ONE, listOf(randomId(ROWS))) }
                                    done++
                                }
                                counts[i] = done
                            }
                        }
                    }
                    return counts.sum() / start.elapsedNow().inWholeMicroseconds.toDouble() * 1_000_000
                }
                repeat(warmup) { run(seconds) }
                println("RESULT $label $name ${List(iterations) { run(seconds) }.joinToString(" ")}")
            }
            pool.close()
        }

        for (scenario in SCENARIOS) {
            if (only.isNotEmpty() && scenario.name !in only) continue
            val connections = List(scenario.connections) { connect(url, transport) }
            connections.forEach { it.update("CREATE TEMP TABLE bench_insert (id bigint, name text, amount numeric(12, 2), created timestamp)") }
            repeat(warmup) { measure(connections, seconds, scenario.op) }
            val scores = List(iterations) {
                connections.forEach { it.update("TRUNCATE bench_insert") }
                measure(connections, seconds, scenario.op)
            }
            println("RESULT $label ${scenario.name} ${scores.joinToString(" ")}")
            connections.forEach { it.close() }
        }
    }
    val peak = readTextFile("/proc/self/status")?.lineSequence()?.firstOrNull { it.startsWith("VmHWM") }
        ?.filter { it.isDigit() }?.toLongOrNull()
    println("RESULT $label peakRssKb ${peak ?: 0}")
    println("# sink $sink")
}
