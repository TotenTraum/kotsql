package dev.ksql.ktor

import dev.ksql.BatchItem
import dev.ksql.ConstraintViolation
import dev.ksql.ExecuteOptions
import dev.ksql.SqlBatchException
import dev.ksql.sqlErrorDetails
import dev.ksql.SqlConnection
import dev.ksql.SqlDecimal
import dev.ksql.SqlErrorKind
import dev.ksql.SqlException
import dev.ksql.SqlResult
import dev.ksql.TransactionState
import dev.ksql.postgres.PostgresDriver
import dev.ksql.query
import dev.ksql.spi.Drivers
import dev.ksql.transaction
import dev.ksql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import dev.ksql.SqlOffsetTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal expect fun testEnv(name: String): String?

/** Runs on every target against the database in `KSQL_TEST_URL`; skipped when it is not set. */
class ClientIntegrationTest {
    private fun withDb(block: suspend SqlConnection.() -> Unit) = runTest(timeout = 60.seconds) {
        val url = testEnv("KSQL_TEST_URL") ?: return@runTest println("KSQL_TEST_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            val connection = connect(url)
            try { connection.block() } finally { connection.close() }
        }
    }

    @Test fun decodesTypes() = withDb {
        val id = Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        val row = query(
            "SELECT 1::int2, 2::int4, 3::int8, 1.5::float8, 12.50::numeric, 'héllo'::text, true, '\\xdead'::bytea, " +
                "'2024-02-29'::date, '$id'::uuid, ARRAY[1,NULL,3]::int4[], ARRAY['a b','c\"d']::text[], NULL::text",
        ).toList().single()
        assertEquals(1.toShort(), row[0]); assertEquals(2, row[1]); assertEquals(3L, row[2]); assertEquals(1.5, row[3])
        assertEquals(SqlDecimal("12.50"), row[4]); assertEquals("héllo", row[5]); assertEquals(true, row[6])
        assertTrue(byteArrayOf(0xDE.toByte(), 0xAD.toByte()).contentEquals(row[7] as ByteArray))
        assertEquals(LocalDate(2024, 2, 29), row[8]); assertEquals(id, row[9])
        assertEquals(listOf(1, null, 3), row[10]); assertEquals(listOf("a b", "c\"d"), row[11]); assertEquals(null, row[12])
    }

    @Test fun decodesDatesAndTimesExactly() = withDb {
        // Whatever the parsers do, the server's own text form of the value has to come back from the value's fields.
        val row = query(
            """SELECT DATE '0044-03-15 BC', DATE '12345-01-02', TIME '00:00:00', TIME '23:59:59.999999', TIME '12:00:00.1',
               TIMESTAMP '1969-12-31 23:59:59.5', TIMESTAMP '0001-01-01 00:00:00 BC', TIMESTAMP '2024-02-29 12:30:00.000001',
               TIMESTAMPTZ '2024-01-02 03:04:05.25+05:30', TIMETZ '10:20:30-08', TIMETZ '10:20:30.5+05:53:28'""",
        ).toList().single()
        assertEquals(LocalDate(-43, 3, 15), row[0])
        assertEquals(LocalDate(12345, 1, 2), row[1])
        assertEquals(LocalTime(0, 0, 0), row[2])
        assertEquals(LocalTime(23, 59, 59, 999_999_000), row[3])
        assertEquals(LocalTime(12, 0, 0, 100_000_000), row[4])
        assertEquals(LocalDateTime(1969, 12, 31, 23, 59, 59, 500_000_000), row[5])
        assertEquals(LocalDateTime(0, 1, 1, 0, 0, 0), row[6])
        assertEquals(LocalDateTime(2024, 2, 29, 12, 30, 0, 1_000), row[7])
        assertEquals(Instant.parse("2024-01-01T21:34:05.25Z"), row[8])
        assertEquals(SqlOffsetTime(LocalTime(10, 20, 30), UtcOffset(hours = -8)), row[9])
        assertEquals(SqlOffsetTime(LocalTime(10, 20, 30, 500_000_000), UtcOffset(5, 53, 28)), row[10])
    }

    @Test fun decodesExtremeValues() = withDb {
        val row = query(
            """SELECT '-9223372036854775808'::int8, 9223372036854775807::int8, '-2147483648'::int4, '-32768'::int2, 0::int4,
               '00000000-0000-0000-ffff-FFFFFFFFFFFF'::uuid, ''::bytea, '\xABcd'::bytea, 'NaN'::numeric, ''::text""",
        ).toList().single()
        assertEquals(Long.MIN_VALUE, row[0]); assertEquals(Long.MAX_VALUE, row[1]); assertEquals(Int.MIN_VALUE, row[2])
        assertEquals(Short.MIN_VALUE, row[3]); assertEquals(0, row[4]); assertEquals(Long.MIN_VALUE, row.longValue(0))
        assertEquals(Uuid.parse("00000000-0000-0000-ffff-ffffffffffff"), row[5])
        assertEquals(0, (row[6] as ByteArray).size)
        assertTrue(byteArrayOf(0xAB.toByte(), 0xCD.toByte()).contentEquals(row[7] as ByteArray))
        assertEquals(SqlDecimal("NaN"), row[8]); assertEquals("", row[9]); assertEquals(false, row.isNull(9))
    }

    @Test fun reportsValuesWithoutAnEquivalent() = withDb {
        val row = query("SELECT 'infinity'::timestamp, '-infinity'::date, TIME '24:00:00', NULL::int4, NULL::bool").toList().single()
        for (i in 0..2) assertEquals("22008", assertFailsWith<SqlException> { row[i] }.sqlState)
        assertEquals("infinity", row.getText(0))
        assertTrue(row.isNull(3)); assertEquals(0L, row.longValue(3)); assertEquals(false, row.booleanValue(4))
    }

    @Test fun rowsLargerThanTheReadBuffer() = withDb {
        for (fetchSize in listOf(0, 3)) {
            val rows = query("SELECT i, repeat('x', 50000), i * 2 FROM generate_series(1, 20) i", fetchSize = fetchSize).toList()
            assertEquals((1..20).toList(), rows.map { it[0] })
            assertTrue(rows.all { (it[1] as String).length == 50000 && it[2] == (it[0] as Int) * 2 })
        }
    }

    private suspend fun SqlConnection.preparedCount(): Long =
        query("SELECT count(*) FROM pg_prepared_statements WHERE name LIKE 'ksql_%'").toList().single()[0] as Long

    private suspend fun SqlConnection.selectN(id: Int, repeat: Int = 1): Any? {
        var last: Any? = null
        repeat(repeat) { last = query("SELECT $1::int + 1 AS n", listOf(id)).toList().single()[0] }
        return last
    }

    @Test fun preparesRepeatedStatementsOnTheServer() = withDb {
        assertEquals(0L, preparedCount())
        assertEquals(2, selectN(1, repeat = 4))
        assertEquals(0L, preparedCount())
        assertEquals(3, selectN(2, repeat = 10))
        assertEquals(1L, preparedCount())
        // Other parameter types make it another statement.
        repeat(6) { assertEquals(8, query("SELECT $1::int + 1 AS n", listOf(7L)).toList().single()[0]) }
        assertEquals(2L, preparedCount())
    }

    @Test fun failedStatementsNeverGetAName() = withDb {
        repeat(8) { assertFailsWith<SqlException> { query("SELECT no_such_column", emptyList()).toList() } }
        assertEquals(0L, preparedCount())
        // Failing at run time, unlike that one, leaves a usable prepared statement behind.
        repeat(8) { assertEquals("22012", assertFailsWith<SqlException> { query("SELECT 1 / $1::int", listOf(0)).toList() }.sqlState) }
        assertEquals(1L, query("SELECT 1 / $1::int", listOf(1)).toList().single()[0].let { (it as Int).toLong() })
        assertEquals(1L, preparedCount())
    }

    @Test fun statementCacheIsBounded() = withDb {
        for (i in 1..300) repeat(5) { assertEquals(i, query("SELECT $i").toList().single()[0]) }
        // The statements of the eviction go out with the next execution, hence the margin of one.
        assertTrue(preparedCount() in 255L..257L, "prepared: ${preparedCount()}")
        assertEquals(300, query("SELECT 300").toList().single()[0])
    }

    @Test fun retriesWhenPreparedStatementsAreGone() = withDb {
        assertEquals(2, selectN(1, repeat = 8))
        assertEquals(1L, preparedCount())
        update("DEALLOCATE ALL")
        assertEquals(0L, preparedCount())
        assertEquals(3, selectN(2)) // transparently parsed again
        assertEquals(4, selectN(3, repeat = 8))
        assertEquals(1L, preparedCount())
    }

    @Test fun retriesWhenATableChanges() = withDb {
        update("CREATE TEMP TABLE changing(a int)")
        update("INSERT INTO changing VALUES (1)")
        repeat(8) { assertEquals(1, query("SELECT * FROM changing").toList().single()[0]) }
        update("ALTER TABLE changing ALTER COLUMN a TYPE text")
        assertEquals("1", query("SELECT * FROM changing").toList().single()[0])
        // Paged, where Sync has not been sent when the error arrives.
        repeat(8) { assertEquals("1", query("SELECT * FROM changing", fetchSize = 5).toList().single()[0]) }
        update("ALTER TABLE changing ALTER COLUMN a TYPE int USING a::int")
        assertEquals(1, query("SELECT * FROM changing", fetchSize = 5).toList().single()[0])
        assertEquals(1, query("SELECT 1").toList().single()[0])
    }

    @Test fun staleStatementInATransactionFailsOnceAndThenWorks() = withDb {
        update("CREATE TEMP TABLE changing(a int)")
        update("INSERT INTO changing VALUES (1)")
        repeat(8) { query("SELECT * FROM changing").toList() }
        update("BEGIN")
        update("ALTER TABLE changing ALTER COLUMN a TYPE text")
        assertEquals("0A000", assertFailsWith<SqlException> { query("SELECT * FROM changing").toList() }.sqlState)
        assertEquals(TransactionState.FAILED, transactionState)
        update("ROLLBACK")
        assertEquals(1, query("SELECT * FROM changing").toList().single()[0])
    }

    @Test fun preparedStatementsWorkAcrossTransactionsAndCursors() = withDb {
        update("CREATE TEMP TABLE cross_tx(a int)")
        repeat(8) { update("INSERT INTO cross_tx VALUES ($1)", listOf(it)) }
        assertEquals(1L, preparedCount())
        // Prepared statements outlive a rollback, as they aren't transactional.
        transaction { update("INSERT INTO cross_tx VALUES ($1)", listOf(100)) }
        assertFailsWith<IllegalStateException> { transaction { update("INSERT INTO cross_tx VALUES ($1)", listOf(101)); error("rollback") } }
        update("INSERT INTO cross_tx VALUES ($1)", listOf(102))
        assertEquals(10L, query("SELECT count(*) FROM cross_tx").toList().single()[0])
        // A prepared query interleaved with reading an open cursor.
        val cursor = execute("SELECT generate_series(1, 3)")
        val rows = cursor.nextResult() as SqlResult.Rows
        assertEquals(1, rows.next()!![0])
        assertEquals(6, selectN(5, repeat = 8))
        assertEquals(listOf(2, 3), List(2) { rows.next()!![0] })
    }

    @Test fun cacheCanBeTurnedOff() {
        val url = testEnv("KSQL_TEST_URL") ?: return
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
                val connection = connect("$url&prepareThreshold=0")
                try {
                    assertEquals(3, connection.selectN(2, repeat = 10))
                    assertEquals(0L, connection.preparedCount())
                } finally {
                    connection.close()
                }
                assertFailsWith<SqlException> { connect("$url&prepareThreshold=lots") }
            }
        }
    }

    @Test fun errorDetailsForEachKindOfConstraint() = withDb {
        update("CREATE TEMP TABLE parent(id int PRIMARY KEY)")
        update("""CREATE TEMP TABLE child(id int PRIMARY KEY, email text CONSTRAINT child_email UNIQUE, n int NOT NULL DEFAULT 0 CONSTRAINT child_n CHECK (n >= 0),
                  parent_id int CONSTRAINT child_parent REFERENCES parent(id))""")
        update("INSERT INTO child(id, email) VALUES (1, 'a@b')")
        suspend fun failure(sql: String) = assertFailsWith<SqlException> { update(sql) }

        val unique = failure("INSERT INTO child(id, email) VALUES (2, 'a@b')")
        assertEquals("23505", unique.sqlState); assertEquals(SqlErrorKind.INTEGRITY, unique.kind)
        assertEquals(ConstraintViolation.UNIQUE, unique.details.violation); assertEquals("child_email", unique.details.constraint)
        assertEquals("child", unique.details.table); assertTrue(unique.details.schema!!.startsWith("pg_temp"))
        assertEquals("ERROR", unique.details.severity); assertEquals("Key (email)=(a@b) already exists.", unique.details.detail)
        assertEquals("_bt_check_unique", unique.details.routine)

        val foreign = failure("INSERT INTO child(id, parent_id) VALUES (3, 99)")
        assertEquals(ConstraintViolation.FOREIGN_KEY, foreign.details.violation); assertEquals("child_parent", foreign.details.constraint)

        val check = failure("INSERT INTO child(id, n) VALUES (4, -1)")
        assertEquals(ConstraintViolation.CHECK, check.details.violation); assertEquals("child_n", check.details.constraint)

        val notNull = failure("INSERT INTO child(id, n) VALUES (5, NULL)")
        assertEquals(ConstraintViolation.NOT_NULL, notNull.details.violation); assertEquals("n", notNull.details.column); assertEquals("child", notNull.details.table)
    }

    @Test fun errorDetailsForOtherErrors() = withDb {
        val syntax = assertFailsWith<SqlException> { query("SELECT 1 FORM t").toList() }
        // FORM is taken for an alias of the 1, so it's the t that the parser objects to.
        assertEquals(15, syntax.details.position); assertEquals(null, syntax.details.violation)
        val unknown = assertFailsWith<SqlException> { query("SELECT no_such_column").toList() }
        assertEquals(8, unknown.details.position)
        val hinted = assertFailsWith<SqlException> { query("SELECT 1 + 'x'::text").toList() }
        assertTrue(hinted.details.hint!!.isNotEmpty())
        update("CREATE FUNCTION pg_temp.fails() RETURNS int AS $$ BEGIN RAISE EXCEPTION 'custom' USING ERRCODE = 'P0001', HINT = 'try again', DETAIL = 'why'; END; $$ LANGUAGE plpgsql")
        val raised = assertFailsWith<SqlException> { query("SELECT pg_temp.fails()").toList() }
        assertEquals("P0001", raised.sqlState); assertEquals("try again", raised.details.hint); assertEquals("why", raised.details.detail)
        assertTrue(raised.details.where!!.contains("PL/pgSQL"))
        // In a batch, and through wrapping.
        update("CREATE TEMP TABLE b(a int PRIMARY KEY)")
        val batch = assertFailsWith<SqlBatchException> { executeBatch(listOf(BatchItem("INSERT INTO b VALUES (1)"), BatchItem("INSERT INTO b VALUES (1)"))) }
        assertEquals(ConstraintViolation.UNIQUE, batch.details.violation); assertEquals("b_pkey", batch.details.constraint)
        val wrapped = RuntimeException("outer", IllegalStateException("middle", batch))
        assertEquals("b_pkey", wrapped.sqlErrorDetails()?.constraint)
        assertEquals(null, RuntimeException("no database here").sqlErrorDetails())
    }

    @Test fun bindsParameters() = withDb {
        val row = query("SELECT $1::int8 + 1, $2::text, $3::date", listOf(41L, "x", LocalDate(2020, 1, 2))).toList().single()
        assertEquals(42L, row[0]); assertEquals("x", row[1]); assertEquals(LocalDate(2020, 1, 2), row[2])
    }

    @Test fun pagesLargeResults() = withDb {
        val all = query("SELECT generate_series(1, 2500)", fetchSize = 100).map { it[0] as Int }.toList()
        assertEquals((1..2500).toList(), all)
        // Stopping early closes the portal and leaves the connection usable.
        assertEquals(listOf(1, 2, 3), query("SELECT generate_series(1, 100000)", fetchSize = 10).take(3).map { it[0] as Int }.toList())
        assertEquals(1, query("SELECT 1").toList().single()[0])
    }

    @Test fun keepsAnOpenCursorReadableDuringOtherQueries() = withDb {
        for (fetchSize in listOf(0, 2)) {
            val cursor = execute("SELECT generate_series(1, 5)", options = ExecuteOptions(fetchSize))
            val rows = cursor.nextResult() as SqlResult.Rows
            assertEquals(1, rows.next()!![0])
            assertEquals(7, query("SELECT 7").toList().single()[0])
            assertEquals(listOf(2, 3, 4, 5), List(4) { rows.next()!![0] })
            assertEquals(null, rows.next())
            assertEquals(null, cursor.nextResult())
        }
    }

    @Test fun reportsErrorsAndRecovers() = withDb {
        val e = assertFailsWith<SqlException> { query("SELEC 1").toList() }
        assertEquals("42601", e.sqlState); assertEquals(SqlErrorKind.SYNTAX, e.kind)
        // An error in a paged query, where Sync has not been sent yet.
        assertFailsWith<SqlException> { query("SELECT 1/0", fetchSize = 5).toList() }
        assertEquals(1, query("SELECT 1").toList().single()[0])
    }

    @Test fun rollsBackOnFailure() = withDb {
        update("CREATE TEMP TABLE t(a int)")
        assertFailsWith<IllegalStateException> {
            transaction {
                update("INSERT INTO t VALUES (1)")
                error("boom")
            }
        }
        assertEquals(0L, query("SELECT count(*) FROM t").toList().single()[0])
        transaction { update("INSERT INTO t VALUES (1)") }
        assertEquals(1L, query("SELECT count(*) FROM t").toList().single()[0])
    }
}
