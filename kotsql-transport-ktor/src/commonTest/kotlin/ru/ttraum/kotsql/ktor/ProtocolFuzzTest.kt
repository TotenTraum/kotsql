package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.connect
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.postgres.postgres
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.transport.ByteWriter
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** A server that plays back fixed bytes, whatever the client says, and then hangs up. */
private class ScriptedTransport(private val script: ByteArray, private val chunks: Random?) : Transport {
    private var position = 0
    override val isSecure get() = false
    override fun setReadTimeout(millis: Long) {}
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position == script.size) return -1
        val available = minOf(length, script.size - position)
        val n = if (chunks == null) available else chunks.nextInt(1, available + 1)
        script.copyInto(buffer, offset, position, position + n)
        position += n
        return n
    }
    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {}
    override suspend fun flush() {}
    override suspend fun startTls(options: TlsOptions) = throw SqlException("No TLS here", "08004")
    override suspend fun close() {}
}

/**
 * @param payloads the server's messages, each without what [frame] adds, so that mutations can reach past the framing
 * @param act does what an application does, and returns how many values it has read
 */
private class Scenario(
    val name: String,
    val url: String,
    val payloads: List<ByteArray>,
    val frame: (ByteArray) -> ByteArray,
    /** False when the unmutated script can't succeed, as a client nonce that the script can't know prevents. */
    val succeeds: Boolean = true,
    val act: suspend SqlConnection.() -> Int,
)

private suspend fun SqlConnection.readAll(sql: String, params: List<Any?> = emptyList(), fetchSize: Int = 0): Int {
    var values = 0
    query(sql, params, fetchSize).collect { row -> values += readValues(row) }
    return values
}

private suspend fun SqlConnection.readScript(sql: String): Int {
    val cursor = executeScript(sql)
    var values = 0
    while (true) {
        val result = cursor.nextResult() ?: break
        if (result is SqlResult.Rows) while (true) values += readValues(result.next() ?: break)
    }
    cursor.close()
    return values
}

/** A statement that the server refuses, unless a mutation has it otherwise. */
private suspend fun refused(block: suspend () -> Unit) {
    try { block() } catch (e: SqlException) { if (e.sqlState?.startsWith("08") == true) throw e }
}

private fun readValues(row: SqlRow): Int {
    for (i in row.columns.indices) {
        row[i]
        row.getText(i)
    }
    return row.columns.size
}

// ---- PostgreSQL's messages ----

private val PG_FRAME: (ByteArray) -> ByteArray = ::frameTyped

private fun frameTyped(payload: ByteArray): ByteArray {
    if (payload.isEmpty()) return payload
    val w = ByteWriter(payload.size + 4)
    w.int8(payload[0].toInt())
    w.int32(payload.size + 3)
    w.bytes(payload, 1, payload.size - 1)
    return w.buffer.copyOf(w.size)
}

private fun pg(type: Char, body: ByteWriter.() -> Unit = {}): ByteArray {
    val w = ByteWriter()
    w.int8(type.code)
    w.body()
    return w.buffer.copyOf(w.size)
}

private val PG_READY = pg('Z') { int8('I'.code) }
private val PG_STARTUP = listOf(
    pg('R') { int32(0) },
    pg('S') { cstring("server_version"); cstring("17.0") },
    pg('K') { int32(1); int32(2) },
    PG_READY,
)
private val PG_VALUES = listOf(
    21 to "1", 23 to "2", 20 to "3", 701 to "1.5", 1700 to "2.50", 16 to "t", 25 to "text", 17 to "\\x0102", 1082 to "2024-01-02",
    1083 to "03:04:05", 1114 to "2024-01-02 03:04:05.5", 1184 to "2024-01-02 03:04:05+01", 2950 to "00000000-0000-0000-0000-000000000001",
    114 to "{}", 1007 to "{1,2,NULL}", 1009 to "{\"a\",b}", 1186 to "1 day", 16384 to "custom",
)
private val PG_COLUMNS = pg('T') {
    int16(PG_VALUES.size)
    for ((oid, _) in PG_VALUES) { cstring("c$oid"); int32(0); int16(0); int32(oid); int16(-1); int32(-1); int16(0) }
}
private val PG_ROW = pg('D') {
    int16(PG_VALUES.size)
    for ((_, text) in PG_VALUES) { val bytes = text.encodeToByteArray(); int32(bytes.size); bytes(bytes) }
}
/** The driver's question about the type that isn't built in, which it asks after the rest of the first result. */
private val PG_CATALOG = listOf(
    pg('T') { int16(3); for (name in listOf("oid", "typname", "elem")) { cstring(name); int32(0); int16(0); int32(25); int16(-1); int32(-1); int16(0) } },
    pg('D') { int16(3); for (v in listOf("16384", "custom")) { val b = v.encodeToByteArray(); int32(b.size); bytes(b) }; int32(-1) },
    pg('C') { cstring("SELECT 1") }, PG_READY,
)
private val PG_QUERY = listOf(pg('1'), pg('2'), PG_COLUMNS, PG_ROW, pg('N') { int8('M'.code); cstring("a warning"); int8(0) }, PG_ROW, pg('C') { cstring("SELECT 2") }, PG_READY) + PG_CATALOG

private val PG_BINARY_VALUES: List<Pair<Int, ByteArray>> = listOf(
    16 to byteArrayOf(1), 21 to ByteArray(2) { 1 }, 23 to ByteArray(4) { 1 }, 20 to ByteArray(8) { 1 }, 26 to ByteArray(4) { 1 }, 17 to ByteArray(3) { 1 },
    2950 to ByteArray(16) { 1 }, 1082 to ByteArray(4), 1083 to ByteArray(8), 1114 to ByteArray(8),
)
private val PG_BINARY_COLUMNS = pg('T') {
    int16(PG_BINARY_VALUES.size)
    for ((oid, _) in PG_BINARY_VALUES) { cstring("c$oid"); int32(0); int16(0); int32(oid); int16(-1); int32(-1); int16(1) }
}
private val PG_BINARY_ROW = pg('D') {
    int16(PG_BINARY_VALUES.size)
    for ((_, value) in PG_BINARY_VALUES) { int32(value.size); bytes(value) }
}

private const val PG_URL = "postgresql://u:p@h/d?sslmode=disable"

private val PG_SCENARIOS = listOf(
    Scenario("pg-query", PG_URL, PG_STARTUP + PG_QUERY, PG_FRAME) { readAll("SELECT 1") },
    Scenario(
        "pg-binary", PG_URL,
        PG_STARTUP + listOf(pg('1'), pg('2'), PG_BINARY_COLUMNS, PG_BINARY_ROW, PG_BINARY_ROW, pg('C') { cstring("SELECT 2") }, PG_READY), PG_FRAME,
    ) { readAll("SELECT 1") },
    Scenario("pg-params", PG_URL, PG_STARTUP + PG_QUERY, PG_FRAME) { readAll("SELECT $1", listOf(1)) },
    Scenario(
        "pg-portal", PG_URL,
        PG_STARTUP + listOf(pg('1'), pg('2'), PG_COLUMNS, PG_ROW, pg('s'), PG_ROW, pg('C') { cstring("SELECT 2") }, PG_READY) + PG_CATALOG, PG_FRAME,
    ) { readAll("SELECT 1", fetchSize = 1) },
    Scenario(
        "pg-script", PG_URL,
        PG_STARTUP + listOf(PG_COLUMNS, PG_ROW, pg('C') { cstring("SELECT 1") }, pg('C') { cstring("UPDATE 3") }, PG_READY) + PG_CATALOG, PG_FRAME,
    ) { readScript("SELECT 1; UPDATE t SET a = 1") },
    Scenario(
        "pg-error", PG_URL,
        PG_STARTUP + listOf(
            pg('E') {
                for ((k, v) in listOf('S' to "ERROR", 'V' to "ERROR", 'C' to "23505", 'M' to "duplicate", 'P' to "12", 'n' to "c", 't' to "t")) { int8(k.code); cstring(v) }
                int8(0)
            },
            PG_READY,
        ) + PG_QUERY, PG_FRAME,
    ) {
        refused { readAll("INSERT") }
        readAll("SELECT 1")
    },
    Scenario(
        "pg-md5", PG_URL,
        listOf(pg('R') { int32(5); int32(0x01020304) }) + PG_STARTUP, PG_FRAME,
    ) { 1 },
    Scenario(
        "pg-scram", PG_URL,
        listOf(pg('R') { int32(10); cstring("SCRAM-SHA-256"); int8(0) }, pg('R') { int32(11); bytes("r=abc,s=AAAA,i=1".encodeToByteArray()) }) + PG_STARTUP, PG_FRAME,
        succeeds = false,
    ) { 1 },
    Scenario(
        "pg-copy-out", PG_URL,
        PG_STARTUP + listOf(
            pg('H') { int8(0); int16(2); int16(0); int16(0) },
            pg('d') { bytes("1\ta\\tb\n".encodeToByteArray()) }, pg('d') { bytes("2\t\\N\n".encodeToByteArray()) },
            pg('c'), pg('C') { cstring("COPY 2") }, PG_READY,
        ), PG_FRAME,
    ) { postgres.copyOutRows("t").toList().size },
    Scenario(
        "pg-copy-in", PG_URL,
        PG_STARTUP + listOf(pg('G') { int8(0); int16(1); int16(0) }, pg('C') { cstring("COPY 1") }, PG_READY), PG_FRAME,
    ) { postgres.copyIn("COPY t FROM STDIN", flowOf("1\n".encodeToByteArray())).toInt() },
    Scenario(
        "pg-notify", PG_URL,
        PG_STARTUP + listOf(
            pg('C') { cstring("LISTEN") }, PG_READY,
            pg('A') { int32(7); cstring("c"); cstring("one") }, pg('A') { int32(7); cstring("c"); cstring("two") },
            pg('C') { cstring("UNLISTEN") }, PG_READY,
        ), PG_FRAME,
    ) { postgres.notifications("c").take(2).toList().size },
)

// ---- MySQL's messages ----

private fun framePacket(payload: ByteArray): ByteArray {
    val w = ByteWriter(payload.size + 4)
    w.int24LE(payload.size)
    w.int8(0)
    w.bytes(payload)
    return w.buffer.copyOf(w.size)
}

private fun my(body: ByteWriter.() -> Unit): ByteArray {
    val w = ByteWriter()
    w.body()
    return w.buffer.copyOf(w.size)
}

private fun ByteWriter.lenenc(text: String) { val b = text.encodeToByteArray(); int8(b.size); bytes(b) }

private const val MY_CAPABILITIES = 0x200 or 0x8000 or (1 shl 19) or (1 shl 23) or (1 shl 24)
private val MY_GREETING = my {
    int8(10); cstring("8.4.0"); int32LE(1); bytes(ByteArray(8) { 1 }); int8(0); int16LE(MY_CAPABILITIES and 0xFFFF); int8(45); int16LE(2)
    int16LE(MY_CAPABILITIES ushr 16); int8(21); bytes(ByteArray(10)); bytes(ByteArray(12) { 2 }); int8(0); cstring("mysql_native_password")
}
private val MY_OK = my { int8(0); int8(0); int8(0); int16LE(2); int16LE(0) }
private val MY_END = my { int8(0xFE); int8(0); int8(0); int16LE(2); int16LE(0) }
private val MY_STARTUP = listOf(MY_GREETING, MY_OK)

private fun myColumn(type: Int, flags: Int = 0, decimals: Int = 0) = my {
    lenenc("def"); lenenc("db"); lenenc("t"); lenenc("t"); lenenc("c$type"); lenenc("c$type")
    int8(0x0C); int16LE(45); int32LE(255); int8(type); int16LE(flags); int8(decimals); int16LE(0)
}

private val MY_TEXT_VALUES = listOf(
    1 to "1", 2 to "2", 3 to "3", 8 to "4", 9 to "5", 13 to "2024", 4 to "1.5", 5 to "2.5", 246 to "3.50", 15 to "text", 253 to "more", 252 to "blob",
    245 to "{}", 16 to "\u0001", 10 to "2024-01-02", 11 to "03:04:05", 12 to "2024-01-02 03:04:05.5", 7 to "2024-01-02 03:04:05",
)
private val MY_TEXT_RESULT = listOf(my { int8(MY_TEXT_VALUES.size) }) + MY_TEXT_VALUES.map { myColumn(it.first) } +
    List(2) { my { for ((_, text) in MY_TEXT_VALUES) lenenc(text) } } + my { int8(0xFB); repeat(MY_TEXT_VALUES.size - 1) { int8(0xFB) } } + MY_END

private val MY_BINARY_RESULT = run {
    val types = listOf(1, 2, 3, 8, 4, 5, 246, 253, 10, 11, 12)
    val row = my {
        int8(0); bytes(ByteArray((types.size + 9) / 8))
        int8(1); int16LE(2); int32LE(3); int64LE(4); int32LE(0x3FC00000); int64LE(0x4004000000000000L); lenenc("3.50"); lenenc("text")
        int8(4); int16LE(2024); int8(1); int8(2)
        int8(8); int8(0); int32LE(0); int8(3); int8(4); int8(5)
        int8(11); int16LE(2024); int8(1); int8(2); int8(3); int8(4); int8(5); int32LE(500000)
    }
    listOf(my { int8(types.size) }) + types.map { myColumn(it) } + listOf(row, row, MY_END)
}
private val MY_PREPARED = listOf(my { int8(0); int32LE(1); int16LE(11); int16LE(1); int8(0); int16LE(0) }, myColumn(3)) +
    listOf(1, 2, 3, 8, 4, 5, 246, 253, 10, 11, 12).map { myColumn(it) }

private const val MY_URL = "mysql://u:p@h/d?sslmode=disable"

private val MY_SCENARIOS = listOf(
    Scenario("my-text", MY_URL, MY_STARTUP + MY_TEXT_RESULT, ::framePacket) { readScript("SELECT 1") },
    Scenario("my-binary", MY_URL, MY_STARTUP + MY_PREPARED + MY_BINARY_RESULT, ::framePacket) { readAll("SELECT ?", listOf(1)) },
    Scenario(
        "my-error", MY_URL,
        MY_STARTUP + my { int8(0xFF); int16LE(1062); bytes("#23000Duplicate entry '1' for key 't.k'".encodeToByteArray()) } + MY_TEXT_RESULT, ::framePacket,
    ) {
        refused { readScript("INSERT") }
        readScript("SELECT 1")
    },
    Scenario(
        "my-update", MY_URL,
        MY_STARTUP + my { int8(0); int8(3); int8(7); int16LE(2); int16LE(0) } + MY_TEXT_RESULT, ::framePacket,
    ) {
        val cursor = executeScript("INSERT")
        cursor.nextResult()
        cursor.close()
        readScript("SELECT 1")
    },
    Scenario(
        "my-auth-switch", MY_URL,
        listOf(MY_GREETING, my { int8(0xFE); cstring("caching_sha2_password"); bytes(ByteArray(20) { 3 }); int8(0) }, my { int8(1); int8(3) }, MY_OK), ::framePacket,
    ) { 1 },
)

// ---- Mutations ----

private val INTERESTING = intArrayOf(0, 1, -1, 0x7F, 0x80, 0xFF, 0xFB, 0xFC, 0xFD, 0xFE, 0x7FFF, 0xFFFF, 0x7FFFFF, 0xFFFFFF, 0x40000000, 0x7FFFFFFF, Int.MIN_VALUE)

private fun Random.mutate(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return nextBytes(nextInt(1, 8))
    val at = nextInt(bytes.size)
    return when (nextInt(7)) {
        0 -> bytes.copyOf().also { it[at] = nextInt().toByte() }
        1 -> bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl nextInt(8))).toByte() }
        2 -> bytes.copyOf().also { it[at] = INTERESTING.random(this).toByte() }
        3 -> bytes.copyOf(at) // cut short
        4 -> bytes.copyOf(at) + nextBytes(nextInt(1, 16)) + bytes.copyOfRange(at, bytes.size)
        5 -> bytes.copyOf(at) + bytes.copyOfRange(minOf(bytes.size, at + nextInt(1, 8)), bytes.size)
        else -> bytes.copyOf().also {
            // An integer of whichever byte order, in place.
            val value = INTERESTING.random(this)
            val bigEndian = nextBoolean()
            for (i in 0 until minOf(4, it.size - at)) it[at + i] = (value ushr (if (bigEndian) 24 - 8 * i else 8 * i)).toByte()
        }
    }
}

/** Mostly leaves the framing intact, to get as far as the parsers. The rest mangles the stream as it travels. */
private fun Random.mutateScript(scenario: Scenario): ByteArray {
    var payloads = scenario.payloads
    repeat(nextInt(1, 4)) {
        val at = nextInt(payloads.size)
        payloads = when (nextInt(10)) {
            0 -> payloads.filterIndexed { i, _ -> i != at }.ifEmpty { payloads }
            1 -> payloads.take(at) + payloads[nextInt(payloads.size)] + payloads.drop(at)
            else -> payloads.toMutableList().also { it[at] = mutate(it[at]) }
        }
    }
    var script = payloads.map(scenario.frame).fold(ByteArray(0)) { a, b -> a + b }
    if (nextInt(4) == 0) repeat(nextInt(1, 3)) { script = mutate(script) }
    return script
}

/** What the driver may do with any input: read it or fail with a [SqlException], and not be left usable after a broken stream. */
private suspend fun exercise(scenario: Scenario, script: ByteArray, chunks: Random?): Int {
    val connection = connect(scenario.url, TransportFactory { _, _, _ -> ScriptedTransport(script, chunks) })
    try {
        return scenario.act(connection)
    } catch (e: SqlException) {
        if (e.sqlState?.startsWith("08") == true) {
            check(connection.isClosed) { "Still open after a connection failure ${e.sqlState}: ${e.message}" }
        }
        return 0
    } finally {
        connection.close()
    }
}

class ProtocolFuzzTest {
    private val iterations = testEnv("KOTSQL_FUZZ_ITERATIONS")?.toInt() ?: 2_000
    private val baseSeed = testEnv("KOTSQL_FUZZ_SEED")?.toInt() ?: 20261106

    private fun fuzz(scenarios: List<Scenario>) = runTest(timeout = 600.seconds) {
        if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
        if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
        withContext(Dispatchers.Default) {
            // Whatever the mutations find, the unmutated script has to work, or they would explore nothing but its failure.
            for (scenario in scenarios.filter { it.succeeds }) {
                val script = scenario.payloads.map(scenario.frame).fold(ByteArray(0)) { a, b -> a + b }
                for (chunks in listOf(null, Random(1))) {
                    val connection = connect(scenario.url, TransportFactory { _, _, _ -> ScriptedTransport(script, chunks) })
                    try {
                        assertTrue(scenario.act(connection) > 0, "${scenario.name} reads nothing")
                    } catch (e: SqlException) {
                        fail("${scenario.name}: the unmutated script fails: ${e.stackTraceToString()}")
                    } finally {
                        connection.close()
                    }
                }
            }
            val failures = LinkedHashMap<String, String>()
            for (scenario in scenarios) for (i in 0 until iterations) {
                val seed = baseSeed + i
                val random = Random(seed)
                val script = random.mutateScript(scenario)
                try {
                    try {
                        exercise(scenario, script, if (random.nextBoolean()) random else null)
                    } catch (e: SqlException) {
                        // From opening the connection, or from closing it.
                    }
                } catch (e: Throwable) {
                    val trace = e.stackTraceToString().lineSequence().filter { "ru.ttraum.kotsql" in it && "ProtocolFuzzTest" !in it }.firstOrNull()?.trim()
                    val key = "${e::class.simpleName} at $trace"
                    if (key !in failures) failures[key] = "${scenario.name} seed $seed: ${e.message}"
                }
            }
            if (failures.isNotEmpty()) {
                fail("${failures.size} distinct failures:\n" + failures.entries.joinToString("\n") { "${it.key}\n    ${it.value}" })
            }
        }
    }

    @Test fun postgres() = fuzz(PG_SCENARIOS)
    @Test fun mysql() = fuzz(MY_SCENARIOS)

    /** Failures that happened to be easy to pin down. */
    private fun specific(scenario: Scenario, script: ByteArray, sqlState: String) = runTest(timeout = 60.seconds) {
        if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
        if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
        withContext(Dispatchers.Default) {
            val connection = connect(scenario.url, TransportFactory { _, _, _ -> ScriptedTransport(script, null) })
            val e = assertFailsWith<SqlException> { scenario.act(connection) }
            assertEquals(sqlState, e.sqlState, e.message)
            assertTrue(connection.isClosed, "Still open after: ${e.message}")
        }
    }

    private fun script(scenario: Scenario, vararg extra: ByteArray) =
        (scenario.payloads.take(if (scenario.frame === PG_FRAME) PG_STARTUP.size else MY_STARTUP.size).map(scenario.frame) + extra).fold(ByteArray(0)) { a, b -> a + b }

    /** The length is only a claim. Taking it at its word would have 2 GB allocated for a message that never comes. */
    @Test fun postgresHugeLengthAllocatesNothing() = specific(
        PG_SCENARIOS[0],
        script(PG_SCENARIOS[0], ByteWriter().run { int8('1'.code); int32(Int.MAX_VALUE); buffer.copyOf(size) }),
        "08006",
    )

    @Test fun postgresEmptyReadyForQuery() = specific(PG_SCENARIOS[0], script(PG_SCENARIOS[0], PG_FRAME(pg('Z'))), "08P01")

    @Test fun postgresRowOfTheWrongWidth() = specific(
        PG_SCENARIOS[0],
        script(PG_SCENARIOS[0], *listOf(
            pg('1'), pg('2'),
            pg('T') { int16(1); cstring("c"); int32(0); int16(0); int32(23); int16(4); int32(-1); int16(0) },
            pg('D') { int16(0) },
        ).map(PG_FRAME).toTypedArray()),
        "08P01",
    )

    @Test fun mysqlHugeColumnCount() = specific(
        MY_SCENARIOS[0],
        script(MY_SCENARIOS[0], framePacket(my { int8(0xFE); int64LE(Int.MAX_VALUE.toLong()) })),
        "08P01",
    )

    @Test fun mysqlEmptyPacket() = specific(MY_SCENARIOS[0], script(MY_SCENARIOS[0], framePacket(ByteArray(0))), "08P01")

    /** Nothing is allocated for keys that nobody may ask for, however many rows a statement claims to have changed. */
    @Test fun mysqlHugeAffectedCount() = runTest(timeout = 60.seconds) {
        if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
        withContext(Dispatchers.Default) {
            val ok = my { int8(0); int8(0xFD); int8(0xFF); int8(0xFF); int8(0xFF); int8(7); int16LE(2); int16LE(0) }
            val connection = connect(MY_URL, TransportFactory { _, _, _ -> ScriptedTransport((MY_STARTUP + ok).map(::framePacket).fold(ByteArray(0)) { a, b -> a + b }, null) })
            val result = connection.executeScript("INSERT").nextResult() as SqlResult.UpdateCount
            assertEquals(0xFFFFFF, result.generatedKeys.size)
            assertEquals(7L + 5, result.generatedKeys[5])
        }
    }
}
