package ru.ttraum.kotsql

import kotlin.reflect.KClass

public enum class TransactionState { IDLE, ACTIVE, FAILED }

public enum class IsolationLevel(public val sql: String) {
    READ_UNCOMMITTED("READ UNCOMMITTED"),
    READ_COMMITTED("READ COMMITTED"),
    REPEATABLE_READ("REPEATABLE READ"),
    SERIALIZABLE("SERIALIZABLE");

    public companion object {
        public fun ofSql(text: String): IsolationLevel? = entries.firstOrNull { it.sql.equals(text.trim(), ignoreCase = true) }
    }
}

public data class ServerInfo(val productName: String, val version: String, val majorVersion: Int, val minorVersion: Int)

/** @param fetchSize rows requested per round trip; 0 streams the whole result in a single request. */
public data class ExecuteOptions(val fetchSize: Int = 0) {
    public companion object { public val DEFAULT: ExecuteOptions = ExecuteOptions() }
}

public data class BatchItem(val sql: String, val params: List<Any?> = emptyList())

public data class StatementDescription(val parameterTypes: List<SqlType>, val columns: List<ColumnInfo>)

/** One statement's outcome. */
public sealed interface SqlResult {
    /** @param generatedKeys values generated for the affected rows, from drivers of databases that report them this way */
    public class UpdateCount(public val count: Long, public val generatedKeys: List<Long> = emptyList()) : SqlResult

    /** Pull-based row stream. Rows are read from the network on demand, so a slow consumer applies backpressure. */
    public interface Rows : SqlResult {
        public val columns: List<ColumnInfo>

        /** Next row, or null when this result set is exhausted. */
        public suspend fun next(): SqlRow?

        /**
         * The next row if that takes no I/O, otherwise null, which says nothing about whether more rows exist: call
         * [next] then. Most rows of a result arrive in a batch, and suspending functions allocate on every call even
         * when they don't suspend, so this makes reading those rows much cheaper. Drivers may leave this as it is.
         */
        public fun nextBuffered(): SqlRow? = null

        /** Rows affected by a data-changing statement with `RETURNING`; null until exhausted or for plain queries. */
        public val updateCount: Long?
    }
}

/**
 * The results of an execution, in order.
 *
 * A cursor remains readable if another operation is started on the connection: drivers buffer what is left of it first.
 */
public interface SqlCursor {
    /** Moves to the next result, skipping unread rows of the current one. Null when there are no more. */
    public suspend fun nextResult(): SqlResult?

    /** Discards everything unread. */
    public suspend fun close()
}

/**
 * A session with a database, implemented by every driver.
 *
 * Not safe for concurrent use: callers serialize access, as the JDBC and R2DBC adapters do. [cancel] is the exception.
 * A coroutine must not be cancelled inside these calls, since that could desynchronize the protocol stream.
 */
public interface SqlConnection {
    public val dialect: SqlDialect
    public val serverInfo: ServerInfo
    public val transactionState: TransactionState
    public val isClosed: Boolean

    /** This connection as [type], for what only its database has: a driver's own interface, for one. Null if it's not one. */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> unwrap(type: KClass<T>): T? = if (type.isInstance(this)) this as T else null

    /** How this connection converts application classes, and what adapters consult for theirs. */
    public val codecs: CodecRegistry get() = CodecRegistry.Global

    /** Runs one statement with native parameter markers (see [SqlDialect.parameterMarker]). */
    public suspend fun execute(sql: String, params: List<Any?> = emptyList(), options: ExecuteOptions = ExecuteOptions.DEFAULT): SqlCursor

    /** Runs a parameterless script, which may contain several statements. */
    public suspend fun executeScript(sql: String): SqlCursor

    /** Runs the items efficiently and atomically unless a transaction is already open. Returns one count per item. */
    public suspend fun executeBatch(items: List<BatchItem>): LongArray

    /** Describes a statement without running it. */
    public suspend fun describe(sql: String): StatementDescription

    /** Asks the server to abort the running statement. May be called from any thread. */
    public suspend fun cancel()

    /** Round-trips to the server. Returns false rather than throwing if the connection is unusable. */
    public suspend fun ping(): Boolean

    /** Sets the socket read timeout for later reads; 0 disables it. */
    public fun setReadTimeout(millis: Long)

    /** Warnings and notices received since the last call. */
    public fun drainWarnings(): List<String>

    public suspend fun close()
}
