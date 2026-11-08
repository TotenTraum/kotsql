package ru.ttraum.kotsql.jdbc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLSyntaxErrorException
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteJdbcTest {
    private val file = Files.createTempFile("kotsql", ".db")
    private lateinit var c: Connection

    @BeforeEach fun open() {
        c = DriverManager.getConnection("jdbc:kotsql:sqlite:///$file")
        c.createStatement().use { it.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, name TEXT NOT NULL, price NUMERIC, made DATE, at TIMESTAMP, ok BOOLEAN, data BLOB)") }
    }

    @AfterEach fun close() {
        c.close()
        Files.deleteIfExists(file)
    }

    @Test fun roundTripOfTypes() {
        c.prepareStatement("INSERT INTO item (id, name, price, made, at, ok, data) VALUES (?, ?, ?, ?, ?, ?, ?)").use { p ->
            p.setInt(1, 1)
            p.setString(2, "юникод")
            p.setBigDecimal(3, BigDecimal("1.50"))
            p.setDate(4, Date.valueOf("2024-02-03"))
            p.setTimestamp(5, Timestamp.valueOf("2024-02-03 04:05:06.5"))
            p.setBoolean(6, true)
            p.setBytes(7, byteArrayOf(1, 2))
            assertEquals(1, p.executeUpdate())
        }
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, name, price, made, at, ok, data FROM item").use { r ->
                assertTrue(r.next())
                assertEquals(1, r.getInt("id"))
                assertEquals(1L, r.getLong(1))
                assertEquals("юникод", r.getString(2))
                assertEquals(0, BigDecimal("1.5").compareTo(r.getBigDecimal(3)))
                assertEquals(1.5, r.getDouble(3))
                assertEquals(Date.valueOf("2024-02-03"), r.getDate(4))
                assertEquals(Timestamp.valueOf("2024-02-03 04:05:06.5"), r.getTimestamp(5))
                assertTrue(r.getBoolean(6))
                assertContentEquals(byteArrayOf(1, 2), r.getBytes(7))
                assertEquals("INTEGER", r.metaData.getColumnTypeName(1))
                assertEquals(Types.INTEGER, r.metaData.getColumnType(1))
                assertEquals(1, r.getObject(1)) // an Integer, as other SQLite drivers give it
                assertFalse(r.next())
            }
            // A larger one stays a Long, also further down a column whose first value fits.
            s.executeQuery("SELECT 1 UNION ALL SELECT 5000000000").use { r ->
                assertTrue(r.next()); assertEquals(1, r.getObject(1))
                assertTrue(r.next()); assertEquals(5_000_000_000L, r.getObject(1))
            }
        }
    }

    @Test fun nulls() {
        c.prepareStatement("INSERT INTO item (id, name, price) VALUES (?, ?, ?)").use { p ->
            p.setInt(1, 1)
            p.setString(2, "x")
            p.setNull(3, Types.NUMERIC)
            p.executeUpdate()
        }
        c.createStatement().use { s ->
            s.executeQuery("SELECT price FROM item").use { r ->
                r.next()
                assertNull(r.getBigDecimal(1))
                assertTrue(r.wasNull())
                assertEquals(0, r.getInt(1))
            }
        }
    }

    @Test fun generatedKeys() {
        c.prepareStatement("INSERT INTO item (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS).use { p ->
            p.setString(1, "a")
            p.executeUpdate()
            p.generatedKeys.use { k -> assertTrue(k.next()); assertEquals(1L, k.getLong(1)) }
            p.setString(1, "b")
            p.executeUpdate()
            p.generatedKeys.use { k -> assertTrue(k.next()); assertEquals(2L, k.getLong(1)) }
        }
    }

    @Test fun transactions() {
        c.autoCommit = false
        c.createStatement().use { it.executeUpdate("INSERT INTO item (id, name) VALUES (1, 'a')") }
        c.rollback()
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM item").use { it.next(); assertEquals(0, it.getInt(1)) } }
        c.createStatement().use { it.executeUpdate("INSERT INTO item (id, name) VALUES (2, 'b')") }
        val point = c.setSavepoint()
        c.createStatement().use { it.executeUpdate("INSERT INTO item (id, name) VALUES (3, 'c')") }
        c.rollback(point)
        c.commit()
        c.autoCommit = true
        c.createStatement().use { s -> s.executeQuery("SELECT id FROM item").use { r -> r.next(); assertEquals(2, r.getInt(1)); assertFalse(r.next()) } }
    }

    @Test fun errorsAndBatches() {
        c.createStatement().use { s ->
            assertFailsWith<SQLIntegrityConstraintViolationException> { s.executeUpdate("INSERT INTO item (id) VALUES (1)") }
            assertFailsWith<SQLSyntaxErrorException> { s.executeQuery("SELEC 1") }
        }
        c.prepareStatement("INSERT INTO item (id, name) VALUES (?, 'x')").use { p ->
            p.setInt(1, 1); p.addBatch()
            p.setInt(1, 2); p.addBatch()
            assertContentEquals(intArrayOf(1, 1), p.executeBatch())
            p.setInt(1, 3); p.addBatch()
            p.setInt(1, 3); p.addBatch()
            assertFailsWith<BatchUpdateException> { p.executeBatch() }
        }
        c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM item").use { it.next(); assertEquals(2, it.getInt(1)) } }
    }

    @Test fun updatableResultSet() {
        c.createStatement().use { it.executeUpdate("INSERT INTO item (id, name) VALUES (1, 'a'), (2, 'b')") }
        c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE).use { s ->
            s.executeQuery("SELECT id, name FROM item ORDER BY id").use { r ->
                r.next()
                r.updateString("name", "A")
                r.updateRow()
                assertEquals("A", r.getString(2))
                r.next()
                r.deleteRow()
            }
        }
        c.createStatement().use { s -> s.executeQuery("SELECT name FROM item").use { r -> r.next(); assertEquals("A", r.getString(1)); assertFalse(r.next()) } }
    }

    @Test fun metadata() {
        c.createStatement().use { it.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, item_id INTEGER REFERENCES item(id) ON DELETE CASCADE, code TEXT UNIQUE)") }
        val meta = c.metaData
        assertEquals("SQLite", meta.databaseProductName)
        meta.getTables(null, null, "it%", null).use { r -> r.next(); assertEquals("item", r.getString("TABLE_NAME")); assertEquals("TABLE", r.getString("TABLE_TYPE")); assertFalse(r.next()) }
        meta.getColumns(null, null, "item", "name").use { r ->
            r.next()
            assertEquals("name", r.getString("COLUMN_NAME"))
            assertEquals(Types.VARCHAR, r.getInt("DATA_TYPE"))
            assertEquals("NO", r.getString("IS_NULLABLE"))
            assertEquals(2, r.getInt("ORDINAL_POSITION"))
        }
        meta.getPrimaryKeys(null, null, "item").use { r -> r.next(); assertEquals("id", r.getString("COLUMN_NAME")); assertFalse(r.next()) }
        meta.getImportedKeys(null, null, "child").use { r ->
            r.next()
            assertEquals("item", r.getString("PKTABLE_NAME"))
            assertEquals("item_id", r.getString("FKCOLUMN_NAME"))
            assertEquals(java.sql.DatabaseMetaData.importedKeyCascade, r.getInt("DELETE_RULE"))
        }
        meta.getExportedKeys(null, null, "item").use { r -> r.next(); assertEquals("child", r.getString("FKTABLE_NAME")) }
        meta.getIndexInfo(null, null, "child", true, false).use { r -> r.next(); assertEquals("code", r.getString("COLUMN_NAME")) }
    }

    @Test fun escapes() {
        c.createStatement().use { s ->
            s.executeQuery("SELECT {fn UCASE('abc')}, {d '2024-02-03'}").use { r -> r.next(); assertEquals("ABC", r.getString(1)); assertEquals(Date.valueOf("2024-02-03"), r.getDate(2)) }
        }
        assertEquals(Time.valueOf("04:05:06"), c.createStatement().use { s -> s.executeQuery("SELECT '04:05:06'").use { r -> r.next(); r.getTime(1) } })
    }

    /** `$a$b` is a parameter here, where PostgreSQL would take `$a$` for the start of a quoted string. */
    @Test fun aNamedParameterIsNoDollarQuote() {
        c.prepareStatement("SELECT \$a\$b, {fn UCASE('x')}").use { p ->
            p.setInt(1, 1)
            p.executeQuery().use { r ->
                r.next()
                assertEquals(1, r.getInt(1))
                assertEquals("X", r.getString(2))
            }
        }
    }

    /** A bracket is a quote here, and what's in it no escape. */
    @Test fun aBracketedIdentifierIsNoEscape() {
        c.createStatement().use { s ->
            s.executeQuery("SELECT 1 AS [{fn UCASE('x')}]").use { r -> r.next(); assertEquals("{fn UCASE('x')}", r.metaData.getColumnName(1)) }
        }
    }

    /** SQLite has functions but no procedures, and saying so beats a syntax error about CALL. */
    @Test fun calls() {
        c.prepareCall("{? = call abs(?)}").use { call ->
            call.registerOutParameter(1, Types.INTEGER)
            call.setInt(2, -5)
            call.execute()
            assertEquals(5, call.getInt(1))
        }
        assertFailsWith<java.sql.SQLFeatureNotSupportedException> { c.prepareCall("{call anything()}") }
    }

    @Test fun isolationAndReadOnly() {
        assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.transactionIsolation)
        c.isReadOnly = true
        assertFailsWith<java.sql.SQLException> { c.createStatement().use { it.executeUpdate("INSERT INTO item (id, name) VALUES (1, 'a')") } }
        c.isReadOnly = false
        assertTrue(c.isValid(1))
    }
}
