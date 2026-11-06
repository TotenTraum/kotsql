package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.IsolationLevel
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.runUnsuspended
import ru.ttraum.kotsql.update
import kotlinx.coroutines.flow.first
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Savepoint
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class JdbcConnection(val core: SqlConnection, private val url: String, private val user: String?) : BaseConnection() {
    private val lock = ReentrantLock()
    private var autoCommit = true
    private var readOnly = false
    private var savepointCounter = 0
    private var networkTimeout = 0
    private val clientInfo = Properties()
    val dialect get() = core.dialect
    val jdbcUrl get() = url
    val userName get() = user

    /** Runs driver code on this thread. The lock makes the connection safe to share, as JDBC requires. */
    fun <T> run(block: suspend SqlConnection.() -> T): T = lock.withLock { translating { runUnsuspended { core.block() } } }

    /** The next row, with no suspending call and its allocations if the row has been received already. */
    fun nextRow(rows: SqlResult.Rows): SqlRow? = lock.withLock {
        translating { rows.nextBuffered() ?: runUnsuspended { rows.next() } }
    }

    private fun checkOpen() { if (core.isClosed) throw SQLException("The connection is closed", "08003") }

    /** With auto-commit off, a transaction starts implicitly before the first statement. */
    fun beforeStatement() {
        checkOpen()
        if (!autoCommit) run { if (transactionState == TransactionState.IDLE) dialect.beginStatements().forEach { update(it) } }
    }

    fun warnings(): SQLWarning? = chain(core.drainWarnings())

    override fun getWarnings(): SQLWarning? { checkOpen(); return warnings() }
    override fun clearWarnings() { core.drainWarnings() }

    override fun createStatement(): Statement { checkOpen(); return JdbcStatement(this) }
    override fun createStatement(type: Int, concurrency: Int): Statement {
        checkCursorType(type)
        checkOpen()
        return JdbcStatement(this, type, concurrency)
    }
    override fun createStatement(type: Int, concurrency: Int, holdability: Int): Statement = createStatement(type, concurrency)

    private fun checkCursorType(type: Int) {
        if (type == ResultSet.TYPE_SCROLL_SENSITIVE) unsupported("A ResultSet that sees later changes")
    }

    private fun prepare(
        sql: String?,
        keys: List<String>?,
        type: Int = ResultSet.TYPE_FORWARD_ONLY,
        concurrency: Int = ResultSet.CONCUR_READ_ONLY,
    ): java.sql.PreparedStatement {
        checkOpen()
        val translated = translating { dialect.translatePositional(translateEscapes(sql ?: throw SQLException("sql is null"), dialect)) }
        return JdbcPreparedStatement(this, translated.sql, translated.parameterCount, keys, type, concurrency)
    }

    override fun prepareCall(p0: String?): java.sql.CallableStatement = prepareCall(p0, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    override fun prepareCall(p0: String?, p1: Int, p2: Int): java.sql.CallableStatement {
        checkCursorType(p1)
        checkOpen()
        val call = translating { parseCall(p0 ?: throw SQLException("sql is null"), dialect) }
        if (!call.returnsValue && !dialect.calls.procedures) unsupported("${dialect.productName} has no procedures, so a call without a return value")
        return JdbcCallableStatement(this, call, p1, p2)
    }
    override fun prepareCall(p0: String?, p1: Int, p2: Int, p3: Int): java.sql.CallableStatement = prepareCall(p0, p1, p2)

    override fun prepareStatement(p0: String?) = prepare(p0, null)
    override fun prepareStatement(p0: String?, p1: Int, p2: Int): java.sql.PreparedStatement { checkCursorType(p1); return prepare(p0, null, p1, p2) }
    override fun prepareStatement(p0: String?, p1: Int, p2: Int, p3: Int): java.sql.PreparedStatement = prepareStatement(p0, p1, p2)
    override fun prepareStatement(p0: String?, p1: Int) = prepare(p0, if (p1 == Statement.RETURN_GENERATED_KEYS) emptyList() else null)
    override fun prepareStatement(p0: String?, p1: Array<out String>?) = prepare(p0, p1?.toList().orEmpty())
    override fun prepareStatement(p0: String?, p1: IntArray?): java.sql.PreparedStatement = unsupported("Generated keys by column index")
    override fun nativeSQL(p0: String?): String = translating { dialect.translatePositional(translateEscapes(p0 ?: "", dialect)).sql }

    override fun getAutoCommit(): Boolean { checkOpen(); return autoCommit }
    override fun setAutoCommit(p0: Boolean) {
        checkOpen()
        if (p0 && !autoCommit) finishTransaction(dialect.commitSql())
        autoCommit = p0
    }
    private fun finishTransaction(sql: String) = run { if (transactionState != TransactionState.IDLE) update(sql) }
    override fun commit() { checkNotAutoCommit(); finishTransaction(dialect.commitSql()) }
    override fun rollback() { checkNotAutoCommit(); finishTransaction(dialect.rollbackSql()) }
    private fun checkNotAutoCommit() {
        checkOpen()
        if (autoCommit) throw SQLException("The operation is not allowed in auto-commit mode", "25000")
    }

    override fun setSavepoint(): Savepoint = newSavepoint(null)
    override fun setSavepoint(p0: String?): Savepoint = newSavepoint(p0)
    private fun newSavepoint(name: String?): Savepoint {
        checkNotAutoCommit()
        beforeStatement()
        val id = ++savepointCounter
        val point = JdbcSavepoint(id, name)
        run { update(dialect.savepointSql(point.serverName)) }
        return point
    }
    override fun rollback(p0: Savepoint?) { checkNotAutoCommit(); run { update(dialect.rollbackToSavepointSql((p0 as JdbcSavepoint).serverName)) } }
    override fun releaseSavepoint(p0: Savepoint?) { checkNotAutoCommit(); run { update(dialect.releaseSavepointSql((p0 as JdbcSavepoint).serverName)) } }

    override fun getTransactionIsolation(): Int {
        checkOpen()
        val text = run { query(dialect.showIsolationSql()).first().getText(0)!! }
        return when (IsolationLevel.ofSql(text)) {
            IsolationLevel.READ_UNCOMMITTED -> Connection.TRANSACTION_READ_UNCOMMITTED
            IsolationLevel.READ_COMMITTED -> Connection.TRANSACTION_READ_COMMITTED
            IsolationLevel.REPEATABLE_READ -> Connection.TRANSACTION_REPEATABLE_READ
            IsolationLevel.SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE
            null -> throw SQLException("Unknown isolation level '$text'")
        }
    }
    override fun setTransactionIsolation(p0: Int) {
        checkOpen()
        val level = when (p0) {
            Connection.TRANSACTION_READ_UNCOMMITTED -> IsolationLevel.READ_UNCOMMITTED
            Connection.TRANSACTION_READ_COMMITTED -> IsolationLevel.READ_COMMITTED
            Connection.TRANSACTION_REPEATABLE_READ -> IsolationLevel.REPEATABLE_READ
            Connection.TRANSACTION_SERIALIZABLE -> IsolationLevel.SERIALIZABLE
            else -> throw SQLException("Invalid isolation level $p0", "HY092")
        }
        run { update(dialect.setIsolationSql(level)) }
    }

    override fun isReadOnly(): Boolean { checkOpen(); return readOnly }
    override fun setReadOnly(p0: Boolean) {
        checkOpen()
        if (p0 == readOnly) return
        run { update(dialect.setReadOnlySql(p0)) }
        readOnly = p0
    }

    override fun getSchema(): String? {
        checkOpen()
        val sql = dialect.currentSchemaSql() ?: return null
        return run { query(sql).first().getText(0) }
    }
    override fun setSchema(p0: String?) {
        checkOpen()
        val sql = dialect.setSchemaSql(p0 ?: return) ?: return
        run { update(sql) }
    }
    override fun getCatalog(): String? {
        checkOpen()
        val sql = dialect.currentCatalogSql() ?: return null
        return run { query(sql).first().getText(0) }
    }
    override fun setCatalog(p0: String?) {
        checkOpen()
        val sql = dialect.setCatalogSql(p0 ?: return) ?: return // JDBC says to ignore this where unsupported
        run { update(sql) }
    }

    override fun getHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun setHoldability(p0: Int) { checkOpen() }
    override fun getTypeMap(): MutableMap<String, Class<*>> = HashMap()
    override fun setTypeMap(p0: MutableMap<String, Class<*>>?) = unsupported("A custom type map")
    override fun getMetaData(): java.sql.DatabaseMetaData { checkOpen(); return JdbcDatabaseMetaData(this) }
    override fun createClob(): java.sql.Clob { checkOpen(); return JdbcClob() }
    override fun createNClob(): java.sql.NClob { checkOpen(); return JdbcClob() }
    override fun createBlob(): java.sql.Blob { checkOpen(); return JdbcBlob() }
    override fun createArrayOf(p0: String?, p1: Array<out Any>?): java.sql.Array = JdbcArray(p0 ?: "", p1?.toList().orEmpty())

    override fun isValid(p0: Int): Boolean {
        if (p0 < 0) throw SQLException("Timeout must not be negative", "HY090")
        if (core.isClosed) return false
        return lock.withLock {
            try {
                core.setReadTimeout(p0 * 1000L)
                runUnsuspended { core.ping() }
            } finally {
                if (!core.isClosed) core.setReadTimeout(networkTimeout.toLong())
            }
        }
    }
    override fun getNetworkTimeout() = networkTimeout
    override fun setNetworkTimeout(p0: Executor?, p1: Int) {
        lock.withLock { core.setReadTimeout(p1.toLong()); networkTimeout = p1 }
    }
    override fun getClientInfo(p0: String?): String? = clientInfo.getProperty(p0)
    override fun getClientInfo(): Properties = Properties().apply { putAll(clientInfo) }
    override fun setClientInfo(p0: String?, p1: String?) {
        if (p0 == null) return
        if (p1 == null) clientInfo.remove(p0) else clientInfo.setProperty(p0, p1)
    }
    override fun setClientInfo(p0: Properties?) { clientInfo.clear(); p0?.let(clientInfo::putAll) }

    override fun isClosed() = core.isClosed
    override fun close() { lock.withLock { runUnsuspended { core.close() } } }
    override fun abort(p0: Executor?) {
        // Closes the socket rather than negotiating, even if another thread holds the connection.
        (p0 ?: Executor { it.run() }).execute { runCatching { close() } }
    }
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}

private class JdbcSavepoint(private val id: Int, private val name: String?) : Savepoint {
    val serverName get() = name ?: "kotsql_sp_$id"
    override fun getSavepointId(): Int = if (name == null) id else throw SQLException("A named savepoint has no id")
    override fun getSavepointName(): String = name ?: throw SQLException("An unnamed savepoint has no name")
}

internal fun chain(messages: List<String>): SQLWarning? {
    var head: SQLWarning? = null
    for (m in messages) {
        val w = SQLWarning(m)
        if (head == null) head = w else head.setNextWarning(w)
    }
    return head
}

/** An in-memory row source, for generated keys and empty metadata results. */
internal fun listRows(columns: List<ru.ttraum.kotsql.ColumnInfo>, rows: List<SqlRow>): SqlResult.Rows = ru.ttraum.kotsql.spi.ListRows(columns, rows)
