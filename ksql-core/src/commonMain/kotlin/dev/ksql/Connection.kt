package dev.ksql

import kotlin.reflect.KClass

enum class TransactionState { IDLE, ACTIVE, FAILED }

enum class IsolationLevel(val sql: String) {
    READ_UNCOMMITTED("READ UNCOMMITTED"),
    READ_COMMITTED("READ COMMITTED"),
    REPEATABLE_READ("REPEATABLE READ"),
    SERIALIZABLE("SERIALIZABLE");

    companion object {
        fun ofSql(text: String): IsolationLevel? = entries.firstOrNull { it.sql.equals(text.trim(), ignoreCase = true) }
    }
}

data class ServerInfo(val productName: String, val version: String, val majorVersion: Int, val minorVersion: Int)

/** @param fetchSize rows requested per round trip; 0 streams the whole result in a single request. */
data class ExecuteOptions(val fetchSize: Int = 0) {
    companion object { val DEFAULT = ExecuteOptions() }
}

data class BatchItem(val sql: String, val params: List<Any?> = emptyList())

data class StatementDescription(val parameterTypes: List<SqlType>, val columns: List<ColumnInfo>)

/** One statement's outcome. */
sealed interface SqlResult {
    /** @param generatedKeys values generated for the affected rows, from drivers of databases that report them this way */
    class UpdateCount(val count: Long, val generatedKeys: List<Long> = emptyList()) : SqlResult

    /** Pull-based row stream. Rows are read from the network on demand, so a slow consumer applies backpressure. */
    interface Rows : SqlResult {
        val columns: List<ColumnInfo>

        /** Next row, or null when this result set is exhausted. */
        suspend fun next(): SqlRow?

        /**
         * The next row if that takes no I/O, otherwise null, which says nothing about whether more rows exist: call
         * [next] then. Most rows of a result arrive in a batch, and suspending functions allocate on every call even
         * when they don't suspend, so this makes reading those rows much cheaper. Drivers may leave this as it is.
         */
        fun nextBuffered(): SqlRow? = null

        /** Rows affected by a data-changing statement with `RETURNING`; null until exhausted or for plain queries. */
        val updateCount: Long?
    }
}

/**
 * The results of an execution, in order.
 *
 * A cursor remains readable if another operation is started on the connection: drivers buffer what is left of it first.
 */
interface SqlCursor {
    /** Moves to the next result, skipping unread rows of the current one. Null when there are no more. */
    suspend fun nextResult(): SqlResult?

    /** Discards everything unread. */
    suspend fun close()
}

/**
 * A session with a database, implemented by every driver.
 *
 * Not safe for concurrent use: callers serialize access, as the JDBC and R2DBC adapters do. [cancel] is the exception.
 * A coroutine must not be cancelled inside these calls, since that could desynchronize the protocol stream.
 */
interface SqlConnection {
    val dialect: SqlDialect
    val serverInfo: ServerInfo
    val transactionState: TransactionState
    val isClosed: Boolean

    /** This connection as [type], for what only its database has: a driver's own interface, for one. Null if it's not one. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> unwrap(type: KClass<T>): T? = if (type.isInstance(this)) this as T else null

    /** How this connection converts application classes, and what adapters consult for theirs. */
    val codecs: CodecRegistry get() = CodecRegistry.Global

    /** Runs one statement with native parameter markers (see [SqlDialect.parameterMarker]). */
    suspend fun execute(sql: String, params: List<Any?> = emptyList(), options: ExecuteOptions = ExecuteOptions.DEFAULT): SqlCursor

    /** Runs a parameterless script, which may contain several statements. */
    suspend fun executeScript(sql: String): SqlCursor

    /** Runs the items efficiently and atomically unless a transaction is already open. Returns one count per item. */
    suspend fun executeBatch(items: List<BatchItem>): LongArray

    /** Describes a statement without running it. */
    suspend fun describe(sql: String): StatementDescription

    /** Asks the server to abort the running statement. May be called from any thread. */
    suspend fun cancel()

    /** Round-trips to the server. Returns false rather than throwing if the connection is unusable. */
    suspend fun ping(): Boolean

    /** Sets the socket read timeout for later reads; 0 disables it. */
    fun setReadTimeout(millis: Long)

    /** Warnings and notices received since the last call. */
    fun drainWarnings(): List<String>

    suspend fun close()
}
