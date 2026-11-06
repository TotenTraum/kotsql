package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.BatchItem
import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.ExecuteOptions
import ru.ttraum.kotsql.IsolationLevel
import ru.ttraum.kotsql.ServerInfo
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlCursor
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlSyntax
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.StatementDescription
import ru.ttraum.kotsql.TranslatedSql
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.spi.ListRows
import ru.ttraum.kotsql.spi.SqlDriver
import ru.ttraum.kotsql.spi.ValuesRow
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A database that has nothing in common with those of the real drivers: no network, and a syntax of its own, in which `%` starts
 * a comment-free string quote. It depends on kotsql-core alone, as any driver may, and if the adapter expects more of one, this fails.
 */
private object FakeDialect : SqlDialect {
    override val productName = "Fake"
    override val syntax = SqlSyntax(quotes = "'")
    override fun parameterMarker(index: Int) = "?"
    override fun translatePositional(sql: String) = TranslatedSql(sql, sql.count { it == '?' })
    override fun parameterCount(sql: String) = sql.count { it == '?' }
    override fun parameterIndex(name: String): Int? = null
    override fun setIsolationSql(level: IsolationLevel) = "SET ISOLATION"
    override fun showIsolationSql() = "SHOW ISOLATION"
    override fun setReadOnlySql(readOnly: Boolean) = "SET READ ONLY"
}

private class FakeConnection(private val seen: MutableList<String>) : SqlConnection {
    override val dialect: SqlDialect get() = FakeDialect
    override val serverInfo = ServerInfo("Fake", "1.0", 1, 0)
    override val transactionState = TransactionState.IDLE
    override var isClosed = false
    private val columns = listOf(ColumnInfo("sql", SqlType.TEXT, "TEXT"))

    private fun answer(sql: String, params: List<Any?>): SqlCursor {
        seen += sql
        val rows = ListRows(columns, listOf(ValuesRow(columns, listOf("$sql $params"))))
        return object : SqlCursor {
            private var result: SqlResult? = rows
            override suspend fun nextResult() = result.also { result = null }
            override suspend fun close() {}
        }
    }

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions) = answer(sql, params)
    override suspend fun executeScript(sql: String) = answer(sql, emptyList())
    override suspend fun executeBatch(items: List<BatchItem>) = LongArray(items.size) { 1 }
    override suspend fun describe(sql: String) = StatementDescription(emptyList(), columns)
    override suspend fun cancel() {}
    override suspend fun ping() = !isClosed
    override fun setReadTimeout(millis: Long) {}
    override fun drainWarnings(): List<String> = emptyList()
    override suspend fun close() { isClosed = true }
}

class FakeDriverTest {
    private val seen = ArrayList<String>()

    init {
        Drivers.register(object : SqlDriver {
            override val schemes = setOf("fake")
            override val dialect: SqlDialect get() = FakeDialect
            override suspend fun connect(config: ConnectionConfig): SqlConnection = FakeConnection(seen)
        })
    }

    @Test fun aDriverOfNothingButCoreWorksThroughJdbc() {
        DriverManager.getConnection("jdbc:kotsql:fake://anywhere/x").use { c ->
            assertEquals("Fake", c.metaData.databaseProductName)
            c.prepareStatement("SELECT ?").use { p ->
                p.setInt(1, 5)
                p.executeQuery().use { r ->
                    r.next()
                    assertEquals("SELECT ? [5]", r.getString(1))
                }
            }
            // The dialect's own say on what's quoted: an escape in a string stays, and one outside it is translated.
            c.createStatement().use { s -> s.executeQuery("SELECT '{fn UCASE(1)}', {d '2024-01-02'}").close() }
            assertEquals("SELECT '{fn UCASE(1)}', DATE '2024-01-02'", seen.last())
            // Procedures are there by default, as the standard has them.
            assertFailsWith<java.sql.SQLException> { c.prepareCall("SELECT 1") }
        }
    }
}
