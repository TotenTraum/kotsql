package ru.ttraum.kotsql.postgres

import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A message from `NOTIFY`. */
public data class PgNotification(val channel: String, val payload: String, val senderPid: Int)

/**
 * What only PostgreSQL has. Reach it from any connection of this driver with [postgres], also through a pool.
 */
public interface PostgresConnection : SqlConnection {
    /**
     * Runs a `COPY ... FROM STDIN` and sends it [data], in chunks that needn't follow row boundaries. Returns the number of rows.
     *
     * If [data] fails, the server is told to abandon the copy, nothing is stored, and the failure is rethrown. The server's own
     * complaints, such as about a malformed row, can only be noticed when all the data has been sent. [sql] must be a COPY
     * from STDIN: anything else fails, but only after the server has run it.
     */
    public suspend fun copyIn(sql: String, data: Flow<ByteArray>): Long

    /**
     * Runs a `COPY ... TO STDOUT` and emits its data, one row per chunk in text and CSV formats. If collection stops early, the
     * statement is cancelled on the server, and the connection goes on.
     */
    public fun copyOut(sql: String): Flow<ByteArray>

    /**
     * Loads rows into [table], much faster than inserting them. Values are those accepted as parameters, including with codecs.
     * The table and column names are quoted, so they're taken exactly as given, which leaves no room for a schema: use
     * [copyIn] for that. Returns the number of rows.
     */
    public suspend fun copyInRows(table: String, columns: List<String>, rows: Flow<List<Any?>>): Long

    /** Emits the rows of a table or of a `SELECT`, as `(SELECT ...)`, with each value in the server's text form. */
    public fun copyOutRows(source: String): Flow<List<String?>>

    /**
     * Listens on [channels] and emits notifications until collection ends, using this connection for nothing else meanwhile. Those
     * received before, while running statements, come first.
     *
     * Interrupting a wait closes the connection, as interrupting any read does, so give this a connection of its own. For one that
     * also survives losing its connection, see [postgresNotifications].
     */
    public fun notifications(vararg channels: String): Flow<PgNotification>

    /** Sends a notification, delivered when the current transaction commits, if there is one. */
    public suspend fun notify(channel: String, payload: String = "")

    /**
     * Those received while running statements, oldest first, for a connection that does `LISTEN` by itself between its other work.
     * Up to [MAX_BUFFERED_NOTIFICATIONS] are kept; beyond that the oldest are dropped.
     */
    public fun drainNotifications(): List<PgNotification>

    public companion object {
        public const val MAX_BUFFERED_NOTIFICATIONS: Int = 10_000
    }
}

/** This connection's PostgreSQL features. Fails on a connection to another database. */
public val SqlConnection.postgres: PostgresConnection
    get() = unwrap(PostgresConnection::class) ?: throw SqlException("This is not a PostgreSQL connection", "0A000")

/**
 * Notifications on [channels] for as long as they're collected, on a connection of their own from [connect], reconnecting if it's
 * lost, after [reconnectDelay].
 *
 * **Notifications sent while disconnected are lost**, as PostgreSQL doesn't keep them. So treat a notification as a hint and use
 * [onConnected], called after each connection starts listening, the first included, to catch up with whatever it may have missed.
 * Each failure to connect waits and retries, unless it's about authentication or the like, which won't get better by itself.
 */
public fun postgresNotifications(
    connect: suspend () -> SqlConnection,
    vararg channels: String,
    reconnectDelay: Duration = 1.seconds,
    onConnected: suspend () -> Unit = {},
): Flow<PgNotification> = flow {
    while (true) {
        // Errors of the collector and of onConnected are theirs, even one that looks like a lost connection. Only this stream's
        // own are reasons to reconnect.
        var theirs: Throwable? = null
        try {
            val connection = connect()
            try {
                val pg = connection.unwrap(PgConnection::class) ?: throw SqlException("This is not a PostgreSQL connection", "0A000")
                pg.listenAndDeliver(
                    channels.toList(),
                    onListening = { runCatching { onConnected() }.onFailure { theirs = it }.getOrThrow() },
                    deliver = { notification -> runCatching { emit(notification) }.onFailure { theirs = it }.getOrThrow() },
                )
            } finally {
                connection.close()
            }
        } catch (e: SqlException) {
            if (e === theirs || !e.isTemporary()) throw e
        }
        delay(reconnectDelay)
    }
}

private fun SqlException.isTemporary() = when (kind) {
    ru.ttraum.kotsql.SqlErrorKind.CONNECTION -> true
    // The server shutting down or restarting, in the state's class of "operator intervention".
    else -> sqlState?.startsWith("57") == true
}
