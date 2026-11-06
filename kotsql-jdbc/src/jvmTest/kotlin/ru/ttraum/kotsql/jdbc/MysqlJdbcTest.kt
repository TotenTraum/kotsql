package ru.ttraum.kotsql.jdbc

import com.zaxxer.hikari.HikariConfig
import ru.ttraum.kotsql.sqlErrorDetails
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.math.BigDecimal
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JDBC adapter, which knows nothing of MySQL, against a second database. */
object My {
    const val DATABASE = "test"
    const val USER = "test"
    const val PASSWORD = "test"

    /** Testcontainers' MySQLContainer checks readiness with the official driver, which isn't and shouldn't be on this classpath. */
    val container: GenericContainer<Nothing> by lazy {
        GenericContainer<Nothing>("mysql:8.4").apply {
            withEnv("MYSQL_ROOT_PASSWORD", PASSWORD)
            withEnv("MYSQL_DATABASE", DATABASE)
            withEnv("MYSQL_USER", USER)
            withEnv("MYSQL_PASSWORD", PASSWORD)
            withCommand("--mysql-native-password=ON", "--log-bin-trust-function-creators=ON")
            withExposedPorts(3306)
            waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1).withStartupTimeout(java.time.Duration.ofMinutes(3)))
            start()
        }
    }
    val hostPort get() = "${container.host}:${container.firstMappedPort}"
    val url get() = "jdbc:kotsql:mysql://$hostPort/$DATABASE"
    val user get() = USER
    val password get() = PASSWORD
    fun root(): Connection = DriverManager.getConnection("$url?sslmode=require", "root", PASSWORD)
}

class MysqlJdbcTest {
    private lateinit var c: Connection

    @BeforeEach fun open() {
        c = DriverManager.getConnection(My.url, My.user, My.password)
        c.createStatement().use { it.execute("DROP TABLE IF EXISTS t"); it.execute("CREATE TABLE t(id INT AUTO_INCREMENT PRIMARY KEY, v VARCHAR(50) UNIQUE, n INT)") }
    }
    @AfterEach fun close() = c.close()

    @Test fun escapes() {
        c.createStatement().use { s ->
            s.executeQuery("SELECT {fn UCASE('abc')}, {d '2024-02-03'}, {fn IFNULL(NULL, 'x')}, '{fn UCASE(1)}', 'it\\'s {d}'").use { r ->
                r.next()
                assertEquals("ABC", r.getString(1))
                assertEquals(Date.valueOf("2024-02-03"), r.getDate(2))
                assertEquals("x", r.getString(3))
                assertEquals("{fn UCASE(1)}", r.getString(4))
                assertEquals("it's {d}", r.getString(5))
            }
        }
    }

    /** A comment of MySQL's own, and `--` that isn't one without a space after it. */
    @Test fun escapesAroundItsCommentRules() {
        c.createStatement().use { s ->
            s.executeQuery("SELECT 1 # {fn UNCLOSED(\n, 5--1, {fn UCASE('x')}").use { r ->
                r.next()
                assertEquals(1, r.getInt(1))
                assertEquals(6, r.getInt(2))
                assertEquals("X", r.getString(3))
            }
        }
    }

    @Test fun callsWithOutParameters() {
        c.createStatement().use { s ->
            s.execute("DROP PROCEDURE IF EXISTS both_ways")
            s.execute("CREATE PROCEDURE both_ways(IN a INT, INOUT b INT, OUT d VARCHAR(10)) BEGIN SET b = a + b; SET d = 'done'; END")
            s.execute("DROP FUNCTION IF EXISTS add_up")
            s.execute("CREATE FUNCTION add_up(a INT, b INT) RETURNS INT DETERMINISTIC RETURN a + b")
        }
        c.prepareCall("{call both_ways(?, ?, ?)}").use { call ->
            call.setInt(1, 1)
            call.setInt(2, 2)
            call.registerOutParameter(2, Types.INTEGER)
            call.registerOutParameter(3, Types.VARCHAR)
            assertFalse(call.execute())
            assertEquals(3, call.getInt(2))
            assertEquals("done", call.getString(3))
        }
        c.prepareCall("{? = call add_up(?, ?)}").use { call ->
            call.registerOutParameter(1, Types.INTEGER)
            call.setInt(2, 40)
            call.setInt(3, 2)
            call.execute()
            assertEquals(42, call.getInt(1))
        }
    }

    @Test fun aProcedureWithAResultSet() {
        c.createStatement().use { s ->
            s.execute("DROP PROCEDURE IF EXISTS rows_of")
            s.execute("CREATE PROCEDURE rows_of() BEGIN SELECT 1 UNION SELECT 2; END")
        }
        c.prepareCall("{call rows_of()}").use { call ->
            assertTrue(call.execute())
            call.resultSet.use { r ->
                assertEquals(listOf(1, 2), buildList { while (r.next()) add(r.getInt(1)) })
            }
        }
    }

    @Test fun updatableResultSet() {
        c.createStatement().use { it.execute("INSERT INTO t(v, n) VALUES ('a', 1), ('b', 2), ('c', 3)") }
        c.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_UPDATABLE).use { s ->
            s.executeQuery("SELECT id, v, n FROM t ORDER BY id").use { r ->
                assertEquals(java.sql.ResultSet.CONCUR_UPDATABLE, r.concurrency)
                r.next()
                r.updateInt("n", 10)
                r.updateRow()
                assertEquals(10, r.getInt("n"))
                r.next()
                r.deleteRow()
                r.moveToInsertRow()
                r.updateString("v", "d")
                r.updateInt("n", 4)
                r.insertRow()
                r.moveToCurrentRow()
            }
        }
        c.createStatement().use { s ->
            s.executeQuery("SELECT v, n FROM t ORDER BY v").use { r ->
                val all = buildList { while (r.next()) add(r.getString(1) to r.getInt(2)) }
                assertEquals(listOf("a" to 10, "c" to 3, "d" to 4), all)
            }
        }
    }

    @Test fun selectsAllCommonTypes() {
        c.createStatement().use { s ->
            s.execute(
                """CREATE TEMPORARY TABLE types(a TINYINT, b SMALLINT, c INT, d BIGINT, e FLOAT, f DOUBLE, g DECIMAL(5,2), h VARCHAR(10),
                   i BOOLEAN, j VARBINARY(10), k DATE, l TIME(3), m DATETIME(6), o TIMESTAMP NULL, p JSON, q TEXT, r BIGINT UNSIGNED, u INT UNSIGNED, z INT)""",
            )
            s.execute(
                """INSERT INTO types VALUES (-1, -2, -3, -4, 1.5, 2.5, 10.25, 'héllo', true, x'CAFE', '2024-02-29', '13:14:15.500',
                   '2024-01-02 03:04:05.123456', '2024-01-02 03:04:05', '{"a": 1}', 'text', 18446744073709551615, 4294967295, NULL)""",
            )
        }
        // The same values through the text protocol, as a plain statement, and the binary protocol, as a prepared one.
        fun check(r: java.sql.ResultSet) {
            assertTrue(r.next())
            assertEquals(-1, r.getInt(1)); assertEquals((-2).toShort(), r.getShort(2)); assertEquals(-3, r.getInt(3)); assertEquals(-4L, r.getLong(4))
            assertEquals(1.5f, r.getFloat(5)); assertEquals(2.5, r.getDouble(6)); assertEquals(BigDecimal("10.25"), r.getBigDecimal(7))
            assertEquals("héllo", r.getString(8)); assertTrue(r.getBoolean(9))
            assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), r.getBytes(10))
            assertEquals(Date.valueOf("2024-02-29"), r.getDate(11)); assertEquals(LocalDate.of(2024, 2, 29), r.getObject(11, LocalDate::class.java))
            assertEquals(java.time.LocalTime.of(13, 14, 15, 500_000_000), r.getObject(12, java.time.LocalTime::class.java))
            assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123456000), r.getObject(13, LocalDateTime::class.java))
            assertEquals("2024-01-02 03:04:05.123456", r.getString(13))
            assertEquals(Timestamp.valueOf("2024-01-02 03:04:05"), r.getTimestamp(14))
            assertEquals("{\"a\": 1}", r.getString(15)); assertEquals("text", r.getString(16))
            assertEquals(BigDecimal("18446744073709551615"), r.getBigDecimal(17)); assertEquals("18446744073709551615", r.getString(17))
            assertEquals(4294967295L, r.getLong(18))
            assertEquals(0, r.getInt(19)); assertTrue(r.wasNull()); assertNull(r.getObject(19))
            assertFalse(r.next())
            val md = r.metaData
            assertEquals(19, md.columnCount); assertEquals(Types.NUMERIC, md.getColumnType(7)); assertEquals(5, md.getPrecision(7)); assertEquals(2, md.getScale(7))
            assertEquals("BIGINT UNSIGNED", md.getColumnTypeName(17)); assertFalse(md.isSigned(18)); assertTrue(md.isSigned(3))
            assertEquals(Types.BOOLEAN, md.getColumnType(9))
        }
        c.createStatement().use { s -> s.executeQuery("SELECT * FROM types").use(::check) }
        c.prepareStatement("SELECT * FROM types").use { p -> p.executeQuery().use(::check) }
    }

    @Test fun preparedStatementRoundTripsTypes() {
        c.createStatement().use { it.execute(
            "CREATE TEMPORARY TABLE p(a SMALLINT, b INT, c BIGINT, d FLOAT, e DOUBLE, f DECIMAL(10,2), g VARCHAR(50), h BOOLEAN, i VARBINARY(10), j DATE, k TIME, l DATETIME(6), o CHAR(36))",
        ) }
        val id = UUID.randomUUID()
        val at = Timestamp.valueOf("2021-06-07 08:09:10.5")
        c.prepareStatement("INSERT INTO p VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setShort(1, 1); s.setInt(2, 2); s.setLong(3, 3); s.setFloat(4, 1.5f); s.setDouble(5, 2.5)
            s.setBigDecimal(6, BigDecimal("3.14")); s.setString(7, "it's \"q\" ? \\ 😀"); s.setBoolean(8, true); s.setBytes(9, byteArrayOf(0, 1, -1))
            s.setDate(10, Date.valueOf("2021-06-07")); s.setTime(11, java.sql.Time.valueOf("08:09:10")); s.setTimestamp(12, at); s.setObject(13, id)
            assertEquals(1, s.executeUpdate())
        }
        c.prepareStatement("SELECT * FROM p WHERE g = ? AND a = ?").use { s ->
            s.setString(1, "it's \"q\" ? \\ 😀"); s.setInt(2, 1)
            s.executeQuery().use { r ->
                assertTrue(r.next())
                assertEquals(BigDecimal("3.14"), r.getBigDecimal("f")); assertContentEquals(byteArrayOf(0, 1, -1), r.getBytes("i"))
                assertEquals(at, r.getTimestamp("l")); assertEquals(id.toString(), r.getString("o"))
            }
        }
    }

    @Test fun parametersCannotInjectSql() {
        c.prepareStatement("INSERT INTO t(v) VALUES (?)").use { s ->
            s.setString(1, "'); DROP TABLE t; -- \\"); s.executeUpdate()
        }
        c.createStatement().use { s -> s.executeQuery("SELECT v FROM t").use { r -> r.next(); assertEquals("'); DROP TABLE t; -- \\", r.getString(1)) } }
    }

    @Test fun placeholdersAreOnlyRecognizedInCode() {
        c.prepareStatement("SELECT '?', `n` AS `a?`, ? /* ? */, 'it''s ?', \"?\" -- ?\n, 5-1 # ?\n FROM (SELECT 1 AS n) x").use { s ->
            assertEquals(1, s.parameterMetaData.parameterCount)
            s.setInt(1, 5)
            s.executeQuery().use { r -> r.next(); assertEquals("?", r.getString(1)); assertEquals("5", r.getString(3)) }
        }
        c.prepareStatement("SELECT ?, ?").use { s ->
            s.setInt(1, 1)
            assertEquals("07002", assertFailsWith<SQLException> { s.executeQuery() }.sqlState)
        }
    }

    @Test fun generatedKeys() {
        c.prepareStatement("INSERT INTO t(v) VALUES (?), (?)", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setString(1, "a"); s.setString(2, "b")
            assertEquals(2, s.executeUpdate())
            s.generatedKeys.use { k ->
                assertTrue(k.next()); val first = k.getLong(1)
                assertTrue(first > 0)
                assertTrue(k.next()); assertEquals(first + 1, k.getLong("GENERATED_KEY"))
                assertFalse(k.next())
            }
        }
        c.createStatement().use { s ->
            assertEquals(1, s.executeUpdate("INSERT INTO t(v) VALUES ('c')", Statement.RETURN_GENERATED_KEYS))
            s.generatedKeys.use { k -> assertTrue(k.next()); assertTrue(k.getLong(1) > 0) }
            // A query keeps its rows, however it was asked.
            assertTrue(s.execute("SELECT 1", Statement.RETURN_GENERATED_KEYS))
            s.resultSet.use { r -> assertTrue(r.next()) }
        }
    }

    @Test fun batches() {
        c.prepareStatement("INSERT INTO t(v, n) VALUES (?, ?)").use { s ->
            repeat(300) { s.setString(1, "v$it"); if (it % 2 == 0) s.setInt(2, it) else s.setNull(2, Types.INTEGER); s.addBatch() }
            val counts = s.executeBatch()
            assertEquals(300, counts.size); assertTrue(counts.all { it == 1 })
        }
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*), COUNT(n) FROM t").use { r -> r.next(); assertEquals(300, r.getInt(1)); assertEquals(150, r.getInt(2)) } }
    }

    @Test fun failedBatchIsAtomicAndReportsCounts() {
        c.prepareStatement("INSERT INTO t(v) VALUES (?)").use { s ->
            listOf("a", "b", "a", "c").forEach { s.setString(1, it); s.addBatch() }
            val e = assertFailsWith<BatchUpdateException> { s.executeBatch() }
            assertEquals("23000", e.sqlState); assertEquals(2, e.updateCounts.size)
        }
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM t").use { r -> r.next(); assertEquals(0, r.getInt(1)) } }
    }

    @Test fun transactionsAndSavepoints() {
        val other = DriverManager.getConnection(My.url, My.user, My.password)
        fun count() = other.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM t").use { r -> r.next(); r.getInt(1) } }
        c.autoCommit = false
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
        assertEquals(0, count())
        c.commit()
        assertEquals(1, count())
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('b')") }
        c.rollback()
        assertEquals(1, count())
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('c')") }
        val sp = c.setSavepoint("my point")
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('d')") }
        c.rollback(sp)
        c.commit()
        assertEquals(2, count())
        // Unlike PostgreSQL, a failed statement leaves the transaction usable.
        assertFailsWith<SQLSyntaxErrorException> { c.createStatement().use { it.execute("SELEC") } }
        c.createStatement().use { assertTrue(it.execute("SELECT 1")) }
        c.rollback()
        c.autoCommit = true
        assertFailsWith<SQLException> { c.commit() }
        for (level in listOf(Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ)) {
            c.transactionIsolation = level
            assertEquals(level, c.transactionIsolation)
        }
        other.close()
    }

    @Test fun mapsErrors() {
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO t(v) VALUES ('a')")
            val e = assertFailsWith<SQLIntegrityConstraintViolationException> { s.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
            assertEquals("23000", e.sqlState); assertEquals(1062, e.errorCode)
            assertFailsWith<SQLSyntaxErrorException> { s.executeQuery("SELECT * FROM no_such_table") }
            s.executeQuery("SELECT 1").use { r -> assertTrue(r.next()) }
        }
    }

    @Test fun errorDetailsThroughJdbc() {
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO t(v) VALUES ('a')")
            val e = assertFailsWith<SQLIntegrityConstraintViolationException> { s.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
            assertEquals(ru.ttraum.kotsql.ConstraintViolation.UNIQUE, e.sqlErrorDetails()?.violation); assertEquals("v", e.sqlErrorDetails()?.constraint)
        }
    }

    @Test fun codecsThroughJdbc() {
        KotsqlDataSource(My.url, My.user, My.password, ru.ttraum.kotsql.CodecRegistry(ru.ttraum.kotsql.SqlCodec.enum<TestMood>())).connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE TEMPORARY TABLE m(v ENUM('SAD', 'HAPPY'))") }
            conn.prepareStatement("INSERT INTO m VALUES (?)").use { s -> s.setObject(1, TestMood.HAPPY); s.executeUpdate() }
            conn.prepareStatement("SELECT v FROM m WHERE v = ?").use { s ->
                s.setObject(1, TestMood.HAPPY)
                s.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals(TestMood.HAPPY, r.getObject(1, TestMood::class.java)); assertEquals("HAPPY", r.getString(1))
                    assertEquals("ENUM", r.metaData.getColumnTypeName(1))
                }
            }
        }
    }

    @Test fun multipleResults() {
        c.createStatement().use { s ->
            assertTrue(s.execute("SELECT 1; INSERT INTO t(v) VALUES ('a'); SELECT 2"))
            s.resultSet.use { r -> r.next(); assertEquals(1, r.getInt(1)) }
            assertFalse(s.moreResults); assertEquals(1, s.updateCount)
            assertTrue(s.moreResults); s.resultSet.use { r -> r.next(); assertEquals(2, r.getInt(1)) }
            assertFalse(s.moreResults); assertEquals(-1, s.updateCount)
        }
    }

    @Test fun nestedQueriesWhileIteratingAndPaging() {
        c.createStatement().use { it.execute("INSERT INTO t(n) SELECT 1 FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3) a") }
        c.createStatement().use { it.execute("INSERT INTO t(n) SELECT n FROM t a CROSS JOIN (SELECT 1 x UNION SELECT 2 UNION SELECT 3) b") }
        for (fetch in listOf(0, 3)) c.prepareStatement("SELECT id FROM t ORDER BY id").use { outer ->
            outer.fetchSize = fetch
            outer.executeQuery().use { r ->
                var rows = 0
                while (r.next()) {
                    c.prepareStatement("SELECT ? * 2").use { inner ->
                        inner.setInt(1, r.getInt(1))
                        inner.executeQuery().use { i -> i.next(); assertEquals(r.getInt(1) * 2L, i.getLong(1)) }
                    }
                    rows++
                }
                assertEquals(12, rows)
            }
        }
        // Reading in pages, without anything in between, and leaving early.
        c.prepareStatement("SELECT id FROM t").use { s ->
            s.fetchSize = 5
            s.executeQuery().use { r -> var n = 0; while (r.next()) n++; assertEquals(12, n) }
            s.executeQuery().use { r -> assertTrue(r.next()) }
        }
        c.createStatement().use { s -> s.executeQuery("SELECT 1").use { r -> assertTrue(r.next()) } }
    }

    @Test fun queryTimeoutAndCancel() {
        c.createStatement().use { s ->
            s.queryTimeout = 1
            // SLEEP reports an interruption as its result rather than an error, which only a query like this turns into one.
            val result = runCatching { s.executeQuery("SELECT SLEEP(30) FROM t, (SELECT 1 UNION SELECT 2) x") }
            assertTrue(result.isSuccess || result.exceptionOrNull() is SQLTimeoutException)
        }
        c.createStatement().use { s ->
            thread { Thread.sleep(300); s.cancel() }
            val started = System.nanoTime()
            s.executeQuery("SELECT SLEEP(30)").use { r -> r.next(); assertEquals(1, r.getInt(1)) }
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
        }
        assertTrue(c.isValid(2))
    }

    @Test fun databaseMetaData() {
        c.createStatement().use {
            it.execute("DROP TABLE IF EXISTS md_child"); it.execute("DROP TABLE IF EXISTS md_parent")
            it.execute("CREATE TABLE md_parent(id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(20) NOT NULL DEFAULT 'x', amount DECIMAL(8,3), UNIQUE KEY md_name(name))")
            it.execute("CREATE TABLE md_child(id INT PRIMARY KEY, parent_id INT, FOREIGN KEY (parent_id) REFERENCES md_parent(id) ON DELETE CASCADE)")
        }
        val md = c.metaData
        assertEquals("MySQL", md.databaseProductName); assertEquals(8, md.databaseMajorVersion); assertEquals("`", md.identifierQuoteString)
        md.getTables(null, null, "md_%", arrayOf("TABLE")).use { r ->
            assertTrue(r.next()); assertEquals("md_child", r.getString("TABLE_NAME")); assertEquals("TABLE", r.getString("TABLE_TYPE"))
            assertTrue(r.next()); assertEquals("md_parent", r.getString(3)); assertFalse(r.next())
        }
        md.getColumns(null, null, "md_parent", null).use { r ->
            assertTrue(r.next()); assertEquals("id", r.getString("COLUMN_NAME")); assertEquals(Types.INTEGER, r.getInt("DATA_TYPE")); assertEquals("YES", r.getString("IS_AUTOINCREMENT"))
            assertTrue(r.next()); assertEquals(20, r.getInt("COLUMN_SIZE")); assertEquals("NO", r.getString("IS_NULLABLE")); assertEquals("x", r.getString("COLUMN_DEF"))
            assertTrue(r.next()); assertEquals(8, r.getInt("COLUMN_SIZE")); assertEquals(3, r.getInt("DECIMAL_DIGITS")); assertFalse(r.next())
        }
        md.getPrimaryKeys(null, null, "md_parent").use { r -> assertTrue(r.next()); assertEquals("id", r.getString("COLUMN_NAME")) }
        md.getImportedKeys(null, null, "md_child").use { r ->
            assertTrue(r.next()); assertEquals("md_parent", r.getString("PKTABLE_NAME")); assertEquals("parent_id", r.getString("FKCOLUMN_NAME"))
            assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, r.getInt("DELETE_RULE"))
        }
        md.getExportedKeys(null, null, "md_parent").use { r -> assertTrue(r.next()); assertEquals("md_child", r.getString("FKTABLE_NAME")) }
        md.getIndexInfo(null, null, "md_parent", true, false).use { r ->
            val names = buildSet { while (r.next()) add(r.getString("INDEX_NAME")) }
            assertTrue("md_name" in names, names.toString())
        }
        md.catalogs.use { r -> val all = buildSet { while (r.next()) add(r.getString(1)) }; assertTrue(My.DATABASE in all) }
        md.tableTypes.use { r -> assertTrue(r.next()) }
        assertEquals(My.DATABASE, c.catalog)
        c.catalog = "information_schema"
        assertEquals("information_schema", c.catalog)
    }

    @Test fun authentication() {
        My.root().use { root -> root.createStatement().use { s ->
            s.execute("DROP USER IF EXISTS 'u_sha'@'%', 'u_native'@'%', 'u_rsa'@'%', 'u_empty'@'%'")
            s.execute("CREATE USER 'u_sha'@'%' IDENTIFIED WITH caching_sha2_password BY 'pä ss'")
            s.execute("CREATE USER 'u_rsa'@'%' IDENTIFIED WITH caching_sha2_password BY 'secret'")
            s.execute("CREATE USER 'u_native'@'%' IDENTIFIED WITH mysql_native_password BY 'pä ss'")
            s.execute("CREATE USER 'u_empty'@'%' IDENTIFIED WITH caching_sha2_password BY ''")
            for (user in listOf("u_sha", "u_rsa", "u_native", "u_empty")) s.execute("GRANT ALL ON ${My.DATABASE}.* TO '$user'@'%'")
        } }
        fun plugin(conn: Connection) = conn.createStatement().use { s -> s.executeQuery("SELECT CURRENT_USER()").use { r -> r.next(); r.getString(1) } }
        val plain = "${My.url}?sslmode=disable"

        // Full authentication: without TLS it's refused unless explicitly allowed, as the key could be an attacker's.
        val refused = assertFailsWith<SQLInvalidAuthorizationSpecException> { DriverManager.getConnection(plain, "u_rsa", "secret") }
        assertTrue("allowPublicKeyRetrieval" in refused.message!!, refused.message)
        DriverManager.getConnection("$plain&allowPublicKeyRetrieval=true", "u_rsa", "secret").use { assertEquals("u_rsa@%", plugin(it)) }
        // Now the server has the hash in its cache, and takes the fast path with no key at all.
        DriverManager.getConnection(plain, "u_rsa", "secret").use { assertEquals("u_rsa@%", plugin(it)) }

        DriverManager.getConnection("${My.url}?sslmode=require", "u_sha", "pä ss").use { assertEquals("u_sha@%", plugin(it)) }
        DriverManager.getConnection(plain, "u_native", "pä ss").use { assertEquals("u_native@%", plugin(it)) }
        DriverManager.getConnection(plain, "u_empty", "").use { assertEquals("u_empty@%", plugin(it)) }
        for (user in listOf("u_sha", "u_native")) {
            assertFailsWith<SQLInvalidAuthorizationSpecException> { DriverManager.getConnection("${My.url}?sslmode=require", user, "wrong") }
        }
    }

    @Test fun tlsModes() {
        val props = { mode: String -> Properties().apply { put("user", My.user); put("password", My.password); put("sslmode", mode) } }
        fun cipher(conn: Connection) = conn.createStatement().use { s -> s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'").use { r -> r.next(); r.getString(2) } }
        DriverManager.getConnection(My.url, props("require")).use { assertTrue(cipher(it).isNotEmpty()) }
        DriverManager.getConnection(My.url, props("prefer")).use { assertTrue(cipher(it).isNotEmpty()) }
        assertFailsWith<SQLException> { DriverManager.getConnection(My.url, props("verify-ca")) }
        val cert = java.nio.file.Files.createTempFile("my", ".pem").toFile()
        My.container.copyFileFromContainer("/var/lib/mysql/ca.pem", cert.path)
        val pinned = { mode: String -> props(mode).apply { put("sslrootcert", cert.path) } }
        DriverManager.getConnection(My.url, pinned("verify-ca")).use { assertTrue(cipher(it).isNotEmpty()) }
        // The server's generated certificate names no host.
        assertFailsWith<SQLException> { DriverManager.getConnection(My.url, pinned("verify-full")) }
    }

    @Test fun largeValues() {
        c.createStatement().use { it.execute("CREATE TEMPORARY TABLE big(b LONGBLOB)") }
        val data = ByteArray(1_000_000) { it.toByte() }
        c.prepareStatement("INSERT INTO big VALUES (?)").use { s -> s.setBytes(1, data); s.executeUpdate() }
        c.prepareStatement("SELECT b FROM big").use { s -> s.executeQuery().use { r -> r.next(); assertContentEquals(data, r.getBytes(1)) } }
    }

    @Test fun sharedConnectionIsThreadSafe() {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        List(8) { t ->
            thread {
                try {
                    repeat(100) { i ->
                        c.prepareStatement("SELECT ? + 0").use { s ->
                            s.setInt(1, t * 1000 + i)
                            s.executeQuery().use { r -> r.next(); assertEquals(t * 1000L + i, r.getLong(1)) }
                        }
                    }
                } catch (e: Throwable) { errors += e }
            }
        }.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test fun statementCacheIsBoundedAndSurvivesSchemaChanges() {
        repeat(300) { i -> c.prepareStatement("SELECT $i").use { s -> s.executeQuery().use { r -> r.next(); assertEquals(i.toLong(), r.getLong(1)) } } }
        c.createStatement().use { it.execute("CREATE TEMPORARY TABLE changing(a INT)"); it.execute("INSERT INTO changing VALUES (1)") }
        repeat(3) { c.prepareStatement("SELECT * FROM changing").use { s -> s.executeQuery().use { r -> r.next(); assertEquals(1, r.getInt(1)) } } }
        c.createStatement().use { it.execute("ALTER TABLE changing ADD COLUMN b INT DEFAULT 7") }
        c.prepareStatement("SELECT * FROM changing").use { s -> s.executeQuery().use { r -> r.next(); assertEquals(7, r.getInt("b")) } }
    }

    @Test fun preparedStatementsFollowTheCurrentDatabase() {
        My.root().use { root -> root.createStatement().use { s ->
            s.execute("DROP DATABASE IF EXISTS other_db")
            s.execute("CREATE DATABASE other_db")
            s.execute("CREATE TABLE other_db.same_name(v VARCHAR(10))")
            s.execute("INSERT INTO other_db.same_name VALUES ('other')")
            s.execute("CREATE TABLE IF NOT EXISTS ${My.DATABASE}.same_name(v VARCHAR(10))")
            s.execute("DELETE FROM ${My.DATABASE}.same_name")
            s.execute("INSERT INTO ${My.DATABASE}.same_name VALUES ('home')")
            s.execute("GRANT ALL ON other_db.* TO '${My.USER}'@'%'")
        } }
        fun read() = c.prepareStatement("SELECT v FROM same_name").use { s -> s.executeQuery().use { r -> r.next(); r.getString(1) } }
        repeat(3) { assertEquals("home", read()) }
        c.createStatement().use { it.execute("USE other_db") }
        assertEquals("other", read())
        c.catalog = My.DATABASE
        assertEquals("home", read())
    }

    @Test fun hikariPool() {
        val config = HikariConfig().apply { jdbcUrl = My.url; username = My.user; password = My.password; driverClassName = KotsqlJdbcDriver::class.java.name; maximumPoolSize = 4 }
        HikariDataSource(config).use { pool ->
            val threads = Executors.newFixedThreadPool(16)
            val tasks = List(200) { i ->
                threads.submit {
                    pool.connection.use { conn ->
                        conn.autoCommit = false
                        conn.prepareStatement("INSERT INTO t(n) VALUES (?)").use { s -> s.setInt(1, i); s.executeUpdate() }
                        if (i % 2 == 0) conn.commit() else conn.rollback()
                    }
                }
            }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            threads.shutdown()
            pool.connection.use { conn -> conn.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM t").use { r -> r.next(); assertEquals(100, r.getInt(1)) } } }
        }
    }

    @Test fun flywayMigrations() {
        // A schema of its own, since Flyway won't start in one with tables of unknown origin, which takes the root user.
        My.root().use { root -> root.createStatement().use { it.execute("DROP DATABASE IF EXISTS fly") } }
        val flyway = Flyway.configure().dataSource(KotsqlDataSource("${My.url}?sslmode=require", "root", My.PASSWORD))
            .schemas("fly").locations("classpath:db/mysql").load()
        assertEquals(1, flyway.migrate().migrationsExecuted)
        assertEquals(0, flyway.migrate().migrationsExecuted)
        My.root().use { root -> root.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM fly.author").use { r -> r.next(); assertEquals(1, r.getInt(1)) } } }
    }

    @Test fun jooqQueries() {
        val db = DSL.using(c, SQLDialect.MYSQL)
        db.execute("DROP TABLE IF EXISTS jq")
        db.createTable("jq").column("id", org.jooq.impl.SQLDataType.BIGINT.identity(true)).column("name", org.jooq.impl.SQLDataType.VARCHAR(50)).primaryKey("id").execute()
        val name = DSL.field("name", String::class.java)
        val id = DSL.field("id", Long::class.javaObjectType)
        db.insertInto(DSL.table("jq")).columns(name).values("a").values("b").execute()
        assertEquals(listOf("a", "b"), db.select(name).from("jq").orderBy(id).fetch(name))
        db.transaction { tx -> tx.dsl().deleteFrom(DSL.table("jq")).where(name.eq("a")).execute() }
        assertEquals(1, db.fetchCount(DSL.table("jq")))
    }
}
