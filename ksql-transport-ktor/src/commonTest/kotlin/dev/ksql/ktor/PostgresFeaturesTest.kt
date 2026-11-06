package dev.ksql.ktor

import dev.ksql.SqlConnection
import dev.ksql.SqlDecimal
import dev.ksql.SqlException
import dev.ksql.TransactionState
import dev.ksql.CodecRegistry
import dev.ksql.SqlCodec
import dev.ksql.mysql.MysqlDriver
import dev.ksql.pool.PoolConfig
import dev.ksql.postgres.PgNotification
import dev.ksql.postgres.PostgresDriver
import dev.ksql.postgres.postgres
import dev.ksql.postgres.postgresNotifications
import dev.ksql.query
import dev.ksql.spi.Drivers
import dev.ksql.transaction
import dev.ksql.update
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private enum class Colour { RED, BLUE }

class PostgresFeaturesTest {
    private val url = testEnv("KSQL_TEST_URL")

    private fun pgTest(block: suspend () -> Unit) = runTest(timeout = 120.seconds) {
        if (url == null) return@runTest println("KSQL_TEST_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block()
        }
    }

    private fun db(block: suspend SqlConnection.() -> Unit) = pgTest {
        val connection = connect(url!!)
        try { connection.block() } finally { connection.close() }
    }

    private suspend fun <T> withOther(block: suspend SqlConnection.() -> T): T {
        val other = connect(url!!)
        try { return other.block() } finally { other.close() }
    }

    private fun text(vararg lines: String) = flowOf(*lines.map { it.encodeToByteArray() }.toTypedArray())

    // ---- NOTIFY

    @Test fun deliversInOrderWithPayloadsAndSender() = db {
        val listener = connect(url!!)
        try { coroutineScopeJob {
            val received = async(start = CoroutineStart.UNDISPATCHED) { listener.postgres.notifications("ch_a", "ch b").take(4).toList() }
            delay(200)
            val pid = query("SELECT pg_backend_pid()").toList().single()[0]
            postgres.notify("ch_a", "one")
            postgres.notify("ch b", "дата 😀")
            postgres.notify("ch_a")
            postgres.notify("ch_other", "not for this listener")
            postgres.notify("ch_a", "'; DROP TABLE x; --")
            val all = withTimeout(10_000) { received.await() }
            assertEquals(listOf("ch_a" to "one", "ch b" to "дата 😀", "ch_a" to "", "ch_a" to "'; DROP TABLE x; --"), all.map { it.channel to it.payload })
            assertEquals(pid, all[0].senderPid)
            // Stopping leaves the connection fit for other things, and no longer listening.
            assertEquals(0L, listener.query("SELECT count(*) FROM pg_listening_channels()").toList().single()[0])
            assertEquals(1, listener.query("SELECT 1").toList().single()[0])
        } } finally {
            listener.close()
        }
    }

    @Test fun deliveredOnlyOnCommit() = db {
        val listener = connect(url!!)
        try { coroutineScopeJob {
            val first = async(start = CoroutineStart.UNDISPATCHED) { listener.postgres.notifications("ch_tx").first() }
            delay(200)
            assertFailsWith<IllegalStateException> { transaction { postgres.notify("ch_tx", "rolled back"); error("no") } }
            transaction { postgres.notify("ch_tx", "committed") }
            assertEquals("committed", withTimeout(10_000) { first.await() }.payload)
        } } finally {
            listener.close()
        }
    }

    @Test fun cancellingAWaitClosesItsConnectionCleanly() = db {
        assertFailsWith<TimeoutCancellationException> { withTimeout(200) { postgres.notifications("ch_c").first() } }
        assertTrue(isClosed)
    }

    @Test fun notificationsReceivedWhileRunningStatementsAreKept() = db {
        update("LISTEN ch_buf")
        withOther { postgres.notify("ch_buf", "1"); postgres.notify("ch_buf", "2") }
        delay(100)
        assertEquals(1, query("SELECT 1").toList().single()[0])
        assertEquals(listOf("1", "2"), postgres.drainNotifications().map { it.payload })
        assertEquals(emptyList<PgNotification>(), postgres.drainNotifications())
        // And those come first when the connection turns to listening.
        withOther { postgres.notify("ch_buf", "3") }
        delay(100)
        query("SELECT 1").toList()
        assertEquals("3", withTimeout(10_000) { postgres.notifications("ch_buf").first() }.payload)
    }

    @Test fun listeningInATransactionIsRefused() = db {
        update("BEGIN")
        assertEquals("25001", assertFailsWith<SqlException> { postgres.notifications("x").first() }.sqlState)
        update("ROLLBACK")
    }

    @Test fun survivesLosingItsConnectionAndSaysSo() = pgTest {
        val connections = Channel<Int>(Channel.UNLIMITED)
        val received = Channel<String>(Channel.UNLIMITED)
        var count = 0
        var lastBackend: SqlConnection? = null
        coroutineScopeJob {
            launch {
                postgresNotifications(
                    { connect(url!!).also { lastBackend = it } },
                    "ch_re",
                    reconnectDelay = 50.milliseconds,
                    onConnected = { connections.send(++count) },
                ).collect { received.send(it.payload) }
            }.also { job ->
                try {
                    withTimeout(10_000) {
                        assertEquals(1, connections.receive())
                        withOther { postgres.notify("ch_re", "before") }
                        assertEquals("before", received.receive())
                        // The server ends the session.
                        withOther { query("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND query LIKE 'LISTEN%'").toList() }
                        assertEquals(2, connections.receive())
                        withOther { postgres.notify("ch_re", "after") }
                        assertEquals("after", received.receive())
                    }
                } finally {
                    job.cancel()
                }
            }
        }
        assertTrue(lastBackend != null)
    }

    private suspend fun coroutineScopeJob(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = kotlinx.coroutines.coroutineScope { block() }

    @Test fun aCollectorsErrorIsNotTakenForALostConnection() = pgTest {
        val mine = SqlException("the collector's own connection failed", "08006")
        var connects = 0
        val thrown = assertFailsWith<SqlException> {
            coroutineScopeJob {
                launch { delay(300); withOther { postgres.notify("ch_err", "x") } }
                postgresNotifications({ connects++; connect(url!!) }, "ch_err").collect { throw mine }
            }
        }
        assertSame(mine, thrown)
        assertEquals(1, connects)
    }

    // ---- COPY

    @Test fun copyInAndOutRaw() = db {
        update("CREATE TEMP TABLE c(a int, b text)")
        assertEquals(2, postgres.copyIn("COPY c FROM STDIN", text("1\tone\n", "2\ttwo\n")))
        val out = postgres.copyOut("COPY c TO STDOUT").toList().map { it.decodeToString() }
        assertEquals(listOf("1\tone\n", "2\ttwo\n"), out)
        assertEquals(TransactionState.IDLE, transactionState)
        assertEquals(2L, query("SELECT count(*) FROM c").toList().single()[0])
    }

    @Test fun chunksNeedNotFollowRows() = db {
        update("CREATE TEMP TABLE c(a int, b text)")
        // Cut in the middle of a row, a value, and a multi-byte character.
        val bytes = (1..1000).joinToString("") { "$it\tзначение $it\n" }.encodeToByteArray()
        val chunks = flow { var i = 0; while (i < bytes.size) { emit(bytes.copyOfRange(i, minOf(i + 7, bytes.size))); i += 7 } }
        assertEquals(1000, postgres.copyIn("COPY c FROM STDIN", chunks))
        assertEquals("значение 1000", query("SELECT b FROM c WHERE a = 1000").toList().single()[0])
        assertEquals(0, postgres.copyIn("COPY c FROM STDIN", flow { }))
    }

    @Test fun formatsPassThrough() = db {
        update("CREATE TEMP TABLE c(a int, b text)")
        assertEquals(2, postgres.copyIn("COPY c FROM STDIN (FORMAT csv)", text("1,\"a,b\"\n2,\"x\"\"y\"\n")))
        assertEquals(listOf("1,\"a,b\"\n", "2,\"x\"\"y\"\n"), postgres.copyOut("COPY c TO STDOUT (FORMAT csv)").toList().map { it.decodeToString() })
        // Binary, from one table to another.
        update("CREATE TEMP TABLE d(a int, b text)")
        val binary = postgres.copyOut("COPY c TO STDOUT (FORMAT binary)").toList()
        assertEquals(2, postgres.copyIn("COPY d FROM STDIN (FORMAT binary)", flowOf(*binary.toTypedArray())))
        assertEquals("a,b", query("SELECT b FROM d WHERE a = 1").toList().single()[0])
    }

    @Test fun whenTheSourceFailsNothingIsStored() = db {
        update("CREATE TEMP TABLE c(a int)")
        val boom = IllegalStateException("the file went away")
        val thrown = assertFailsWith<IllegalStateException> {
            postgres.copyIn("COPY c FROM STDIN", flow { emit("1\n".encodeToByteArray()); throw boom })
        }
        assertSame(boom, thrown)
        assertEquals(TransactionState.IDLE, transactionState)
        assertEquals(0L, query("SELECT count(*) FROM c").toList().single()[0])
    }

    @Test fun whenTheDataIsBadTheServerSaysSoAndNothingIsStored() = db {
        update("CREATE TEMP TABLE c(a int PRIMARY KEY)")
        val bad = assertFailsWith<SqlException> { postgres.copyIn("COPY c FROM STDIN", text("1\n", "not a number\n", "3\n")) }
        assertEquals("22P02", bad.sqlState)
        val duplicate = assertFailsWith<SqlException> { postgres.copyIn("COPY c FROM STDIN", text("1\n1\n")) }
        assertEquals("23505", duplicate.sqlState); assertEquals("c_pkey", duplicate.details.constraint)
        assertEquals(0L, query("SELECT count(*) FROM c").toList().single()[0])
    }

    @Test fun insideATransaction() = db {
        update("CREATE TEMP TABLE c(a int)")
        assertFailsWith<IllegalStateException> {
            transaction {
                postgres.copyIn("COPY c FROM STDIN", text("1\n"))
                assertEquals(TransactionState.ACTIVE, transactionState)
                error("roll back")
            }
        }
        assertEquals(0L, query("SELECT count(*) FROM c").toList().single()[0])
        transaction { postgres.copyIn("COPY c FROM STDIN", text("1\n")) }
        assertEquals(1L, query("SELECT count(*) FROM c").toList().single()[0])
    }

    @Test fun wrongStatementsAreReported() = db {
        assertEquals("42P01", assertFailsWith<SqlException> { postgres.copyIn("COPY no_such_table FROM STDIN", text("1\n")) }.sqlState)
        assertEquals("42P01", assertFailsWith<SqlException> { postgres.copyOut("COPY no_such_table TO STDOUT").toList() }.sqlState)
        assertContains(assertFailsWith<SqlException> { postgres.copyIn("SELECT 1", text("1\n")) }.message!!, "not a COPY")
        assertContains(assertFailsWith<SqlException> { postgres.copyOut("SELECT 1").toList() }.message!!, "not a COPY")
        assertEquals(1, query("SELECT 1").toList().single()[0])
        // COPY in the wrong direction can't be finished in an orderly way, and costs the connection.
        assertFailsWith<SqlException> { postgres.copyIn("COPY (SELECT 1) TO STDOUT", text("1\n")) }
        assertTrue(isClosed)
    }

    @Test fun stoppingCopyOutEarlyLeavesTheConnectionInOrder() = db {
        val few = postgres.copyOut("COPY (SELECT generate_series(1, 5000000)) TO STDOUT").take(3).toList()
        assertEquals(listOf("1\n", "2\n", "3\n"), few.map { it.decodeToString() })
        assertEquals(TransactionState.IDLE, transactionState)
        assertEquals(7, query("SELECT 7").toList().single()[0])
    }

    @Test fun normalStatementsStillRefuseCopyWithAClearError() = db {
        update("CREATE TEMP TABLE c(a int)")
        val e = assertFailsWith<SqlException> { update("COPY c FROM STDIN") }
        assertContains(e.message!!, "copyIn")
        assertEquals(1, query("SELECT 1").toList().single()[0])
        // Through the simple protocol, and with another statement started before anyone reads the result.
        val script = executeScript("COPY c FROM STDIN")
        assertContains(assertFailsWith<SqlException> { script.nextResult() }.message!!, "copyIn")
        val unread = execute("COPY c FROM STDIN")
        assertEquals(2, query("SELECT 2").toList().single()[0])
        assertContains(assertFailsWith<SqlException> { unread.nextResult() }.message!!, "copyIn")
        assertEquals(TransactionState.IDLE, transactionState)
    }

    @Test fun rowsWithEveryKindOfValue() = db {
        update("""CREATE TEMP TABLE r(id int, name text, amount numeric, active bool, day date, blob bytea, tags text[], "Odd Name" text)""")
        val tricky = "tab\there\nnew\\back\rslash\\N 😀 \"q\" 'q'"
        val rows = listOf(
            listOf(1, tricky, SqlDecimal("10.50"), true, LocalDate(2024, 2, 29), byteArrayOf(0, 9, 10, 92, -1), listOf("a\tb", null), "x"),
            listOf(2, null, null, null, null, null, null, null),
            listOf(3, "", SqlDecimal("0"), false, LocalDate(1, 1, 1), ByteArray(0), emptyList<String>(), "\\N"),
        )
        assertEquals(3, postgres.copyInRows("r", listOf("id", "name", "amount", "active", "day", "blob", "tags", "Odd Name"), flowOf(*rows.toTypedArray())))
        val read = query("SELECT * FROM r ORDER BY id").toList()
        assertEquals(tricky, read[0][1]); assertEquals(SqlDecimal("10.50"), read[0][2]); assertEquals(true, read[0][3]); assertEquals(LocalDate(2024, 2, 29), read[0][4])
        assertTrue(byteArrayOf(0, 9, 10, 92, -1).contentEquals(read[0][5] as ByteArray)); assertEquals(listOf("a\tb", null), read[0][6])
        assertEquals(List(7) { null }, (1..7).map { read[1][it] })
        assertEquals("", read[2][1]); assertEquals("\\N", read[2][7]) // a backslash and an N, not a NULL
        // And back out, tell a NULL from the text, and the empty from either.
        val out = postgres.copyOutRows("(SELECT id, name, \"Odd Name\" FROM r ORDER BY id)").toList()
        assertEquals(listOf("1", tricky, "x"), out[0]); assertEquals(listOf("2", null, null), out[1]); assertEquals(listOf("3", "", "\\N"), out[2])
        assertEquals(3, postgres.copyOutRows("r").toList().size)
        assertEquals("22023", assertFailsWith<SqlException> { postgres.copyInRows("r", listOf("id"), flowOf(listOf(1, 2))) }.sqlState)
        assertEquals(1, query("SELECT 1").toList().single()[0])
    }

    @Test fun rowsGoThroughCodecsAndManyChunks() = pgTest {
        val connection = connect(url!!, CodecRegistry(SqlCodec.enum<Colour>()))
        try {
            connection.update("CREATE TEMP TABLE r(id int, colour text)")
            val total = 100_000
            assertEquals(total.toLong(), connection.postgres.copyInRows("r", listOf("id", "colour"), flow { repeat(total) { emit(listOf(it, Colour.BLUE)) } }))
            assertEquals("BLUE", connection.query("SELECT max(colour) FROM r").toList().single()[0])
            val sum = connection.postgres.copyOutRows("(SELECT id FROM r)").fold(0L) { acc, row -> acc + row[0]!!.toLong() }
            assertEquals(total * (total - 1L) / 2, sum)
        } finally {
            connection.close()
        }
    }

    @Test fun throughAPool() = pgTest {
        val pool = connectionPool(url!!, PoolConfig(maxSize = 1))
        try {
            pool.update("DROP TABLE IF EXISTS copy_pool")
            pool.update("CREATE TABLE copy_pool(a int)")
            assertEquals(1, pool.withConnection { postgres.copyIn("COPY copy_pool FROM STDIN", text("1\n")) })
            assertEquals(listOf(listOf<String?>("1")), pool.withConnection { postgres.copyOutRows("copy_pool").toList() })
            // A failed copy gives back a fine connection.
            assertFailsWith<SqlException> { pool.withConnection { postgres.copyIn("COPY copy_pool FROM STDIN", text("bad\n")) } }
            assertEquals(1, pool.stats().created)
            pool.update("DROP TABLE copy_pool")
            val released = pool.acquire().also { it.close() }
            assertEquals("08003", assertFailsWith<SqlException> { released.postgres }.sqlState)
        } finally {
            pool.close()
        }
    }

    @Test fun otherDatabasesSaySo() = runTest(timeout = 60.seconds) {
        val hostPort = testEnv("KSQL_TEST_MYSQL_HOST_PORT") ?: return@runTest
        withContext(Dispatchers.Default) {
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            val mysql = connect("mysql://root:ksql@$hostPort/ksql?sslmode=disable&allowPublicKeyRetrieval=true")
            try {
                assertEquals("0A000", assertFailsWith<SqlException> { mysql.postgres }.sqlState)
            } finally {
                mysql.close()
            }
        }
    }
}
