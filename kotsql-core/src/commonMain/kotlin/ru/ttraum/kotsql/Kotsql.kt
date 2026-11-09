package ru.ttraum.kotsql

import ru.ttraum.kotsql.spi.Drivers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Opens a connection using the driver registered for the URL's scheme, e.g. `sqlite:///app.db`. For a database behind a
 * socket, use `connect` of kotsql-transport-ktor, or of kotsql-transport with a transport of your choice.
 */
public suspend fun connect(url: String, codecs: CodecRegistry = CodecRegistry.Global): SqlConnection {
    val config = ConnectionConfig.parse(url).copy(codecs = codecs)
    return Drivers.require(config.scheme).connect(config)
}

/** Streams the rows of a query. The cursor is closed when collection ends, however it ends. */
public fun SqlConnection.query(sql: String, params: List<Any?> = emptyList(), fetchSize: Int = 0): Flow<SqlRow> = flow {
    val cursor = execute(sql, params, ExecuteOptions(fetchSize))
    try {
        while (true) {
            val result = cursor.nextResult() ?: break
            if (result is SqlResult.Rows) while (true) emit(result.nextBuffered() ?: result.next() ?: break)
        }
    } finally {
        cursor.close()
    }
}

/** Runs a statement and returns the number of affected rows. */
public suspend fun SqlConnection.update(sql: String, params: List<Any?> = emptyList()): Long {
    val cursor = execute(sql, params)
    try {
        var total = 0L
        while (true) when (val result = cursor.nextResult() ?: return total) {
            is SqlResult.UpdateCount -> total += result.count
            is SqlResult.Rows -> {
                while ((result.nextBuffered() ?: result.next()) != null) { /* skip */ }
                total += result.updateCount ?: 0
            }
        }
    } finally {
        cursor.close()
    }
}

/** Runs [block] in a transaction: commits when it returns, rolls back when it throws. */
public suspend fun <T> SqlConnection.transaction(isolation: IsolationLevel? = null, block: suspend SqlConnection.() -> T): T {
    dialect.beginStatements(isolation).forEach { update(it) }
    val result = try {
        block()
    } catch (e: Throwable) {
        try { update(dialect.rollbackSql()) } catch (rollback: Exception) { e.addSuppressed(rollback) }
        throw e
    }
    update(dialect.commitSql())
    return result
}
