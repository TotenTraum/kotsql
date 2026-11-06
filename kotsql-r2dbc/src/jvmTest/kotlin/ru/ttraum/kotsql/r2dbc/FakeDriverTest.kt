package ru.ttraum.kotsql.r2dbc

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
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.StatementDescription
import ru.ttraum.kotsql.TranslatedSql
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.spi.ListRows
import ru.ttraum.kotsql.spi.SqlDriver
import ru.ttraum.kotsql.spi.ValuesRow
import io.r2dbc.spi.ConnectionFactories
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

/** A driver that depends on kotsql-core alone, as any driver may: if the adapter expects more of one, this fails. */
private object FakeDialect : SqlDialect {
    override val productName = "Fake"
    override fun parameterMarker(index: Int) = "?"
    override fun translatePositional(sql: String) = TranslatedSql(sql, sql.count { it == '?' })
    override fun parameterCount(sql: String) = sql.count { it == '?' }
    override fun parameterIndex(name: String): Int? = null
    override fun setIsolationSql(level: IsolationLevel) = "SET ISOLATION"
    override fun showIsolationSql() = "SHOW ISOLATION"
    override fun setReadOnlySql(readOnly: Boolean) = "SET READ ONLY"
}

private class FakeConnection : SqlConnection {
    override val dialect: SqlDialect get() = FakeDialect
    override val serverInfo = ServerInfo("Fake", "1.0", 1, 0)
    override val transactionState = TransactionState.IDLE
    override var isClosed = false
    private val columns = listOf(ColumnInfo("sql", SqlType.TEXT, "TEXT"))

    private fun answer(sql: String): SqlCursor {
        val rows = ListRows(columns, listOf(ValuesRow(columns, listOf(sql))))
        return object : SqlCursor {
            private var result: SqlResult? = rows
            override suspend fun nextResult() = result.also { result = null }
            override suspend fun close() {}
        }
    }

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions) = answer(sql)
    override suspend fun executeScript(sql: String) = answer(sql)
    override suspend fun executeBatch(items: List<BatchItem>) = LongArray(items.size) { 1 }
    override suspend fun describe(sql: String) = StatementDescription(emptyList(), columns)
    override suspend fun cancel() {}
    override suspend fun ping() = !isClosed
    override fun setReadTimeout(millis: Long) {}
    override fun drainWarnings(): List<String> = emptyList()
    override suspend fun close() { isClosed = true }
}

class FakeDriverTest {
    @Test fun aDriverOfNothingButCoreWorksThroughR2dbc() {
        Drivers.register(object : SqlDriver {
            override val schemes = setOf("fake")
            override val dialect: SqlDialect get() = FakeDialect
            override suspend fun connect(config: ConnectionConfig): SqlConnection = FakeConnection()
        })
        val connection = Mono.from(ConnectionFactories.get("r2dbc:kotsql:fake://anywhere/x").create()).block()!!
        try {
            assertEquals("Fake", connection.metadata.databaseProductName)
            val values = Flux.from(connection.createStatement("SELECT 1").execute())
                .concatMap { result -> result.map { row, _ -> row.get(0, String::class.java)!! } }
                .collectList().block()
            assertEquals(listOf("SELECT 1"), values)
        } finally {
            Mono.from(connection.close()).block()
        }
    }
}
