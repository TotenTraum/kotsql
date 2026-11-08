package ru.ttraum.kotsql.sqlite

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.ConstraintViolation
import ru.ttraum.kotsql.SqlBatchException
import ru.ttraum.kotsql.BatchItem
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.pool.ConnectionPool
import ru.ttraum.kotsql.pool.PoolConfig
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.sqlErrorDetails
import ru.ttraum.kotsql.transaction
import ru.ttraum.kotsql.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A directory for temporary files. */
internal expect val temporaryDirectory: String
internal expect fun deleteFile(path: String)

class SqliteTest {
    private fun memory(block: suspend SqlConnection.() -> Unit) = runTest {
        val connection = connectSqlite(":memory:")
        try { connection.block() } finally { connection.close() }
    }

    private suspend fun SqlConnection.one(sql: String, vararg params: Any?) = query(sql, params.toList()).first()[0]

    @Test fun values() = memory {
        val row = query("SELECT 1, 1.5, 'text', x'0102', NULL").first()
        assertEquals(1L, row[0])
        assertEquals(1.5, row[1])
        assertEquals("text", row[2])
        assertContentEquals(byteArrayOf(1, 2), row[3] as ByteArray)
        assertNull(row[4])
        assertEquals(listOf(SqlType.INTEGER, SqlType.DOUBLE, SqlType.TEXT, SqlType.BINARY, SqlType.OTHER), row.columns.map { it.type })
        assertEquals(SqlType.BIGINT, query("SELECT 5000000000").first().columns[0].type)
        // Whole seconds and fractions alike as the same text that SQLite's own functions give.
        val times = query("SELECT ?, ?, ?", listOf(LocalTime(12, 0), LocalDateTime(2024, 6, 1, 12, 0), LocalTime(1, 2, 3, 500_000_000))).first()
        assertEquals(listOf<Any?>("12:00:00", "2024-06-01 12:00:00", "01:02:03.5"), List(3) { times[it] })
        assertEquals("1", row.getText(0))
        assertEquals(1L, row.longValue(0))
        assertTrue(row.isNull(4))
    }

    @Test fun parameters() = memory {
        assertEquals(1L, one("SELECT ?", true))
        assertEquals(5L, one("SELECT ?", 5))
        assertEquals(5L, one("SELECT ?", 5L))
        assertEquals(1.5, one("SELECT ?", 1.5))
        assertEquals("x", one("SELECT ?", "x"))
        assertEquals("юникод", one("SELECT ?", "юникод"))
        assertContentEquals(byteArrayOf(1), one("SELECT ?", byteArrayOf(1)) as ByteArray)
        assertNull(one("SELECT ?", null))
        assertEquals("2024-02-03", one("SELECT ?", LocalDate(2024, 2, 3)))
        assertEquals("2024-02-03 04:05:00", one("SELECT ?", LocalDateTime(2024, 2, 3, 4, 5)))
        assertEquals("1.50", one("SELECT ?", SqlDecimal("1.50")))
        val id = Uuid.random()
        assertEquals(id.toString(), one("SELECT ?", id))
        // What SQLite's own functions take.
        assertEquals("2024-02-04", one("SELECT date(?, '+1 day')", LocalDateTime(2024, 2, 3, 4, 5)))
        assertEquals("07001", assertFailsWith<SqlException> { query("SELECT ?, ?", listOf(1)).toList() }.sqlState)
        assertEquals("22023", assertFailsWith<SqlException> { query("SELECT ?", listOf(Any())).toList() }.sqlState)
    }

    @Test fun markersOfEveryKind() = memory {
        assertEquals(listOf<Any?>(1L, 1L, 2L), query("SELECT ?1, ?1, ?2", listOf(1, 2)).first().let { r -> List(3) { r[it] } })
        assertEquals(listOf<Any?>(1L, 1L, 2L), query("SELECT :a, :a, @b", listOf(1, 2)).first().let { r -> List(3) { r[it] } })
        assertEquals("?", one("SELECT '?'"))
        assertEquals(0, describe("SELECT '?' /* ? */ -- ?\n").parameterTypes.size)
        assertEquals(3, describe("SELECT ?, :a, :a, ?").parameterTypes.size)
    }

    @Test fun updatesAndKeys() = memory {
        update("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
        val cursor = execute("INSERT INTO t (v) VALUES ('a'), ('b'), ('c')")
        val result = cursor.nextResult() as SqlResult.UpdateCount
        cursor.close()
        assertEquals(3, result.count)
        assertEquals(listOf(1L, 2L, 3L), result.generatedKeys)
        assertEquals(2, update("UPDATE t SET v = 'x' WHERE id > 1"))
        assertEquals(1, update("DELETE FROM t WHERE id = 1"))
        // Nothing to do with the last statement that did change something.
        assertEquals(0, update("CREATE TABLE u (a)"))
        assertEquals(0, update("DELETE FROM t WHERE id = 99"))
    }

    @Test fun returning() = memory {
        update("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
        val cursor = execute("INSERT INTO t (v) VALUES ('a'), ('b') RETURNING id")
        val rows = cursor.nextResult() as SqlResult.Rows
        assertEquals(1L, rows.next()!![0])
        assertEquals(2L, rows.next()!![0])
        assertNull(rows.next())
        assertEquals(2, rows.updateCount)
        cursor.close()
    }

    @Test fun scripts() = memory {
        val cursor = executeScript("CREATE TABLE t (a); INSERT INTO t VALUES (1), (2); SELECT a FROM t; -- that's it\n ;")
        assertEquals(0, (cursor.nextResult() as SqlResult.UpdateCount).count)
        assertEquals(2, (cursor.nextResult() as SqlResult.UpdateCount).count)
        val rows = cursor.nextResult() as SqlResult.Rows
        assertEquals(1L, rows.next()!![0])
        assertNull(cursor.nextResult())
        // A semicolon is not always the end.
        update("INSERT INTO t VALUES (';')")
        assertEquals(";", one("SELECT a FROM t WHERE a = ';' /* ; */"))
        assertEquals("0A000", assertFailsWith<SqlException> { execute("SELECT ?; SELECT ?", listOf(1)) }.sqlState)
    }

    @Test fun triggersHaveSemicolonsInside() = memory {
        executeScript(
            """CREATE TABLE t (a); CREATE TABLE log (a);
               CREATE TRIGGER remember AFTER INSERT ON t BEGIN
                 INSERT INTO log VALUES (new.a);
                 INSERT INTO log VALUES (CASE WHEN new.a > 0 THEN 1 ELSE 0 END);
               END;
               INSERT INTO t VALUES (5);""",
        ).close()
        assertEquals(2L, one("SELECT count(*) FROM log"))
    }

    @Test fun aScriptHappensWhetherItsResultsAreReadOrNot() = memory {
        executeScript("CREATE TABLE t (a); INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)").close()
        assertEquals(2L, one("SELECT count(*) FROM t"))
        executeScript("INSERT INTO t VALUES (3); INSERT INTO t VALUES (4)")
        // The next operation finds it done, and the script stops at what fails.
        assertEquals(4L, one("SELECT count(*) FROM t"))
        val failing = executeScript("INSERT INTO t VALUES (5); SELECT * FROM absent; INSERT INTO t VALUES (6)")
        failing.nextResult()
        assertFailsWith<SqlException> { failing.nextResult() }
        assertNull(failing.nextResult())
        assertEquals(5L, one("SELECT count(*) FROM t"))
    }

    @Test fun anOpenCursorSurvivesAnotherOperation() = memory {
        update("CREATE TABLE t (a)")
        update("INSERT INTO t VALUES (1), (2), (3)")
        val cursor = execute("SELECT a FROM t ORDER BY a")
        val rows = cursor.nextResult() as SqlResult.Rows
        assertEquals(1L, rows.next()!![0])
        update("DROP TABLE t") // takes the statement's lock away
        assertEquals(2L, rows.next()!![0])
        assertEquals(3L, rows.next()!![0])
        assertNull(rows.next())
        cursor.close()
    }

    @Test fun cachedStatements() = memory {
        update("CREATE TABLE t (a)")
        update("INSERT INTO t VALUES (1), (2)")
        // The same text twice at once: the second can't have the statement that the first is still reading.
        val first = execute("SELECT a FROM t ORDER BY a").nextResult() as SqlResult.Rows
        assertEquals(1L, first.next()!![0])
        assertEquals(listOf(1L, 2L), query("SELECT a FROM t ORDER BY a").toList().map { it[0] })
        assertEquals(2L, first.next()!![0])
        assertNull(first.next())
        // A cached statement after the schema changes under it.
        update("DROP TABLE t")
        update("CREATE TABLE t (b, a)")
        update("INSERT INTO t VALUES ('x', 3)")
        assertEquals(listOf(3L), query("SELECT a FROM t ORDER BY a").toList().map { it[0] })
        // A failed statement is fit for the next use, with nothing left bound.
        update("CREATE TABLE u (a NOT NULL)")
        repeat(2) { assertFailsWith<SqlException> { update("INSERT INTO u VALUES (?)", listOf(null)) } }
        assertEquals(1L, update("INSERT INTO u VALUES (?)", listOf(1)))
        // More texts than the cache holds.
        repeat(200) { assertEquals(it.toLong(), one("SELECT $it")) }
        assertEquals(5L, one("SELECT 5"))
    }

    @Test fun noStatementCache() = runTest {
        val driver = SqliteDriver()
        assertEquals("08001", assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite:///:memory:?statement_cache_size=-1")) }.sqlState)
        val connection = driver.connect(ConnectionConfig.parse("sqlite:///:memory:?statement_cache_size=0"))
        try {
            repeat(3) { assertEquals(1L, connection.query("SELECT 1").first()[0]) }
        } finally {
            connection.close()
        }
    }

    @Test fun partOfAResult() = memory {
        update("CREATE TABLE t (a)")
        update("INSERT INTO t VALUES (1), (2), (3)")
        assertEquals(2, query("SELECT a FROM t").take(2).toList().size)
        update("DROP TABLE t")
    }

    @Test fun errors() = memory {
        update("CREATE TABLE p (id INTEGER PRIMARY KEY)")
        update("CREATE TABLE t (a INT UNIQUE, b INT NOT NULL, c INT CHECK (c > 0), d INT REFERENCES p(id))")
        update("PRAGMA foreign_keys = ON")
        update("INSERT INTO t (a, b, c) VALUES (1, 1, 1)")
        suspend fun refused(sql: String, state: String, violation: ConstraintViolation) {
            val e = assertFailsWith<SqlException>(sql) { update(sql) }
            assertEquals(state, e.sqlState, e.message)
            assertEquals(SqlErrorKind.INTEGRITY, e.kind)
            assertEquals(19, e.vendorCode and 0xFF)
            assertEquals(violation, e.sqlErrorDetails()?.violation)
        }
        refused("INSERT INTO t (a, b) VALUES (1, 1)", "23505", ConstraintViolation.UNIQUE)
        refused("INSERT INTO t (a) VALUES (2)", "23502", ConstraintViolation.NOT_NULL)
        refused("INSERT INTO t (b, c) VALUES (1, -1)", "23514", ConstraintViolation.CHECK)
        refused("INSERT INTO t (b, d) VALUES (1, 9)", "23503", ConstraintViolation.FOREIGN_KEY)
        assertEquals("t", assertFailsWith<SqlException> { update("INSERT INTO t (a, b) VALUES (1, 1)") }.sqlErrorDetails()?.table)
        assertEquals("42601", assertFailsWith<SqlException> { update("SELEC 1") }.sqlState)
        assertEquals("42P01", assertFailsWith<SqlException> { update("SELECT * FROM absent") }.sqlState)
        assertEquals("42703", assertFailsWith<SqlException> { update("SELECT absent FROM t") }.sqlState)
        assertEquals(SqlErrorKind.SYNTAX, assertFailsWith<SqlException> { update("SELEC 1") }.kind)
        // Still in working order.
        assertEquals(1L, one("SELECT 1"))
    }

    @Test fun transactions() = memory {
        update("CREATE TABLE t (a)")
        assertEquals(TransactionState.IDLE, transactionState)
        transaction {
            assertEquals(TransactionState.ACTIVE, transactionState)
            update("INSERT INTO t VALUES (1)")
        }
        assertEquals(TransactionState.IDLE, transactionState)
        assertFailsWith<IllegalStateException> {
            transaction {
                update("INSERT INTO t VALUES (2)")
                error("no")
            }
        }
        assertEquals(1L, one("SELECT count(*) FROM t"))
        assertEquals(TransactionState.IDLE, transactionState)
    }

    @Test fun batches() = memory {
        update("CREATE TABLE t (a INT UNIQUE)")
        assertContentEquals(longArrayOf(1, 1, 2), executeBatch(listOf(BatchItem("INSERT INTO t VALUES (?)", listOf(1)), BatchItem("INSERT INTO t VALUES (?)", listOf(2)), BatchItem("UPDATE t SET a = a + 10"))))
        val e = assertFailsWith<SqlBatchException> { executeBatch(listOf(BatchItem("INSERT INTO t VALUES (?)", listOf(100)), BatchItem("INSERT INTO t VALUES (?)", listOf(100)))) }
        assertContentEquals(longArrayOf(1), e.updateCounts)
        // All or nothing.
        assertEquals(0L, one("SELECT count(*) FROM t WHERE a = 100"))
        assertEquals(TransactionState.IDLE, transactionState)
        // Inside a transaction it's for that one to decide.
        transaction { executeBatch(listOf(BatchItem("INSERT INTO t VALUES (7)"))) }
        assertEquals(1L, one("SELECT count(*) FROM t WHERE a = 7"))
    }

    @Test fun describing() = memory {
        update("CREATE TABLE t (a INT, b TEXT)")
        val description = describe("SELECT a, b FROM t WHERE a = ?")
        assertEquals(listOf("a", "b"), description.columns.map { it.name })
        assertEquals(1, description.parameterTypes.size)
        assertFailsWith<SqlException> { describe("SELEC") }
    }

    @Test fun closing() = runTest {
        val connection = connectSqlite(":memory:")
        val cursor = connection.execute("SELECT 1 UNION SELECT 2")
        cursor.nextResult()
        connection.close()
        connection.close()
        assertTrue(connection.isClosed)
        assertFalse(connection.ping())
        assertEquals("08003", assertFailsWith<SqlException> { connection.query("SELECT 1").toList() }.sqlState)
    }

    private fun tempPath() = "$temporaryDirectory/kotsql-${Random.nextLong().toULong()}.db"

    @Test fun files() = runTest {
        val path = tempPath()
        try {
            val driver = SqliteDriver()
            val writer = driver.connect(ConnectionConfig.parse("sqlite:///$path"))
            writer.update("CREATE TABLE t (a)")
            writer.update("INSERT INTO t VALUES (1)")
            writer.close()
            val again = driver.connect(ConnectionConfig.parse("sqlite:///$path?mode=rw"))
            assertEquals(1L, again.query("SELECT a FROM t").first()[0])
            again.close()
            val reader = driver.connect(ConnectionConfig.parse("sqlite:///$path?mode=ro"))
            assertEquals("25006", assertFailsWith<SqlException> { reader.update("INSERT INTO t VALUES (2)") }.sqlState)
            reader.close()
            val missing = assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite:///$path-none?mode=rw")) }
            assertEquals("08001", missing.sqlState, missing.message)
        } finally {
            deleteFile(path)
        }
    }

    @Test fun urls() = runTest {
        val driver = SqliteDriver()
        assertEquals("08001", assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite:///")) }.sqlState)
        assertEquals("08001", assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite://elsewhere/x.db")) }.sqlState)
        assertEquals("08001", assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite:///:memory:?mode=sometimes")) }.sqlState)
        // A setting goes into the text of a statement, and must not bring any more of it along.
        assertEquals("08001", assertFailsWith<SqlException> { driver.connect(ConnectionConfig.parse("sqlite:///:memory:?foreign_keys=1;DROP")) }.sqlState)
        val connection = driver.connect(ConnectionConfig.parse("sqlite:///:memory:?foreign_keys=ON"))
        assertEquals(1L, connection.query("PRAGMA foreign_keys").first()[0])
        assertEquals("SQLite", connection.serverInfo.productName)
        assertTrue(connection.serverInfo.majorVersion >= 3)
        connection.close()
    }

    @Test fun twoConnectionsToAFile() = runTest {
        val path = tempPath()
        try {
            val one = connectSqlite(path)
            val two = connectSqlite(path)
            one.update("CREATE TABLE t (a)")
            one.update("BEGIN IMMEDIATE")
            one.update("INSERT INTO t VALUES (1)")
            two.update("PRAGMA busy_timeout = 10")
            val e = assertFailsWith<SqlException> { two.update("INSERT INTO t VALUES (2)") }
            assertEquals(SqlErrorKind.TIMEOUT, e.kind, e.message)
            one.update("COMMIT")
            assertEquals(1, two.update("INSERT INTO t VALUES (2)"))
            one.close()
            two.close()
        } finally {
            deleteFile(path)
        }
    }

    @Test fun pool() = runTest {
        val path = tempPath()
        try {
            val pool = ConnectionPool({ connectSqlite(path) }, PoolConfig(maxSize = 2))
            pool.update("CREATE TABLE t (a)")
            repeat(20) { pool.transaction { update("INSERT INTO t VALUES ($it)") } }
            assertEquals(20L, pool.query("SELECT count(*) FROM t").first()[0])
            assertEquals(0, pool.stats().inUse)
            pool.close()
        } finally {
            deleteFile(path)
        }
    }
}
