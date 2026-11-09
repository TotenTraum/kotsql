@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package ru.ttraum.kotsql.pool

import ru.ttraum.kotsql.BatchItem
import ru.ttraum.kotsql.ExecuteOptions
import ru.ttraum.kotsql.IsolationLevel
import ru.ttraum.kotsql.ServerInfo
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlCursor
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.StatementDescription
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.transaction
import ru.ttraum.kotsql.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * For critical sections of a few instructions that never suspend. A Mutex would put a coroutine to sleep on any contention, and
 * waking it takes a trip through the dispatcher, which costs more than the wait.
 */
private class SpinLock {
    private val held = AtomicInt(0)

    inline fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) { /* the holder is a few instructions from done */ }
        try {
            return block()
        } finally {
            held.store(0)
        }
    }
}

/**
 * @param maxSize the most connections open at any time, which is what callers queue for, first come, first served
 * @param minSize connections kept open even when idle, made in the background
 * @param acquireTimeout how long a caller waits for a connection, including making it
 * @param maxIdleTime after this an idle connection beyond [minSize] is closed
 * @param maxLifetime after this a connection is replaced, which also spreads load after a failover
 * @param validateAfterIdle a connection idle for this long is checked with a round trip before it's handed out
 * @param resetTimeout how long returning a connection may take, for its rollback. Past that it's closed, not kept
 * @param onConnect runs on each new connection, for session settings. If it fails, the connection is closed
 */
public data class PoolConfig(
    val maxSize: Int = 10,
    val minSize: Int = 0,
    val acquireTimeout: Duration = 30.seconds,
    val maxIdleTime: Duration = 10.minutes,
    val maxLifetime: Duration = 30.minutes,
    val validateAfterIdle: Duration = 30.seconds,
    val resetTimeout: Duration = 5.seconds,
    val maintenanceInterval: Duration = 30.seconds,
    val onConnect: (suspend SqlConnection.() -> Unit)? = null,
) {
    init {
        require(maxSize >= 1) { "maxSize must be at least 1" }
        require(minSize in 0..maxSize) { "minSize must be between 0 and maxSize" }
    }
}

/** A snapshot, for monitoring. */
public data class PoolStats(val inUse: Int, val idle: Int, val waiting: Int, val created: Int, val discarded: Int)

/**
 * Connections shared between coroutines, each used by one holder at a time, for any driver. Needs a transport that doesn't
 * block, as all but JDBC's are.
 *
 * A connection comes back from [withConnection] or [PooledConnection.close] with its open cursors closed and any transaction
 * rolled back, or it's closed instead. What else a holder changes in the session, such as a `SET`, stays for the next one.
 *
 * ```
 * val pool = ConnectionPool({ connect(url) })
 * pool.transaction { update("UPDATE accounts SET balance = balance - 1") }
 * ```
 */
public class ConnectionPool(private val connector: suspend () -> SqlConnection, public val config: PoolConfig = PoolConfig()) {
    private class Idle(val connection: SqlConnection, val createdAt: TimeSource.Monotonic.ValueTimeMark, val idleSince: TimeSource.Monotonic.ValueTimeMark)

    /** Fair, and handles a cancelled wait by itself. Holders of a permit are those in [acquire] or holding a connection. */
    private val permits = Semaphore(config.maxSize)

    /** Guards [idle] and [closed]. Most recently used last: those are the likeliest to be alive and in the server's caches. */
    private val lock = SpinLock()
    private val idle = ArrayList<Idle>()
    private var closed = false

    private val inUse = AtomicInt(0)
    private val waiting = AtomicInt(0)
    private val created = AtomicInt(0)
    private val discarded = AtomicInt(0)

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val maintenanceStarted = AtomicInt(0)

    /** Runs [block] with a connection, and returns the connection however it ends. */
    public suspend fun <T> withConnection(block: suspend SqlConnection.() -> T): T {
        val connection = acquire()
        try {
            return connection.block()
        } finally {
            connection.close()
        }
    }

    /** Runs [block] in a transaction: commits when it returns, rolls back when it throws. */
    public suspend fun <T> transaction(isolation: IsolationLevel? = null, block: suspend SqlConnection.() -> T): T =
        withConnection { transaction(isolation, block) }

    /** Streams a query's rows, holding a connection for as long as the collection lasts. */
    public fun query(sql: String, params: List<Any?> = emptyList(), fetchSize: Int = 0): Flow<SqlRow> = flow {
        withConnection { query(sql, params, fetchSize).collect { emit(it) } }
    }

    public suspend fun update(sql: String, params: List<Any?> = emptyList()): Long = withConnection { update(sql, params) }

    /**
     * Waits for a connection. Prefer [withConnection], since this one must be returned with [PooledConnection.close], or its
     * place in the pool is lost for good.
     */
    public suspend fun acquire(): PooledConnection {
        startMaintenance()
        immediately()?.let { return it }
        // Kept out here because a cancellation can arrive while the result is on its way back from the timeout's scope,
        // which would lose the connection and with it a place in the pool for good. It also tells the pool's own timeout,
        // which makes this null, from a caller's timeout around all this, which isn't for the pool to report or swallow.
        var connection: PooledConnection? = null
        var handedOut = false
        try {
            withTimeoutOrNull(config.acquireTimeout) { connection = acquireWithin() }
            val result = connection ?: throw SqlException(
                "No connection available within ${config.acquireTimeout}: ${inUse.load()} of ${config.maxSize} in use, ${waiting.load()} waiting",
                "HYT00",
                kind = SqlErrorKind.TIMEOUT,
            )
            handedOut = true
            return result
        } finally {
            if (!handedOut) connection?.close()
        }
    }

    /**
     * A free connection that needs no checking, if there is one. This takes no timer, unlike the path that may wait: on native
     * targets one thread serves all timers, and two per use made the pool itself the limit, at a third of the throughput.
     */
    private suspend fun immediately(): PooledConnection? {
        if (!permits.tryAcquire()) return null
        var handedOut = false
        try {
            val entry = lock.locked {
                if (closed) throw closedError()
                idle.lastOrNull()?.takeIf(::isFresh)?.also { idle.removeLast() }
            } ?: return null
            inUse.addAndFetch(1)
            handedOut = true
            return PooledConnection(entry.connection, this, entry.createdAt)
        } finally {
            if (!handedOut) permits.release()
        }
    }

    private fun isFresh(entry: Idle) = !entry.connection.isClosed && entry.createdAt.elapsedNow() <= config.maxLifetime &&
        entry.idleSince.elapsedNow() < config.validateAfterIdle

    private suspend fun acquireWithin(): PooledConnection {
        waiting.addAndFetch(1)
        try {
            permits.acquire()
        } finally {
            waiting.addAndFetch(-1)
        }
        // Counted from here, not from when the connection is ready, or maintenance would make another one in the meantime.
        inUse.addAndFetch(1)
        var handedOut = false
        try {
            lock.locked { if (closed) throw closedError() }
            val (connection, createdAt) = idleConnection() ?: newConnection()
            handedOut = true
            return PooledConnection(connection, this, createdAt)
        } finally {
            if (!handedOut) {
                inUse.addAndFetch(-1)
                permits.release()
            }
        }
    }

    private fun closedError() = SqlException("The connection pool is closed", "08003")

    /** The most recently used connection that's still good, closing those that aren't. */
    private suspend fun idleConnection(): Pair<SqlConnection, TimeSource.Monotonic.ValueTimeMark>? {
        while (true) {
            val entry = lock.locked { idle.removeLastOrNull() } ?: return null
            if (isUsable(entry)) return entry.connection to entry.createdAt
            discard(entry.connection)
        }
    }

    private suspend fun isUsable(entry: Idle): Boolean {
        if (entry.connection.isClosed || entry.createdAt.elapsedNow() > config.maxLifetime) return false
        if (isFresh(entry)) return true
        // A connection is lost for good when a call on it is interrupted, so this can't leave a broken one behind.
        return withContext(NonCancellable) { withTimeoutOrNull(config.resetTimeout) { entry.connection.ping() } } == true
    }

    private suspend fun newConnection(): Pair<SqlConnection, TimeSource.Monotonic.ValueTimeMark> {
        val connection = connector()
        try {
            config.onConnect?.invoke(connection)
        } catch (e: Throwable) {
            discard(connection)
            throw e
        }
        created.addAndFetch(1)
        return connection to TimeSource.Monotonic.markNow()
    }

    private suspend fun discard(connection: SqlConnection) {
        discarded.addAndFetch(1)
        withContext(NonCancellable) {
            // Closing politely can wait on a broken network.
            withTimeoutOrNull(config.resetTimeout) { runCatching { connection.close() } }
        }
    }

    internal suspend fun release(connection: SqlConnection, createdAt: TimeSource.Monotonic.ValueTimeMark, cursors: Collection<SqlCursor>, tainted: Boolean) {
        withContext(NonCancellable) {
            try {
                // A timer only if there's work for the server, for the same reason as in immediately().
                val clean = cursors.isEmpty() && connection.transactionState == TransactionState.IDLE
                val keep = !tainted && !connection.isClosed &&
                    (clean || withTimeoutOrNull(config.resetTimeout) { reset(connection, cursors) } == true)
                if (keep && clean) connection.drainWarnings()
                val back = keep && createdAt.elapsedNow() <= config.maxLifetime && lock.locked {
                    (!closed).also { open -> if (open) idle += Idle(connection, createdAt, TimeSource.Monotonic.markNow()) }
                }
                if (!back) discard(connection)
            } finally {
                inUse.addAndFetch(-1)
                permits.release()
            }
        }
    }

    /** Makes a connection fit for the next holder. False if it isn't. */
    private suspend fun reset(connection: SqlConnection, cursors: Collection<SqlCursor>): Boolean = try {
        // First the cursors, or the next statement would have to read and keep what's left of them.
        cursors.forEach { it.close() }
        if (connection.transactionState != TransactionState.IDLE) connection.update(connection.dialect.rollbackSql())
        connection.drainWarnings()
        connection.transactionState == TransactionState.IDLE
    } catch (e: SqlException) {
        false
    }

    private fun startMaintenance() {
        if (maintenanceStarted.fetchAndAdd(1) != 0) return
        background.launch {
            while (isActive) {
                runCatching { maintain() }
                delay(config.maintenanceInterval.coerceAtLeast(10.milliseconds))
            }
        }
    }

    /** Closes what has been idle too long or lived too long, and opens up to [PoolConfig.minSize]. */
    private suspend fun maintain() {
        val expired = lock.locked {
            val keepAtLeast = config.minSize
            val old = idle.filter { it.createdAt.elapsedNow() > config.maxLifetime || it.connection.isClosed }.toMutableList()
            idle.removeAll(old)
            // Oldest first, as they sit at the front.
            while (idle.size > keepAtLeast && idle.first().idleSince.elapsedNow() > config.maxIdleTime) old += idle.removeAt(0)
            old
        }
        expired.forEach { discard(it.connection) }
        while (lock.locked { !closed && idle.size + inUse.load() < config.minSize }) {
            if (!permits.tryAcquire()) return
            try {
                val (connection, createdAt) = newConnection()
                val kept = lock.locked {
                    (!closed).also { open -> if (open) idle += Idle(connection, createdAt, TimeSource.Monotonic.markNow()) }
                }
                if (!kept) discard(connection)
            } finally {
                permits.release()
            }
        }
    }

    /** What's in the pool right now. Idle connections aren't locked for this, so the numbers may be a moment apart. */
    public suspend fun stats(): PoolStats = PoolStats(inUse.load(), lock.locked { idle.size }, waiting.load(), created.load(), discarded.load())

    /** Closes idle connections, and the ones in use when they return. Later and waiting calls of [acquire] fail. */
    public suspend fun close() {
        val all = lock.locked {
            if (closed) return
            closed = true
            idle.toList().also { idle.clear() }
        }
        background.cancel()
        all.forEach { discard(it.connection) }
    }
}

/**
 * A connection on loan from a [ConnectionPool]. After [close] returns it to the pool this object is useless, and in particular
 * can't reach whoever holds the connection next.
 */
public class PooledConnection internal constructor(
    private val connection: SqlConnection,
    private val pool: ConnectionPool,
    private val createdAt: TimeSource.Monotonic.ValueTimeMark,
) : SqlConnection {
    private var released = false

    /** Cursors that may still be reading, to close on return. */
    private val cursors = LinkedHashSet<SqlCursor>()

    /** A setting of the connection itself is changed, which the next holder mustn't inherit. */
    private var tainted = false

    private fun checkHeld() {
        if (released) throw SqlException("The connection has been returned to the pool", "08003")
    }

    override val dialect: SqlDialect get() = connection.dialect
    override val serverInfo: ServerInfo get() = connection.serverInfo
    override val codecs: ru.ttraum.kotsql.CodecRegistry get() = connection.codecs

    /** Reaches a driver's own interface, which is good only for as long as this is held. */
    override fun <T : Any> unwrap(type: KClass<T>): T? {
        checkHeld()
        return super.unwrap(type) ?: connection.unwrap(type)
    }
    override val transactionState: TransactionState get() = if (released) TransactionState.IDLE else connection.transactionState
    override val isClosed: Boolean get() = released || connection.isClosed

    private fun track(cursor: SqlCursor): SqlCursor {
        val tracked = TrackedCursor(cursor)
        cursors += tracked
        return tracked
    }

    /** Forgets itself once finished, so that a long-held connection doesn't collect cursors. */
    private inner class TrackedCursor(private val cursor: SqlCursor) : SqlCursor {
        override suspend fun nextResult(): SqlResult? = cursor.nextResult().also { if (it == null) cursors -= this }
        override suspend fun close() {
            cursors -= this
            cursor.close()
        }
    }

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions): SqlCursor {
        checkHeld()
        return track(connection.execute(sql, params, options))
    }

    override suspend fun executeScript(sql: String): SqlCursor {
        checkHeld()
        return track(connection.executeScript(sql))
    }

    override suspend fun executeBatch(items: List<BatchItem>): LongArray {
        checkHeld()
        return connection.executeBatch(items)
    }

    override suspend fun describe(sql: String): StatementDescription {
        checkHeld()
        return connection.describe(sql)
    }

    /** Does nothing once returned: it would interrupt whatever the next holder is running. */
    override suspend fun cancel() {
        if (!released) connection.cancel()
    }

    override suspend fun ping(): Boolean = !released && connection.ping()

    override fun setReadTimeout(millis: Long) {
        checkHeld()
        tainted = true
        connection.setReadTimeout(millis)
    }

    override fun drainWarnings(): List<String> {
        checkHeld()
        return connection.drainWarnings()
    }

    /** Returns the connection to the pool. Calling this again does nothing. */
    override suspend fun close() {
        if (released) return
        released = true
        val open = cursors.toList()
        cursors.clear()
        pool.release(connection, createdAt, open, tainted)
    }
}
