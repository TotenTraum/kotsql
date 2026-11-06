package ru.ttraum.kotsql.jdbc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `CallableStatement`, updatable result sets and escapes, on PostgreSQL. `MysqlJdbcTest` has MySQL's. */
class JdbcFeaturesTest {
    private lateinit var c: Connection

    @BeforeEach fun open() {
        c = DriverManager.getConnection("${Pg.url}?sslmode=disable", Pg.user, Pg.password)
        c.createStatement().use { s ->
            s.execute("DROP TABLE IF EXISTS item")
            s.execute("CREATE TABLE item (id int PRIMARY KEY, name text, price numeric(8, 2) DEFAULT 1.50, made date)")
            s.execute("INSERT INTO item (id, name, price) VALUES (1, 'one', 10), (2, 'two', 20), (3, 'three', 30)")
            s.execute("DROP TABLE IF EXISTS nokey")
            s.execute("CREATE TABLE nokey (a int)")
            s.execute("INSERT INTO nokey VALUES (1)")
        }
    }

    @AfterEach fun close() = c.close()

    private fun Connection.scalar(sql: String): Any? = createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getObject(1) } }
    private fun updatable(sql: String = "SELECT id, name, price FROM item ORDER BY id"): ResultSet =
        c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE).executeQuery(sql)

    // ---- Escapes ----

    @Test fun escapes() {
        fun one(sql: String) = c.scalar(sql)
        assertEquals("ABC", one("SELECT {fn UCASE('abc')}"))
        assertEquals("abc", one("SELECT {fn lcase('ABC')}"))
        assertEquals("x", one("SELECT {fn IFNULL(NULL, 'x')}"))
        assertEquals(3, one("SELECT {fn LOCATE('c', 'abc')}"))
        assertEquals(2024.0, (one("SELECT {fn YEAR(DATE '2024-02-03')}") as Number).toDouble())
        assertEquals(java.sql.Date.valueOf("2024-02-03"), one("SELECT {d '2024-02-03'}"))
        assertEquals(java.sql.Timestamp.valueOf("2024-02-03 04:05:06"), one("SELECT {ts '2024-02-03 04:05:06'}"))
        assertEquals(java.sql.Time.valueOf("04:05:06"), one("SELECT {t '04:05:06'}"))
        assertEquals(true, one("SELECT 'a_b' LIKE 'a#_b' {escape '#'}"))
        // Inside each other, and with what only looks like one.
        assertEquals("AB", one("SELECT {fn UCASE({fn CONCAT('a', 'b')})}"))
        assertEquals("{fn UCASE('a')}", one("SELECT '{fn UCASE(''a'')}'"))
        assertEquals("{d}", one("""SELECT ${'$'}x${'$'}{d}${'$'}x${'$'}"""))
        assertEquals("a", one("SELECT /* {fn UCASE('a')} */ 'a' -- {d '2'}"))
        c.createStatement().use { s ->
            s.executeQuery("SELECT i.id, n.a FROM item i {oj LEFT OUTER JOIN nokey n ON n.a = i.id} ORDER BY i.id {limit 1}").use { r ->
                assertTrue(r.next())
                assertEquals(1, r.getInt(1))
                assertFalse(r.next())
            }
            s.setEscapeProcessing(false)
            assertFailsWith<SQLException> { s.executeQuery("SELECT {fn UCASE('a')}") }
        }
        c.prepareStatement("SELECT {fn UCASE(?)}").use { p ->
            p.setString(1, "abc")
            p.executeQuery().use { it.next(); assertEquals("ABC", it.getString(1)) }
        }
        assertEquals("SELECT UPPER('a')", c.nativeSQL("SELECT {fn UCASE('a')}"))
    }

    // ---- Calls ----

    @Test fun functionWithReturnValue() {
        c.createStatement().use { it.execute("CREATE OR REPLACE FUNCTION add_up(a int, b int) RETURNS int AS 'SELECT a + b' LANGUAGE sql") }
        c.prepareCall("{? = call add_up(?, ?)}").use { call ->
            call.registerOutParameter(1, Types.INTEGER)
            call.setInt(2, 40)
            call.setInt(3, 2)
            assertFalse(call.execute())
            assertEquals(42, call.getInt(1))
            assertFalse(call.wasNull())
            assertEquals("42", call.getString(1))
            assertEquals(42, call.getObject(1))
            // Again, with other values.
            call.setInt(2, 1)
            call.execute()
            assertEquals(3, call.getInt(1))
        }
    }

    @Test fun functionWithNullResult() {
        c.createStatement().use { it.execute("CREATE OR REPLACE FUNCTION nothing_here() RETURNS int AS 'SELECT NULL::int' LANGUAGE sql") }
        c.prepareCall("{? = call nothing_here()}").use { call ->
            call.registerOutParameter(1, Types.INTEGER)
            call.execute()
            assertEquals(0, call.getInt(1))
            assertTrue(call.wasNull())
        }
    }

    @Test fun functionWithOutParameters() {
        c.createStatement().use { it.execute("CREATE OR REPLACE FUNCTION two(IN a int, OUT b int, OUT c text) AS 'SELECT a + 1, a::text' LANGUAGE sql") }
        c.prepareCall("{call two(?, ?, ?)}").use { call ->
            call.setInt(1, 5)
            call.registerOutParameter(2, Types.INTEGER)
            call.registerOutParameter(3, Types.VARCHAR)
            call.execute()
            assertEquals(6, call.getInt(2))
            assertEquals("5", call.getString(3))
        }
    }

    @Test fun procedure() {
        c.createStatement().use {
            it.execute("""CREATE OR REPLACE PROCEDURE add_item(i int, n text) LANGUAGE sql AS ${'$'}${'$'} INSERT INTO item (id, name) VALUES (i, n) ${'$'}${'$'}""")
        }
        c.prepareCall("{call add_item(?, ?)}").use { call ->
            call.setInt(1, 9)
            call.setString(2, "nine")
            call.execute()
        }
        assertEquals("nine", c.scalar("SELECT name FROM item WHERE id = 9"))
    }

    @Test fun procedureWithOutAndInOutParameters() {
        c.createStatement().use {
            it.execute(
                """CREATE OR REPLACE PROCEDURE both_ways(a int, INOUT b int, OUT d text) LANGUAGE plpgsql AS ${'$'}${'$'}
                   BEGIN b := a + b; d := 'done'; END ${'$'}${'$'}""",
            )
        }
        c.prepareCall("{call both_ways(?, ?, ?)}").use { call ->
            call.setInt(1, 1)
            call.setInt(2, 2)
            call.registerOutParameter(2, Types.INTEGER)
            call.registerOutParameter(3, Types.VARCHAR)
            call.execute()
            assertEquals(3, call.getInt(2))
            assertEquals("done", call.getString(3))
        }
    }

    @Test fun setReturningFunctionKeepsItsRows() {
        c.createStatement().use { it.execute("CREATE OR REPLACE FUNCTION names() RETURNS SETOF text AS 'SELECT name FROM item ORDER BY id' LANGUAGE sql") }
        c.prepareCall("{call names()}").use { call ->
            assertTrue(call.execute())
            call.resultSet.use { r ->
                val names = buildList { while (r.next()) add(r.getString(1)) }
                assertEquals(listOf("one", "two", "three"), names)
            }
        }
    }

    @Test fun callMistakes() {
        c.createStatement().use { it.execute("CREATE OR REPLACE FUNCTION add_up(a int, b int) RETURNS int AS 'SELECT a + b' LANGUAGE sql") }
        assertFailsWith<SQLException> { c.prepareCall("SELECT 1") }
        c.prepareCall("{? = call add_up(?, ?)}").use { call ->
            call.setInt(2, 1)
            call.setInt(3, 1)
            assertFailsWith<SQLException> { call.execute() } // the return value isn't registered
            call.registerOutParameter(1, Types.INTEGER)
            assertFailsWith<SQLException> { call.getInt(1) } // not executed yet
            assertFailsWith<SQLException> { call.registerOutParameter(4, Types.INTEGER) }
            call.execute()
            assertFailsWith<SQLException> { call.getInt(2) } // not an out parameter
            assertFailsWith<java.sql.SQLFeatureNotSupportedException> { call.getInt("name") }
        }
        c.prepareCall("{? = call add_up(?, ?)}").use { call ->
            call.registerOutParameter(1, Types.INTEGER)
            call.setInt(2, 1)
            assertFailsWith<SQLException> { call.execute() } // a value is missing
        }
    }

    // ---- Updatable result sets ----

    @Test fun updatingARow() {
        updatable().use { r ->
            assertEquals(ResultSet.CONCUR_UPDATABLE, r.concurrency)
            assertTrue(r.next())
            r.updateString("name", "ONE")
            r.updateBigDecimal(3, BigDecimal("11.50"))
            assertEquals("ONE", r.getString(2)) // already, as seen from here
            r.updateRow()
            assertTrue(r.rowUpdated())
            assertEquals("ONE", r.getString(2))
            assertEquals(BigDecimal("11.50"), r.getBigDecimal(3))
            assertTrue(r.next())
            assertEquals("two", r.getString(2))
        }
        assertEquals("ONE", c.scalar("SELECT name FROM item WHERE id = 1"))
        assertEquals(BigDecimal("11.50"), c.scalar("SELECT price FROM item WHERE id = 1"))
        assertEquals("two", c.scalar("SELECT name FROM item WHERE id = 2"))
    }

    @Test fun changingAKeyAndNulls() {
        updatable().use { r ->
            r.next()
            r.updateInt("id", 100)
            r.updateNull("name")
            r.updateRow()
            assertEquals(100, r.getInt(1))
            assertNull(r.getString(2))
            assertTrue(r.wasNull())
        }
        assertEquals(1L, c.scalar("SELECT count(*) FROM item WHERE id = 100 AND name IS NULL"))
    }

    @Test fun movingOnDropsChanges() {
        updatable().use { r ->
            r.next()
            r.updateString(2, "dropped")
            r.next()
            r.updateString(2, "cancelled")
            r.cancelRowUpdates()
            assertEquals("two", r.getString(2))
            r.updateRow() // nothing to do
        }
        assertEquals(0L, c.scalar("SELECT count(*) FROM item WHERE name IN ('dropped', 'cancelled')"))
    }

    @Test fun deletingARow() {
        updatable().use { r ->
            r.next()
            r.next()
            r.deleteRow()
            assertTrue(r.rowDeleted())
            assertFailsWith<SQLException> { r.getString(2) }
            assertTrue(r.next())
            assertEquals("three", r.getString(2))
            assertFalse(r.next())
        }
        assertEquals(listOf(1L, 0L), listOf(c.scalar("SELECT count(*) FROM item WHERE id = 1"), c.scalar("SELECT count(*) FROM item WHERE id = 2")))
    }

    @Test fun insertingARow() {
        updatable().use { r ->
            r.next()
            r.moveToInsertRow()
            r.updateInt("id", 7)
            r.updateString("name", "seven")
            assertEquals("seven", r.getString("name"))
            r.insertRow()
            r.moveToCurrentRow()
            assertEquals("one", r.getString(2))
            assertFailsWith<SQLException> { r.insertRow() }
            assertTrue(r.next())
            assertEquals("two", r.getString(2))
        }
        // The default too, which the row didn't set.
        assertEquals(BigDecimal("1.50"), c.scalar("SELECT price FROM item WHERE id = 7"))
    }

    @Test fun refreshingARow() {
        updatable().use { r ->
            r.next()
            DriverManager.getConnection("${Pg.url}?sslmode=disable", Pg.user, Pg.password).use { other ->
                other.createStatement().use { it.execute("UPDATE item SET name = 'theirs' WHERE id = 1") }
            }
            assertEquals("one", r.getString(2))
            r.refreshRow()
            assertEquals("theirs", r.getString(2))
        }
    }

    @Test fun aRowThatHasGoneIsReported() {
        updatable().use { r ->
            r.next()
            DriverManager.getConnection("${Pg.url}?sslmode=disable", Pg.user, Pg.password).use { other ->
                other.createStatement().use { it.execute("DELETE FROM item WHERE id = 1") }
            }
            r.updateString(2, "x")
            assertFailsWith<SQLException> { r.updateRow() }
        }
    }

    @Test fun aPreparedStatementAndATransaction() {
        c.autoCommit = false
        c.prepareStatement("SELECT id, name FROM item WHERE id = ?", ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE).use { p ->
            p.setInt(1, 2)
            p.executeQuery().use { r ->
                r.next()
                r.updateString("name", "changed")
                r.updateRow()
            }
        }
        c.rollback()
        c.autoCommit = true
        assertEquals("two", c.scalar("SELECT name FROM item WHERE id = 2"))
    }

    @Test fun whatCantBeUpdated() {
        fun refused(sql: String) {
            updatable(sql).use { r ->
                r.next()
                assertFailsWith<SQLException>(sql) { r.updateString(1, "x"); r.updateRow() }
            }
        }
        // A join, an aggregate and so on say so from the start.
        val join = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)
        join.executeQuery("SELECT i.id FROM item i JOIN item j ON i.id = j.id").use { assertEquals(ResultSet.CONCUR_READ_ONLY, it.concurrency) }
        refused("SELECT i.id FROM item i JOIN item j ON i.id = j.id")
        refused("SELECT count(*) FROM item GROUP BY name")
        refused("SELECT 1")
        refused("SELECT a FROM nokey") // no primary key
        refused("SELECT name FROM item") // not selected
        c.createStatement().use { s -> s.executeQuery("SELECT id FROM item").use { r -> r.next(); assertFailsWith<SQLException> { r.updateInt(1, 1) } } }
    }

    @Test fun metadataSaysSo() {
        val meta = c.metaData
        assertTrue(meta.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE))
        assertTrue(meta.ownUpdatesAreVisible(ResultSet.TYPE_FORWARD_ONLY))
        assertTrue(meta.supportsStoredProcedures())
        assertEquals(LocalDate.of(2024, 2, 3), (c.scalar("SELECT {d '2024-02-03'}") as java.sql.Date).toLocalDate())
    }
}
