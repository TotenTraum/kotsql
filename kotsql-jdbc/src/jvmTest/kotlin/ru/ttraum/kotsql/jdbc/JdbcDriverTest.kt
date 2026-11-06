package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.sqlErrorDetails
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

enum class TestMood { SAD, HAPPY }

class JdbcDriverTest {
    private lateinit var c: Connection

    @BeforeEach fun open() {
        c = DriverManager.getConnection(Pg.url, Pg.user, Pg.password)
        c.createStatement().use { it.execute("DROP TABLE IF EXISTS t; CREATE TABLE t(id serial PRIMARY KEY, v text UNIQUE, n int)") }
    }
    @AfterEach fun close() = c.close()

    @Test fun discoveredByDriverManagerAndRejectsForeignUrls() {
        assertTrue(DriverManager.getDriver(Pg.url) is KotsqlJdbcDriver)
        assertNull(KotsqlJdbcDriver().connect("jdbc:other://x", Properties()))
        assertFailsWith<SQLException> { DriverManager.getConnection("jdbc:kotsql:nosuchdb://localhost/x") }
    }

    @Test fun selectsAllCommonTypes() {
        val id = UUID.randomUUID()
        c.createStatement().use { s ->
            s.executeQuery(
                """SELECT 1::smallint, 2::int, 3::bigint, 1.5::real, 2.5::double precision, 10.25::numeric(5,2), 'x'::text,
                   true, '\xCAFE'::bytea, DATE '2024-02-29', TIME '13:14:15.5', TIMESTAMP '2024-01-02 03:04:05.123456',
                   TIMESTAMPTZ '2024-01-02 03:04:05+03', '$id'::uuid, '{"a":1}'::jsonb, ARRAY[1,2]::int[], NULL::int""",
            ).use { r ->
                assertTrue(r.next())
                assertEquals(1, r.getObject(1)); assertEquals(1.toShort(), r.getShort(1))
                assertEquals(2, r.getInt(2)); assertEquals(3L, r.getLong(3))
                assertEquals(1.5f, r.getFloat(4)); assertEquals(2.5, r.getDouble(5))
                assertEquals(BigDecimal("10.25"), r.getBigDecimal(6)); assertEquals("x", r.getString(7))
                assertTrue(r.getBoolean(8)); assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), r.getBytes(9))
                assertEquals(Date.valueOf("2024-02-29"), r.getDate(10))
                assertEquals(LocalDate.of(2024, 2, 29), r.getObject(10, LocalDate::class.java))
                assertEquals("13:14:15", r.getTime(11).toString())
                assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123456000), r.getObject(12, LocalDateTime::class.java))
                assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123456"), r.getTimestamp(12))
                assertEquals(OffsetDateTime.of(2024, 1, 2, 0, 4, 5, 0, ZoneOffset.UTC).toInstant(), r.getObject(13, OffsetDateTime::class.java).toInstant())
                assertEquals(id, r.getObject(14)); assertEquals("{\"a\": 1}", r.getString(15))
                assertEquals(listOf<Any?>(1, 2), (r.getArray(16).array as Array<*>).toList())
                assertEquals(0, r.getInt(17)); assertTrue(r.wasNull()); assertNull(r.getObject(17))
                assertFalse(r.next())
                val md = r.metaData
                assertEquals(17, md.columnCount); assertEquals(Types.NUMERIC, md.getColumnType(6))
                assertEquals(5, md.getPrecision(6)); assertEquals(2, md.getScale(6)); assertEquals("int4", md.getColumnTypeName(2))
            }
        }
    }

    @Test fun preparedStatementRoundTripsTypes() {
        c.createStatement().use { it.execute(
            "CREATE TEMP TABLE p(a smallint, b int, c bigint, d real, e float8, f numeric, g text, h bool, i bytea, j date, k time, l timestamp, m timestamptz, o uuid, q int[])",
        ) }
        val id = UUID.randomUUID()
        val at = Timestamp.valueOf("2021-06-07 08:09:10.5")
        c.prepareStatement("INSERT INTO p VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setShort(1, 1); s.setInt(2, 2); s.setLong(3, 3); s.setFloat(4, 1.5f); s.setDouble(5, 2.5)
            s.setBigDecimal(6, BigDecimal("3.14")); s.setString(7, "it's \"q\" ?"); s.setBoolean(8, true); s.setBytes(9, byteArrayOf(0, 1, -1))
            s.setDate(10, Date.valueOf("2021-06-07")); s.setTime(11, java.sql.Time.valueOf("08:09:10")); s.setTimestamp(12, at); s.setTimestamp(13, at)
            s.setObject(14, id); s.setArray(15, c.createArrayOf("int4", arrayOf(1, null, 3)))
            assertEquals(1, s.executeUpdate())
        }
        c.createStatement().use { s: Statement ->
            s.executeQuery("SELECT * FROM p").use { r ->
                assertTrue(r.next())
                assertEquals(BigDecimal("3.14"), r.getBigDecimal("f")); assertEquals("it's \"q\" ?", r.getString("g"))
                assertContentEquals(byteArrayOf(0, 1, -1), r.getBytes("i"))
                // A java.sql.Timestamp keeps both its wall clock and its instant.
                assertEquals(at, r.getTimestamp("l")); assertEquals(at, r.getTimestamp("m"))
                assertEquals(id, r.getObject("o", UUID::class.java))
                assertEquals(listOf<Any?>(1, null, 3), (r.getArray("q").array as Array<*>).toList())
            }
        }
    }

    @Test fun nullParametersFitAnyColumn() {
        c.prepareStatement("INSERT INTO t(v, n) VALUES (?, ?)").use { s ->
            s.setNull(1, Types.VARCHAR); s.setNull(2, Types.INTEGER)
            s.executeUpdate()
        }
        c.prepareStatement("SELECT n FROM t WHERE v IS NOT DISTINCT FROM ?").use { s ->
            s.setObject(1, null)
            s.executeQuery().use { r -> assertTrue(r.next()); assertNull(r.getObject(1)) }
        }
    }

    @Test fun placeholdersAreOnlyRecognizedInCode() {
        c.prepareStatement("""SELECT '?', "n" AS "a?", $$?$$, ? /* ? */ , E'\'?' -- ?
            FROM (SELECT 1 AS n) x""").use { s ->
            assertEquals(1, s.parameterMetaData.parameterCount)
            s.setInt(1, 5)
            s.executeQuery().use { r -> r.next(); assertEquals("?", r.getString(1)); assertEquals("5", r.getString(4)); assertEquals("'?", r.getString(5)) }
        }
        c.prepareStatement("SELECT '{\"a\":1}'::jsonb ?? ?").use { s ->
            s.setString(1, "a")
            s.executeQuery().use { r -> r.next(); assertTrue(r.getBoolean(1)) }
        }
        c.prepareStatement("SELECT ?, ?").use { s ->
            s.setInt(1, 1)
            assertFailsWith<SQLException> { s.setInt(3, 1) }
            assertEquals("07002", assertFailsWith<SQLException> { s.executeQuery() }.sqlState)
        }
    }

    @Test fun generatedKeys() {
        c.prepareStatement("INSERT INTO t(v) VALUES (?), (?)", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setString(1, "a"); s.setString(2, "b")
            assertEquals(2, s.executeUpdate())
            s.generatedKeys.use { k ->
                assertTrue(k.next()); val first = k.getInt("id")
                assertTrue(k.next()); assertEquals(first + 1, k.getInt(1))
                assertFalse(k.next())
            }
        }
        c.createStatement().use { s ->
            assertEquals(1, s.executeUpdate("INSERT INTO t(v) VALUES ('c')", arrayOf("v")))
            s.generatedKeys.use { k -> assertTrue(k.next()); assertEquals("c", k.getString(1)) }
        }
    }

    @Test fun queriesKeepTheirRowsWhenAskedForGeneratedKeys() {
        c.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.executeQuery().use { r -> assertTrue(r.next()); assertEquals(1, r.getInt(1)) }
        }
        c.createStatement().use { s ->
            assertTrue(s.execute("SELECT 2", Statement.RETURN_GENERATED_KEYS))
            s.resultSet.use { r -> assertTrue(r.next()); assertEquals(2, r.getInt(1)) }
        }
    }

    @Test fun scrollableResultSets() {
        c.createStatement(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, java.sql.ResultSet.CONCUR_READ_ONLY).use { s ->
            s.executeQuery("SELECT generate_series(1, 5)").use { r ->
                assertEquals(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, r.type)
                assertTrue(r.isBeforeFirst)
                assertTrue(r.last()); assertEquals(5, r.getInt(1)); assertEquals(5, r.row); assertTrue(r.isLast)
                assertTrue(r.first()); assertEquals(1, r.getInt(1))
                assertTrue(r.absolute(3)); assertEquals(3, r.getInt(1))
                assertTrue(r.relative(-1)); assertEquals(2, r.getInt(1))
                assertTrue(r.previous()); assertEquals(1, r.getInt(1))
                assertFalse(r.previous()); assertTrue(r.isBeforeFirst)
                assertTrue(r.absolute(-1)); assertEquals(5, r.getInt(1))
                assertFalse(r.next()); assertTrue(r.isAfterLast)
                assertFailsWith<SQLException> { r.getInt(1) }
                r.beforeFirst()
                var n = 0
                while (r.next()) n++
                assertEquals(5, n)
            }
        }
        c.prepareStatement("SELECT generate_series(1, 5)", java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, java.sql.ResultSet.CONCUR_READ_ONLY).use { p ->
            p.maxRows = 2
            p.executeQuery().use { r -> assertTrue(r.last()); assertEquals(2, r.getInt(1)) }
        }
        // Empty, and forward-only refusing to go back.
        c.createStatement(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, java.sql.ResultSet.CONCUR_READ_ONLY).use { s ->
            s.executeQuery("SELECT 1 WHERE false").use { r -> assertFalse(r.first()); assertFalse(r.isBeforeFirst); assertFalse(r.next()) }
        }
        c.createStatement().use { s -> s.executeQuery("SELECT 1").use { r -> assertFailsWith<SQLException> { r.first() } } }
    }

    @Test fun clobsAndBlobs() {
        c.createStatement().use { it.execute("CREATE TEMP TABLE lobs(a text, b bytea)") }
        val clob = c.createClob().apply { setString(1, "héllo") }
        val blob = c.createBlob().apply { setBytes(1, byteArrayOf(1, 2, 3)) }
        c.prepareStatement("INSERT INTO lobs VALUES (?, ?)").use { p -> p.setClob(1, clob); p.setBlob(2, blob); p.executeUpdate() }
        c.createStatement().use { s -> s.executeQuery("SELECT * FROM lobs").use { r ->
            r.next()
            val a = r.getClob(1); val b = r.getBlob(2)
            assertEquals("héllo", a.getSubString(1, a.length().toInt())); assertEquals(5, a.length()); assertEquals(2, a.position("él", 1))
            assertEquals("ll", a.getSubString(3, 2)); assertEquals(-1, a.position("nope", 1))
            assertContentEquals(byteArrayOf(2, 3), b.getBytes(2, 5)); assertEquals(3, b.length()); assertEquals(2, b.position(byteArrayOf(2), 1))
            a.free()
            assertFailsWith<SQLException> { a.length() }
        } }
    }

    @Test fun errorDetailsThroughJdbc() {
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO t(v) VALUES ('a')")
            val e = assertFailsWith<SQLIntegrityConstraintViolationException> { s.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
            assertEquals(ru.ttraum.kotsql.ConstraintViolation.UNIQUE, e.sqlErrorDetails()?.violation); assertEquals("t_v_key", e.sqlErrorDetails()?.constraint)
        }
        c.prepareStatement("INSERT INTO t(v) VALUES (?)").use { p ->
            p.setString(1, "a"); p.addBatch()
            val e = assertFailsWith<BatchUpdateException> { p.executeBatch() }
            assertEquals("t_v_key", e.sqlErrorDetails()?.constraint)
        }
    }

    @Test fun codecsThroughJdbc() {
        val source = KotsqlDataSource(Pg.url, Pg.user, Pg.password, ru.ttraum.kotsql.CodecRegistry(ru.ttraum.kotsql.SqlCodec.enum<TestMood>("test_mood")))
        source.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP TYPE IF EXISTS test_mood CASCADE; CREATE TYPE test_mood AS ENUM ('SAD', 'HAPPY'); CREATE TEMP TABLE m(v test_mood, a test_mood[])") }
            conn.prepareStatement("INSERT INTO m VALUES (?, ?)").use { s ->
                s.setObject(1, TestMood.HAPPY); s.setObject(2, listOf(TestMood.SAD)); s.executeUpdate()
            }
            conn.prepareStatement("SELECT v, a, v::text FROM m WHERE v = ?").use { s ->
                s.setObject(1, TestMood.HAPPY)
                s.executeQuery().use { r ->
                    assertTrue(r.next())
                    assertEquals(TestMood.HAPPY, r.getObject(1)); assertEquals(TestMood.HAPPY, r.getObject(1, TestMood::class.java))
                    assertEquals("HAPPY", r.getString(1)); assertEquals("test_mood", r.metaData.getColumnTypeName(1))
                    assertEquals(listOf<Any?>(TestMood.SAD), (r.getArray(2).array as Array<*>).toList())
                    assertEquals(TestMood.HAPPY, r.getObject(3, TestMood::class.java)) // from text, by the class asked for
                }
            }
        }
        // With no codec, the same column is its text, as with any other type the driver doesn't know.
        c.createStatement().use { s -> s.executeQuery("SELECT 'HAPPY'::test_mood").use { r -> r.next(); assertEquals("HAPPY", r.getObject(1)) } }
        // An enum goes by name without any codec, and other classes are refused.
        c.prepareStatement("SELECT ?").use { s -> s.setObject(1, TestMood.SAD); s.executeQuery().use { r -> r.next(); assertEquals("SAD", r.getString(1)) } }
        c.prepareStatement("SELECT ?").use { s -> assertFailsWith<SQLException> { s.setObject(1, Object()) } }
    }

    @Test fun batches() {
        c.prepareStatement("INSERT INTO t(v, n) VALUES (?, ?)").use { s ->
            repeat(1000) { s.setString(1, "v$it"); if (it % 2 == 0) s.setInt(2, it) else s.setNull(2, Types.INTEGER); s.addBatch() }
            val counts = s.executeBatch()
            assertEquals(1000, counts.size); assertTrue(counts.all { it == 1 })
        }
        c.createStatement().use { s ->
            s.addBatch("UPDATE t SET n = 0 WHERE v = 'v1'"); s.addBatch("DELETE FROM t WHERE v LIKE 'v2%'")
            assertEquals(111, s.executeBatch()[1])
            s.executeQuery("SELECT count(*) FROM t").use { r -> r.next(); assertEquals(889, r.getInt(1)) }
        }
    }

    @Test fun failedBatchIsAtomicAndReportsCounts() {
        c.prepareStatement("INSERT INTO t(v) VALUES (?)").use { s ->
            listOf("a", "b", "a", "c").forEach { s.setString(1, it); s.addBatch() }
            val e = assertFailsWith<BatchUpdateException> { s.executeBatch() }
            assertEquals("23505", e.sqlState); assertEquals(2, e.updateCounts.size)
        }
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM t").use { r -> r.next(); assertEquals(0, r.getInt(1)) } }
    }

    @Test fun transactionsAndSavepoints() {
        val other = DriverManager.getConnection(Pg.url, Pg.user, Pg.password)
        fun count() = other.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM t").use { r -> r.next(); r.getInt(1) } }
        c.autoCommit = false
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
        assertEquals(0, count())
        c.commit()
        assertEquals(1, count())
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('b')") }
        c.rollback()
        assertEquals(1, count())

        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('c')") }
        val sp = c.setSavepoint()
        c.createStatement().use { it.executeUpdate("INSERT INTO t(v) VALUES ('d')") }
        c.rollback(sp)
        c.commit()
        other.createStatement().use { s -> s.executeQuery("SELECT v FROM t ORDER BY v").use { r ->
            assertTrue(r.next()); assertEquals("a", r.getString(1)); assertTrue(r.next()); assertEquals("c", r.getString(1)); assertFalse(r.next())
        } }

        // An error aborts the transaction until rollback.
        assertFailsWith<SQLSyntaxErrorException> { c.createStatement().use { it.execute("SELEC") } }
        assertEquals("25P02", assertFailsWith<SQLException> { c.createStatement().use { it.execute("SELECT 1") } }.sqlState)
        c.rollback()
        c.createStatement().use { assertTrue(it.execute("SELECT 1")) }
        c.autoCommit = true
        assertFailsWith<SQLException> { c.commit() }

        for (level in listOf(Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_COMMITTED)) {
            c.transactionIsolation = level
            assertEquals(level, c.transactionIsolation)
        }
        other.close()
    }

    @Test fun mapsErrorsToSqlExceptionSubclasses() {
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO t(v) VALUES ('a')")
            val e = assertFailsWith<SQLIntegrityConstraintViolationException> { s.executeUpdate("INSERT INTO t(v) VALUES ('a')") }
            assertEquals("23505", e.sqlState)
            assertTrue(e.message!!.contains("unique"))
            assertFailsWith<SQLSyntaxErrorException> { s.executeQuery("SELECT * FROM no_such_table") }
            // The connection is still usable.
            s.executeQuery("SELECT 1").use { r -> assertTrue(r.next()) }
        }
        assertFailsWith<SQLFeatureNotSupportedException> { c.createStatement(java.sql.ResultSet.TYPE_SCROLL_SENSITIVE, java.sql.ResultSet.CONCUR_READ_ONLY) }
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

    @Test fun nestedQueriesWhileIterating() {
        for (fetch in listOf(0, 3)) c.createStatement().use { outer ->
            outer.fetchSize = fetch
            outer.executeQuery("SELECT generate_series(1, 10)").use { r ->
                var sum = 0
                while (r.next()) {
                    c.prepareStatement("SELECT ?::int * 2").use { inner ->
                        inner.setInt(1, r.getInt(1))
                        inner.executeQuery().use { i -> i.next(); sum += i.getInt(1) }
                    }
                }
                assertEquals(110, sum)
            }
        }
    }

    @Test fun maxRowsAndEarlyClose() {
        c.createStatement().use { s ->
            s.maxRows = 3
            s.executeQuery("SELECT generate_series(1, 100)").use { r ->
                var n = 0; while (r.next()) n++
                assertEquals(3, n)
            }
        }
        c.createStatement().use { s ->
            s.fetchSize = 10
            s.executeQuery("SELECT generate_series(1, 100000)").use { r -> r.next() }
            s.close()
            s.isClosed.let(::assertTrue)
        }
        c.createStatement().use { s -> s.executeQuery("SELECT 1").use { r -> assertTrue(r.next()) } }
    }

    @Test fun queryTimeoutAndCancel() {
        c.createStatement().use { s ->
            s.queryTimeout = 1
            val e = assertFailsWith<SQLTimeoutException> { s.execute("SELECT pg_sleep(30)") }
            assertEquals("57014", e.sqlState)
        }
        c.createStatement().use { s ->
            thread { Thread.sleep(300); s.cancel() }
            assertFailsWith<SQLException> { s.execute("SELECT pg_sleep(30)") }
        }
        assertTrue(c.isValid(2))
    }

    @Test fun databaseMetaData() {
        c.createStatement().use { it.execute(
            """DROP TABLE IF EXISTS md_child; DROP TABLE IF EXISTS md_parent;
               CREATE TABLE md_parent(id serial PRIMARY KEY, name varchar(20) NOT NULL DEFAULT 'x', amount numeric(8,3));
               CREATE TABLE md_child(id int PRIMARY KEY, parent_id int REFERENCES md_parent(id) ON DELETE CASCADE);
               CREATE UNIQUE INDEX md_name ON md_parent(name);""",
        ) }
        val md = c.metaData
        assertEquals("PostgreSQL", md.databaseProductName); assertTrue(md.databaseMajorVersion >= 17)
        assertTrue(md.supportsTransactions())
        md.getTables(null, "public", "md_%", arrayOf("TABLE")).use { r ->
            assertTrue(r.next()); assertEquals("md_child", r.getString("TABLE_NAME")); assertEquals("TABLE", r.getString("TABLE_TYPE"))
            assertTrue(r.next()); assertEquals("md_parent", r.getString(3)); assertFalse(r.next())
        }
        md.getColumns(null, "public", "md_parent", null).use { r ->
            assertTrue(r.next()); assertEquals("id", r.getString("COLUMN_NAME")); assertEquals(Types.INTEGER, r.getInt("DATA_TYPE")); assertEquals("YES", r.getString("IS_AUTOINCREMENT"))
            assertTrue(r.next()); assertEquals("name", r.getString(4)); assertEquals(20, r.getInt("COLUMN_SIZE")); assertEquals("NO", r.getString("IS_NULLABLE")); assertEquals("'x'::character varying", r.getString("COLUMN_DEF"))
            assertTrue(r.next()); assertEquals(8, r.getInt("COLUMN_SIZE")); assertEquals(3, r.getInt("DECIMAL_DIGITS")); assertFalse(r.next())
        }
        md.getPrimaryKeys(null, "public", "md_parent").use { r -> assertTrue(r.next()); assertEquals("id", r.getString("COLUMN_NAME")); assertEquals(1, r.getInt("KEY_SEQ")) }
        md.getImportedKeys(null, "public", "md_child").use { r ->
            assertTrue(r.next()); assertEquals("md_parent", r.getString("PKTABLE_NAME")); assertEquals("parent_id", r.getString("FKCOLUMN_NAME"))
            assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, r.getInt("DELETE_RULE"))
        }
        md.getExportedKeys(null, "public", "md_parent").use { r -> assertTrue(r.next()); assertEquals("md_child", r.getString("FKTABLE_NAME")) }
        md.getIndexInfo(null, "public", "md_parent", true, false).use { r ->
            val names = buildSet { while (r.next()) add(r.getString("INDEX_NAME")) }
            assertTrue("md_name" in names, names.toString())
        }
        md.getSchemas().use { r -> val s = buildSet { while (r.next()) add(r.getString(1)) }; assertTrue("public" in s) }
        md.catalogs.use { r -> assertTrue(r.next()) }
        md.tableTypes.use { r -> assertTrue(r.next()) }
        md.typeInfo.use { r -> assertTrue(r.next()) }
        // Metadata calls don't disturb a transaction.
        assertNotNull(c.schema); assertNotNull(c.catalog)
    }

    @Test fun authenticationFailures() {
        assertFailsWith<SQLInvalidAuthorizationSpecException> { DriverManager.getConnection(Pg.url, Pg.user, "wrong") }
    }

    @Test fun scramUser() {
        c.createStatement().use { s ->
            s.execute("DROP ROLE IF EXISTS u_scram; SET password_encryption = 'scram-sha-256'; CREATE ROLE u_scram LOGIN PASSWORD 'pä ss'")
        }
        DriverManager.getConnection(Pg.url, "u_scram", "pä ss").use { assertTrue(it.isValid(1)) }
        assertFailsWith<SQLInvalidAuthorizationSpecException> { DriverManager.getConnection(Pg.url, "u_scram", "nope") }
    }

    @Test fun tlsModes() {
        val props = { mode: String -> Properties().apply { put("user", Pg.user); put("password", Pg.password); put("sslmode", mode) } }
        fun sslInUse(conn: Connection) = conn.createStatement().use { s ->
            s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").use { r -> r.next(); r.getBoolean(1) }
        }
        DriverManager.getConnection(Pg.url, props("disable")).use { assertFalse(sslInUse(it)) }
        DriverManager.getConnection(Pg.url, props("prefer")).use { assertTrue(sslInUse(it)) }
        DriverManager.getConnection(Pg.url, props("require")).use { assertTrue(sslInUse(it)) }
        // The self-signed certificate isn't trusted by default, so verification must refuse it.
        assertFailsWith<SQLException> { DriverManager.getConnection(Pg.url, props("verify-ca")) }
        assertFailsWith<SQLException> { DriverManager.getConnection(Pg.url, props("verify-full")) }

        // Trusting it explicitly satisfies verify-ca. The certificate names only "localhost", so verify-full accepts
        // that host and refuses the same server reached as 127.0.0.1.
        val cert = java.nio.file.Files.createTempFile("pg", ".pem").toFile()
        Pg.container.copyFileFromContainer("/etc/ssl/certs/ssl-cert-snakeoil.pem", cert.path)
        val pinned = { mode: String -> props(mode).apply { put("sslrootcert", cert.path) } }
        val port = Pg.container.firstMappedPort
        val db = Pg.container.databaseName
        val byName = "jdbc:kotsql:postgresql://localhost:$port/$db"
        val byIp = "jdbc:kotsql:postgresql://127.0.0.1:$port/$db"
        DriverManager.getConnection(byIp, pinned("verify-ca")).use { assertTrue(sslInUse(it)) }
        DriverManager.getConnection(byName, pinned("verify-full")).use { assertTrue(sslInUse(it)) }
        assertFailsWith<SQLException> { DriverManager.getConnection(byIp, pinned("verify-full")) }
        val missing = props("verify-full").apply { put("sslrootcert", "/no/such/file.pem") }
        assertTrue("/no/such/file.pem" in assertFailsWith<SQLException> { DriverManager.getConnection(byName, missing) }.message!!)
    }

    @Test fun cancelWorksOverTls() {
        val props = Properties().apply { put("user", Pg.user); put("password", Pg.password); put("sslmode", "require") }
        DriverManager.getConnection(Pg.url, props).use { conn ->
            conn.createStatement().use { s ->
                s.queryTimeout = 1
                assertFailsWith<SQLTimeoutException> { s.execute("SELECT pg_sleep(30)") }
            }
        }
    }

    @Test fun closedObjectsReportIt() {
        val s = c.createStatement()
        val r = s.executeQuery("SELECT 1")
        s.close()
        assertFailsWith<SQLException> { r.next() }
        assertFailsWith<SQLException> { s.execute("SELECT 1") }
        c.close()
        assertTrue(c.isClosed); assertFalse(c.isValid(1))
        assertFailsWith<SQLException> { c.createStatement() }
    }

    @Test fun sharedConnectionIsThreadSafe() {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        List(8) { t ->
            thread {
                try {
                    repeat(100) { i ->
                        c.prepareStatement("SELECT ?::int").use { s ->
                            s.setInt(1, t * 1000 + i)
                            s.executeQuery().use { r -> r.next(); assertEquals(t * 1000 + i, r.getInt(1)) }
                        }
                    }
                } catch (e: Throwable) { errors += e }
            }
        }.forEach { it.join() }
        assertTrue(errors.isEmpty(), errors.toString())
    }
}
