package ru.ttraum.kotsql.r2dbc

import ru.ttraum.kotsql.IsolationLevel as CoreIsolation
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlCursor
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.update
import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.reactivestreams.Publisher
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

internal class R2dbcConnection(private val core: SqlConnection) : Connection {
    private val mutex = Mutex()
    private var autoCommit = true
    private var isolation = IsolationLevel.valueOf(core.dialect.defaultIsolation.sql)
    private val dialect get() = core.dialect
    val codecs get() = core.codecs
    private val abandoned = ConcurrentLinkedQueue<SqlCursor>()

    /**
     * Every call into the driver goes through here. The lock serializes subscribers, and since cancelling a coroutine
     * mid-read would desynchronize the protocol stream, a call always runs to its end even if its subscriber has left.
     */
    suspend fun <T> op(block: suspend SqlConnection.() -> T): T =
        withContext(NonCancellable) {
            mutex.withLock {
                translating {
                    while (true) (abandoned.poll() ?: break).let { c -> runCatching { c.close() } }
                    core.block()
                }
            }
        }

    /** A row that takes no waiting, neither for the server nor for the lock; null means to go through [op]. */
    fun nextRowWithoutWaiting(rows: SqlResult.Rows): SqlRow? {
        if (!abandoned.isEmpty() || !mutex.tryLock()) return null
        try {
            return translating { rows.nextBuffered() }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Marks a cursor as unwanted, to be closed before the next call. It takes effect right away, unlike coroutine
     * cancellation: otherwise a query issued right after `take(n)` could beat the cancellation and, finding the
     * cursor still open, would have to save all of its remaining rows.
     */
    fun abandon(cursor: SqlCursor) { abandoned += cursor }

    val parameterCount: (String) -> Int get() = dialect::parameterCount
    val parameterIndex: (String) -> Int? get() = dialect::parameterIndex
    fun withGeneratedKeys(sql: String, columns: List<String>) = dialect.withGeneratedKeys(sql, columns)

    /** With auto-commit off, a transaction starts implicitly before the first statement. */
    suspend fun SqlConnection.beginIfNeeded() {
        if (!autoCommit && transactionState == TransactionState.IDLE) dialect.beginStatements().forEach { update(it) }
    }

    override fun beginTransaction(): Publisher<Void> = beginTransaction(EmptyDefinition)
    override fun beginTransaction(definition: TransactionDefinition): Publisher<Void> = none {
        val level = definition.getAttribute(TransactionDefinition.ISOLATION_LEVEL)?.let { CoreIsolation.ofSql(it.asSql()) }
        val readOnly = definition.getAttribute(TransactionDefinition.READ_ONLY)
        val lockTimeout = definition.getAttribute(TransactionDefinition.LOCK_WAIT_TIMEOUT)
        op {
            if (transactionState == TransactionState.IDLE) dialect.beginStatements(level, readOnly).forEach { update(it) }
            lockTimeout?.let { d -> dialect.setLockTimeoutSql(d.toMillis())?.let { update("$it") } }
        }
    }

    override fun commitTransaction(): Publisher<Void> = none { op { if (transactionState != TransactionState.IDLE) update(dialect.commitSql()) } }
    override fun rollbackTransaction(): Publisher<Void> = none { op { if (transactionState != TransactionState.IDLE) update(dialect.rollbackSql()) } }

    override fun createSavepoint(name: String): Publisher<Void> {
        requireName(name)
        return none {
            op {
                // A savepoint needs a transaction; the SPI says to start one.
                if (transactionState == TransactionState.IDLE) dialect.beginStatements().forEach { update(it) }
                update(dialect.savepointSql(name))
            }
        }
    }
    override fun releaseSavepoint(name: String): Publisher<Void> {
        requireName(name)
        return none { op { update(dialect.releaseSavepointSql(name)) } }
    }
    override fun rollbackTransactionToSavepoint(name: String): Publisher<Void> {
        requireName(name)
        return none { op { update(dialect.rollbackToSavepointSql(name)) } }
    }
    @Suppress("SENSELESS_COMPARISON")
    private fun requireName(name: String?) { if (name == null) throw IllegalArgumentException("name must not be null") }

    override fun isAutoCommit() = autoCommit && core.transactionState == TransactionState.IDLE
    override fun setAutoCommit(autoCommit: Boolean): Publisher<Void> = none {
        if (autoCommit != isAutoCommit) op {
            // Per the SPI, turning auto-commit on commits an open transaction.
            if (autoCommit && transactionState != TransactionState.IDLE) update(dialect.commitSql())
            this@R2dbcConnection.autoCommit = autoCommit
        }
    }

    override fun getTransactionIsolationLevel(): IsolationLevel = isolation
    override fun setTransactionIsolationLevel(level: IsolationLevel): Publisher<Void> {
        @Suppress("SENSELESS_COMPARISON")
        if (level == null) throw IllegalArgumentException("level must not be null")
        return none {
            val coreLevel = CoreIsolation.ofSql(level.asSql()) ?: throw IllegalArgumentException("Unknown isolation level ${level.asSql()}")
            op { update(dialect.setIsolationSql(coreLevel)) }
            isolation = level
        }
    }

    override fun setLockWaitTimeout(timeout: Duration): Publisher<Void> = timeoutSetting(dialect.setLockTimeoutSql(timeout.toMillis()))
    override fun setStatementTimeout(timeout: Duration): Publisher<Void> = timeoutSetting(dialect.setStatementTimeoutSql(timeout.toMillis()))
    private fun timeoutSetting(sql: String?): Publisher<Void> = none {
        op { update(sql ?: throw R2dbcNonTransientResourceException("This database has no such timeout")) }
    }

    override fun createStatement(sql: String): Statement {
        @Suppress("SENSELESS_COMPARISON")
        if (sql == null) throw IllegalArgumentException("sql must not be null")
        return R2dbcStatement(this, sql)
    }
    override fun createBatch(): Batch = R2dbcBatch(this)

    override fun validate(depth: ValidationDepth): Publisher<Boolean> = one {
        if (depth == ValidationDepth.LOCAL) !core.isClosed else op { ping() }
    }
    override fun getMetadata() = object : ConnectionMetadata {
        override fun getDatabaseProductName() = core.serverInfo.productName
        override fun getDatabaseVersion() = core.serverInfo.version
    }
    override fun close(): Publisher<Void> = none { op { close() } }

    private object EmptyDefinition : TransactionDefinition {
        override fun <T : Any?> getAttribute(option: io.r2dbc.spi.Option<T>): T? = null
    }
}
