package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.BatchItem
import ru.ttraum.kotsql.ConstraintViolation
import ru.ttraum.kotsql.ExecuteOptions
import ru.ttraum.kotsql.SqlBatchException
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transaction
import ru.ttraum.kotsql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The second driver through the common API, on every target. Needs `KOTSQL_TEST_MYSQL_HOST_PORT`, as host:port, of a server
 * with the user `root` and the password `kotsql`, and the database `kotsql`.
 */
class MysqlClientTest {
    private val hostPort = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")

    private fun url(user: String = "root", password: String = "kotsql", query: String = "sslmode=disable&allowPublicKeyRetrieval=true") =
        "mysql://$user:$password@$hostPort/kotsql?$query"

    private fun mysqlTest(block: suspend () -> Unit) = runTest(timeout = 120.seconds) {
        if (hostPort == null) return@runTest println("KOTSQL_TEST_MYSQL_HOST_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            block()
        }
    }

    private suspend fun <T> using(url: String, block: suspend SqlConnection.() -> T): T {
        val connection = connect(url)
        try { return connection.block() } finally { connection.close() }
    }

    private fun db(block: suspend SqlConnection.() -> Unit) = mysqlTest { using(url(), block) }

    @Test fun decodesBinaryAndTextRowsAlike() = db {
        update("CREATE TEMPORARY TABLE types(a BIGINT, b DECIMAL(5,2), c VARCHAR(20), d BOOLEAN, e VARBINARY(5), f DATE, g DATETIME(6), h BIGINT UNSIGNED, i INT)")
        update("INSERT INTO types VALUES (-9223372036854775808, 12.50, 'héllo 😀', true, x'DEAD', '2024-02-29', '2024-01-02 03:04:05.000001', 18446744073709551615, NULL)")
        val binary = query("SELECT * FROM types").toList().single()
        val cursor = executeScript("SELECT * FROM types")
        val rows = cursor.nextResult() as SqlResult.Rows
        val text = rows.next()!!
        assertEquals(null, rows.next())
        for (row in listOf(binary, text)) {
            assertEquals(Long.MIN_VALUE, row[0]); assertEquals(SqlDecimal("12.50"), row[1]); assertEquals("héllo 😀", row[2]); assertEquals(true, row[3])
            assertTrue(byteArrayOf(0xDE.toByte(), 0xAD.toByte()).contentEquals(row[4] as ByteArray))
            assertEquals(LocalDate(2024, 2, 29), row[5]); assertEquals(LocalDateTime(2024, 1, 2, 3, 4, 5, 1000), row[6])
            assertEquals(SqlDecimal("18446744073709551615"), row[7]); assertEquals(null, row[8]); assertTrue(row.isNull(8))
        }
    }

    @Test fun bindsParameters() = db {
        val row = query("SELECT ? + 1, ?, ?, ?", listOf(41L, "x'; --", 1.5, LocalDate(2020, 1, 2))).toList().single()
        assertEquals(42L, row[0]); assertEquals("x'; --", row[1]); assertEquals(1.5, row[2])
        assertEquals("2020-01-02", row.getText(3))
    }

    @Test fun zeroDatesAreReportedRatherThanInvented() = db {
        update("SET SESSION sql_mode = ''")
        update("CREATE TEMPORARY TABLE z(d DATE)")
        update("INSERT INTO z VALUES ('0000-00-00')")
        val row = query("SELECT d FROM z").toList().single()
        assertEquals("22008", assertFailsWith<SqlException> { row[0] }.sqlState)
        assertEquals("0000-00-00", row.getText(0))
    }

    @Test fun paging() = db {
        update("CREATE TEMPORARY TABLE p(n INT)")
        executeBatch(List(100) { BatchItem("INSERT INTO p VALUES (?)", listOf(it)) })
        assertEquals((0 until 100).toList(), query("SELECT n FROM p ORDER BY n", fetchSize = 7).map { it[0] as Int }.toList())
        // Stopping early ends the server's cursor, and the connection goes on.
        assertEquals(listOf(0, 1), query("SELECT n FROM p ORDER BY n", fetchSize = 7).take(2).map { it[0] as Int }.toList())
        assertEquals(1L, query("SELECT 1").toList().single()[0])
    }

    @Test fun keepsAnOpenCursorReadableDuringOtherQueries() = db {
        update("CREATE TEMPORARY TABLE c(n INT)")
        executeBatch(List(5) { BatchItem("INSERT INTO c VALUES (?)", listOf(it + 1)) })
        for (fetchSize in listOf(0, 2)) {
            val cursor = execute("SELECT n FROM c ORDER BY n", options = ExecuteOptions(fetchSize))
            val rows = cursor.nextResult() as SqlResult.Rows
            assertEquals(1, rows.next()!![0])
            assertEquals(7L, query("SELECT 7").toList().single()[0])
            assertEquals(listOf(2, 3, 4, 5), List(4) { rows.next()!![0] })
            assertEquals(null, rows.next())
            assertEquals(null, cursor.nextResult())
        }
    }

    @Test fun transactionsAndBatches() = db {
        update("CREATE TEMPORARY TABLE t(a INT PRIMARY KEY) ENGINE=InnoDB")
        assertFailsWith<IllegalStateException> {
            transaction {
                update("INSERT INTO t VALUES (1)")
                error("boom")
            }
        }
        assertEquals(0L, query("SELECT COUNT(*) FROM t").toList().single()[0])
        transaction { update("INSERT INTO t VALUES (1)") }
        val e = assertFailsWith<SqlBatchException> { executeBatch(listOf(BatchItem("INSERT INTO t VALUES (2)"), BatchItem("INSERT INTO t VALUES (1)"))) }
        assertEquals(SqlErrorKind.INTEGRITY, e.kind); assertEquals(1062, e.vendorCode); assertEquals(1, e.updateCounts.size)
        assertEquals(1L, query("SELECT COUNT(*) FROM t").toList().single()[0])
    }

    @Test fun generatedKeysAndErrors() = db {
        update("CREATE TEMPORARY TABLE g(id INT AUTO_INCREMENT PRIMARY KEY, v INT)")
        val cursor = execute("INSERT INTO g(v) VALUES (?), (?)", listOf(1, 2))
        val result = cursor.nextResult() as SqlResult.UpdateCount
        assertEquals(2, result.count); assertEquals(listOf(1L, 2L), result.generatedKeys)
        assertEquals("42000", assertFailsWith<SqlException> { query("SELEC").toList() }.sqlState)
        assertEquals(1L, query("SELECT 1").toList().single()[0])
    }

    @Test fun errorDetailsForEachKindOfConstraint() = db {
        update("DROP TABLE IF EXISTS e_child"); update("DROP TABLE IF EXISTS e_parent")
        update("CREATE TABLE e_parent(id INT PRIMARY KEY)")
        update("""CREATE TABLE e_child(id INT PRIMARY KEY, email VARCHAR(20), n INT NOT NULL, parent_id INT, required INT NOT NULL,
                  UNIQUE KEY child_email(email), CONSTRAINT child_n CHECK (n >= 0), CONSTRAINT child_parent FOREIGN KEY (parent_id) REFERENCES e_parent(id))""")
        update("INSERT INTO e_parent VALUES (1)")
        update("INSERT INTO e_child(id, email, n, parent_id, required) VALUES (1, 'a@b', 0, 1, 0)")
        suspend fun failure(sql: String) = assertFailsWith<SqlException> { update(sql) }

        val unique = failure("INSERT INTO e_child(id, email, n, required) VALUES (2, 'a@b', 0, 0)")
        assertEquals(ConstraintViolation.UNIQUE, unique.details.violation); assertEquals("child_email", unique.details.constraint); assertEquals("e_child", unique.details.table)
        assertEquals(1062, unique.vendorCode); assertEquals(SqlErrorKind.INTEGRITY, unique.kind)
        assertEquals("PRIMARY", failure("INSERT INTO e_child(id, n, required) VALUES (1, 0, 0)").details.constraint)

        val child = failure("INSERT INTO e_child(id, n, parent_id, required) VALUES (3, 0, 99, 0)")
        assertEquals(ConstraintViolation.FOREIGN_KEY, child.details.violation); assertEquals("child_parent", child.details.constraint)
        assertEquals("e_child", child.details.table); assertEquals("parent_id", child.details.column); assertEquals("kotsql", child.details.schema)
        val parent = failure("DELETE FROM e_parent WHERE id = 1")
        assertEquals(ConstraintViolation.FOREIGN_KEY, parent.details.violation); assertEquals("child_parent", parent.details.constraint)

        val check = failure("INSERT INTO e_child(id, n, required) VALUES (4, -1, 0)")
        assertEquals(ConstraintViolation.CHECK, check.details.violation); assertEquals("child_n", check.details.constraint)

        val notNull = failure("INSERT INTO e_child(id, n, required) VALUES (5, NULL, 0)")
        assertEquals(ConstraintViolation.NOT_NULL, notNull.details.violation); assertEquals("n", notNull.details.column)
        val noDefault = failure("INSERT INTO e_child(id, n) VALUES (6, 0)")
        assertEquals(ConstraintViolation.NOT_NULL, noDefault.details.violation); assertEquals("required", noDefault.details.column)

        assertEquals("no_such", failure("UPDATE e_child SET no_such = 1").details.column)
        val noTable = failure("DELETE FROM no_such_table")
        assertEquals("kotsql", noTable.details.schema); assertEquals("no_such_table", noTable.details.table)
        assertEquals("email", failure("UPDATE e_child SET email = REPEAT('x', 100)").details.column)
        update("DROP TABLE e_child"); update("DROP TABLE e_parent")
    }

    /** In another language the names can't be read from the message, which must leave them empty, and nothing else amiss. */
    @Test fun errorDetailsInAnotherLanguage() = db {
        update("SET lc_messages = 'de_DE'")
        update("CREATE TEMPORARY TABLE l(id INT PRIMARY KEY)")
        update("INSERT INTO l VALUES (1)")
        val e = assertFailsWith<SqlException> { update("INSERT INTO l VALUES (1)") }
        assertEquals(ConstraintViolation.UNIQUE, e.details.violation); assertEquals(1062, e.vendorCode)
        assertTrue("Doppelter" in e.message!!, e.message)
    }

    @Test fun cancelInterruptsAStatement() = db {
        val connection = this
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        withContext(Dispatchers.Default) {
            launch {
                delay(300)
                connection.cancel()
            }
            // SLEEP treats an interruption as its result: 1 rather than the usual 0.
            assertEquals(1L, query("SELECT SLEEP(30)").toList().single()[0])
        }
        assertTrue(started.elapsedNow() < 10.seconds)
    }

    /** All of this exercises the platform's crypto: SHA-256 and SHA-1 for the tokens, RSA for the password. */
    @Test fun authenticationMethods() = mysqlTest {
        using(url()) {
            update("DROP USER IF EXISTS 'n_sha'@'%', 'n_rsa'@'%', 'n_native'@'%'")
            update("CREATE USER 'n_sha'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret'")
            update("CREATE USER 'n_rsa'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret'")
            update("CREATE USER 'n_native'@'%' IDENTIFIED WITH mysql_native_password BY 'secret'")
            for (user in listOf("n_sha", "n_rsa", "n_native")) update("GRANT ALL ON kotsql.* TO '$user'@'%'")
        }
        suspend fun who(u: String) = using(u) { query("SELECT CURRENT_USER()").toList().single()[0] }
        // Full authentication without TLS is refused, then allowed if asked for, with the password encrypted by RSA.
        assertEquals("28000", assertFailsWith<SqlException> { who(url("n_rsa", "secret", "sslmode=disable")) }.sqlState)
        assertEquals("n_rsa@%", who(url("n_rsa", "secret", "sslmode=disable&allowPublicKeyRetrieval=true")))
        // The server's cache now holds the hash, so the fast path works with no key.
        assertEquals("n_rsa@%", who(url("n_rsa", "secret", "sslmode=disable")))
        // Full authentication over TLS.
        assertEquals("n_sha@%", who(url("n_sha", "secret", "sslmode=require")))
        assertEquals("n_native@%", who(url("n_native", "secret", "sslmode=disable")))
        for (user in listOf("n_sha", "n_native")) {
            val e = assertFailsWith<SqlException> { who(url(user, "wrong", "sslmode=require")) }
            assertEquals(SqlErrorKind.AUTHENTICATION, e.kind)
        }
    }

    @Test fun tlsCarriesLargeData() = mysqlTest {
        using(url(query = "sslmode=require")) {
            assertTrue((query("SHOW SESSION STATUS LIKE 'Ssl_cipher'").toList().single()[1] as String).isNotEmpty())
            val big = "дата-😀-".repeat(100_000)
            update("CREATE TEMPORARY TABLE b(v LONGTEXT)")
            update("INSERT INTO b VALUES (?)", listOf(big))
            assertEquals(big, query("SELECT v FROM b").toList().single()[0])
        }
    }

    @Test fun connectionFailures() = mysqlTest {
        assertEquals("08001", assertFailsWith<SqlException> { connect("mysql://u:p@localhost:1/x?sslmode=disable") }.sqlState)
        val killed = connect(url())
        val pid = killed.query("SELECT CONNECTION_ID()").toList().single()[0]
        using(url()) { update("KILL $pid") }
        delay(100)
        assertFailsWith<SqlException> { killed.query("SELECT 1").toList() }
        assertTrue(killed.isClosed)
    }
}
