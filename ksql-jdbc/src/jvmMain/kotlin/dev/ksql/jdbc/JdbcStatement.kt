package dev.ksql.jdbc

import dev.ksql.BatchItem
import dev.ksql.ColumnInfo
import dev.ksql.ExecuteOptions
import dev.ksql.JvmValues
import dev.ksql.SqlCursor
import dev.ksql.SqlResult
import dev.ksql.SqlRow
import dev.ksql.SqlType
import dev.ksql.TypedNull
import dev.ksql.spi.runUnsuspended
import kotlinx.datetime.toKotlinLocalDateTime
import java.io.InputStream
import java.io.Reader
import java.math.BigDecimal
import java.sql.Date
import java.sql.ParameterMetaData
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.sql.Types
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val timeoutScheduler: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { Thread(it, "ksql-query-timeout").apply { isDaemon = true } }
}

/** A plain `Statement`, and the base of [JdbcPreparedStatement]. Its `setXxx` methods stay unsupported here. */
internal open class JdbcStatement(val conn: JdbcConnection, private val resultSetType: Int = ResultSet.TYPE_FORWARD_ONLY) : BasePreparedStatement() {
    private var cursor: SqlCursor? = null
    private var resultSet: JdbcResultSet? = null
    private var updateCount = -1L
    private var generatedKeys: JdbcResultSet? = null
    private var keepsGeneratedKeys = false
    private var closed = false
    private var maxRows = 0L
    private var fetchSize = 0
    private var queryTimeout = 0
    private var closeOnCompletion = false
    private val staticBatch = ArrayList<BatchItem>()

    protected fun checkOpen() {
        if (closed) throw SQLException("The statement is closed", "HY010")
        if (conn.isClosed) throw SQLException("The connection is closed", "08003")
    }

    private fun closeResults() {
        resultSet?.closeQuietly()
        resultSet = null
        generatedKeys = null
        updateCount = -1
        cursor?.let { c -> if (!conn.isClosed) conn.run { c.close() } }
        cursor = null
    }

    /** @param prepared the SQL uses bound parameters, so it must go through the extended protocol */
    protected fun runStatement(sql: String, params: List<Any?>, prepared: Boolean, keepKeys: Boolean): Boolean {
        checkOpen()
        closeResults()
        conn.beforeStatement()
        keepsGeneratedKeys = keepKeys
        val timer = queryTimeout.takeIf { it > 0 }?.let {
            timeoutScheduler.schedule({ runCatching { runUnsuspended { conn.core.cancel() } } }, it.toLong(), TimeUnit.SECONDS)
        }
        try {
            cursor = conn.run {
                if (prepared || fetchSize > 0) execute(sql, params, ExecuteOptions(fetchSize)) else executeScript(sql)
            }
            return advance()
        } finally {
            timer?.cancel(false)
        }
    }

    /** Moves to the next result. Returns true if it is a result set. */
    private fun advance(): Boolean {
        resultSet = null
        updateCount = -1
        val c = cursor ?: return false
        when (val result = conn.run { c.nextResult() }) {
            null -> cursor = null
            is SqlResult.UpdateCount -> {
                updateCount = result.count
                if (keepsGeneratedKeys && result.generatedKeys.isNotEmpty()) {
                    generatedKeys = JdbcResultSet(this, dev.ksql.spi.generatedKeyRows(result.generatedKeys))
                }
            }
            is SqlResult.Rows -> {
                if (keepsGeneratedKeys) {
                    val rows = ArrayList<SqlRow>()
                    conn.run { while (true) rows += result.next() ?: break }
                    generatedKeys = JdbcResultSet(this, listRows(result.columns, rows))
                    updateCount = result.updateCount ?: rows.size.toLong()
                } else {
                    // Servers announce a result's columns before running the query, so the first row is what shows that
                    // it has run. JDBC code expects failures and query timeouts here rather than in ResultSet.next().
                    val first = conn.run { result.next() }
                    resultSet = JdbcResultSet(this, Prefetched(result, first), maxRows = maxRows, type = resultSetType)
                    return true
                }
            }
        }
        return false
    }

    override fun execute(p0: String?) = runStatement(p0!!, emptyList(), prepared = false, keepKeys = false)
    override fun execute(p0: String?, p1: Int) = withKeys(p0!!, if (p1 == Statement.RETURN_GENERATED_KEYS) emptyList() else null)
    override fun execute(p0: String?, p1: Array<out String>?) = withKeys(p0!!, p1?.toList().orEmpty())
    override fun execute(p0: String?, p1: IntArray?): Boolean = unsupported("Generated keys by column index")

    private fun withKeys(sql: String, keys: List<String>?): Boolean {
        // A statement with no generated values, such as a SELECT, keeps its rows.
        val rewritten = keys?.let { conn.dialect.withGeneratedKeys(sql, it) }
        return runStatement(rewritten ?: sql, emptyList(), prepared = false, keepKeys = rewritten != null)
    }

    override fun executeQuery(p0: String?): ResultSet {
        execute(p0)
        return resultSet ?: throw SQLException("The statement did not return a result set", "02000")
    }
    override fun executeUpdate(p0: String?): Int = executeLargeUpdate(p0).clampToInt()
    override fun executeUpdate(p0: String?, p1: Int): Int = executeLargeUpdate(p0, p1).clampToInt()
    override fun executeUpdate(p0: String?, p1: IntArray?): Int = unsupported("Generated keys by column index")
    override fun executeUpdate(p0: String?, p1: Array<out String>?): Int = executeLargeUpdate(p0, p1).clampToInt()
    override fun executeLargeUpdate(sql: String?): Long { execute(sql); return requireCount() }
    override fun executeLargeUpdate(sql: String?, autoGeneratedKeys: Int): Long { execute(sql, autoGeneratedKeys); return requireCount() }
    override fun executeLargeUpdate(sql: String?, columnNames: Array<out String>?): Long { execute(sql, columnNames); return requireCount() }
    override fun executeLargeUpdate(sql: String?, columnIndexes: IntArray?): Long = unsupported("Generated keys by column index")

    protected fun requireCount(): Long {
        if (resultSet != null) throw SQLException("The statement returned a result set where none was expected", "0100E")
        return maxOf(updateCount, 0)
    }

    protected fun Long.clampToInt(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun getResultSet(): ResultSet? { checkOpen(); return resultSet }
    override fun getUpdateCount(): Int { checkOpen(); return if (updateCount < 0) -1 else updateCount.clampToInt() }
    override fun getLargeUpdateCount(): Long { checkOpen(); return updateCount }
    override fun getMoreResults(): Boolean = getMoreResults(Statement.CLOSE_CURRENT_RESULT)
    override fun getMoreResults(p0: Int): Boolean {
        checkOpen()
        if (p0 == Statement.CLOSE_CURRENT_RESULT) resultSet?.closeQuietly()
        return advance()
    }
    override fun getGeneratedKeys(): ResultSet { checkOpen(); return generatedKeys ?: JdbcResultSet(this, listRows(emptyList(), emptyList())) }

    override fun addBatch(p0: String?) { checkOpen(); staticBatch += BatchItem(p0!!) }
    override fun clearBatch() { checkOpen(); staticBatch.clear() }
    override fun executeBatch(): IntArray = executeLargeBatch().let { counts -> IntArray(counts.size) { counts[it].clampToInt() } }
    override fun executeLargeBatch(): LongArray {
        checkOpen()
        val items = staticBatch.toList()
        staticBatch.clear()
        return runBatch(items)
    }
    protected fun runBatch(items: List<BatchItem>): LongArray {
        closeResults()
        if (items.isEmpty()) return LongArray(0)
        conn.beforeStatement()
        return conn.run { executeBatch(items) }
    }

    override fun cancel() { checkOpen(); translating { runUnsuspended { conn.core.cancel() } } }
    override fun close() {
        if (closed) return
        try { closeResults() } finally { closed = true }
    }
    override fun isClosed() = closed
    internal fun resultSetClosed() { if (closeOnCompletion && cursor == null) close() }
    override fun closeOnCompletion() { checkOpen(); closeOnCompletion = true }
    override fun isCloseOnCompletion(): Boolean { checkOpen(); return closeOnCompletion }
    override fun getConnection(): java.sql.Connection { checkOpen(); return conn }
    override fun getWarnings(): SQLWarning? { checkOpen(); return conn.warnings() }
    override fun clearWarnings() { checkOpen() }
    override fun getMaxRows(): Int { checkOpen(); return maxRows.clampToInt() }
    override fun setMaxRows(p0: Int) { checkOpen(); require0(p0); maxRows = p0.toLong() }
    override fun getLargeMaxRows(): Long { checkOpen(); return maxRows }
    override fun setLargeMaxRows(max: Long) { checkOpen(); maxRows = max }
    override fun getFetchSize(): Int { checkOpen(); return fetchSize }
    override fun setFetchSize(p0: Int) { checkOpen(); require0(p0); fetchSize = p0 }
    override fun getFetchDirection() = ResultSet.FETCH_FORWARD
    override fun setFetchDirection(p0: Int) { if (p0 != ResultSet.FETCH_FORWARD) unsupported("Reverse fetching") }
    override fun getQueryTimeout(): Int { checkOpen(); return queryTimeout }
    override fun setQueryTimeout(p0: Int) { checkOpen(); require0(p0); queryTimeout = p0 }
    override fun getMaxFieldSize() = 0
    override fun setMaxFieldSize(p0: Int) { checkOpen() }
    override fun setEscapeProcessing(p0: Boolean) { checkOpen() }
    override fun setCursorName(p0: String?) { checkOpen() }
    override fun getResultSetType() = resultSetType
    override fun getResultSetConcurrency() = ResultSet.CONCUR_READ_ONLY
    override fun getResultSetHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun isPoolable() = false
    override fun setPoolable(p0: Boolean) { checkOpen() }
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)

    private fun require0(v: Int) { if (v < 0) throw SQLException("The value must not be negative", "HY024") }
}

/** Puts back a row that has already been read. */
private class Prefetched(private val rows: SqlResult.Rows, private var first: SqlRow?) : SqlResult.Rows {
    override val columns get() = rows.columns
    override val updateCount get() = rows.updateCount
    override suspend fun next(): SqlRow? = first?.also { first = null } ?: rows.next()
    override fun nextBuffered(): SqlRow? = first?.also { first = null } ?: rows.nextBuffered()
}

internal class JdbcPreparedStatement(
    conn: JdbcConnection,
    private val nativeSql: String,
    private val parameterCount: Int,
    private val keys: List<String>?,
    type: Int = ResultSet.TYPE_FORWARD_ONLY,
) : JdbcStatement(conn, type) {
    private var params = arrayOfNulls<Any?>(parameterCount)
    private var bound = BooleanArray(parameterCount)
    private val batch = ArrayList<List<Any?>>()

    private val keysSql = keys?.let { conn.dialect.withGeneratedKeys(nativeSql, it) }
    private fun effectiveSql() = keysSql ?: nativeSql

    private fun currentParams(): List<Any?> {
        val missing = bound.indexOfFirst { !it }
        if (missing >= 0) throw SQLException("No value specified for parameter ${missing + 1}", "07002")
        return params.toList()
    }

    private fun set(index: Int, value: Any?) {
        checkOpen()
        if (index !in 1..parameterCount) throw SQLException("Parameter index $index is out of range 1..$parameterCount", "07009")
        params[index - 1] = translating { JvmValues.toCanonical(conn.core.codecs.encode(value)) }
        bound[index - 1] = true
    }

    override fun execute(): Boolean = runStatement(effectiveSql(), currentParams(), prepared = true, keepKeys = keysSql != null)
    override fun executeQuery(): ResultSet {
        execute()
        return resultSet ?: throw SQLException("The statement did not return a result set", "02000")
    }
    override fun executeUpdate(): Int = executeLargeUpdate().clampToInt()
    override fun executeLargeUpdate(): Long { execute(); return requireCount() }

    override fun addBatch() { checkOpen(); batch += currentParams() }
    override fun clearBatch() { checkOpen(); batch.clear() }
    override fun executeLargeBatch(): LongArray {
        checkOpen()
        val sql = effectiveSql()
        val items = batch.map { BatchItem(sql, it) }
        batch.clear()
        return runBatch(items)
    }
    override fun clearParameters() { checkOpen(); params = arrayOfNulls(parameterCount); bound = BooleanArray(parameterCount) }

    private val notForPrepared get() = SQLException("This method cannot be called on a PreparedStatement", "HY000")
    override fun execute(p0: String?): Boolean = throw notForPrepared
    override fun executeQuery(p0: String?): ResultSet = throw notForPrepared
    override fun addBatch(p0: String?) = throw notForPrepared

    override fun getMetaData(): ResultSetMetaData? {
        checkOpen()
        val columns = conn.run { describe(nativeSql) }.columns
        return if (columns.isEmpty()) null else JdbcResultSetMetaData(columns)
    }
    override fun getParameterMetaData(): ParameterMetaData {
        checkOpen()
        return JdbcParameterMetaData(conn.run { describe(nativeSql) }.parameterTypes)
    }

    override fun setNull(p0: Int, p1: Int) = set(p0, sqlTypeOfJdbc(p1)?.let(::TypedNull))
    override fun setNull(p0: Int, p1: Int, p2: String?) = setNull(p0, p1)
    override fun setBoolean(p0: Int, p1: Boolean) = set(p0, p1)
    override fun setByte(p0: Int, p1: Byte) = set(p0, p1)
    override fun setShort(p0: Int, p1: Short) = set(p0, p1)
    override fun setInt(p0: Int, p1: Int) = set(p0, p1)
    override fun setLong(p0: Int, p1: Long) = set(p0, p1)
    override fun setFloat(p0: Int, p1: Float) = set(p0, p1)
    override fun setDouble(p0: Int, p1: Double) = set(p0, p1)
    override fun setBigDecimal(p0: Int, p1: BigDecimal?) = set(p0, p1)
    override fun setString(p0: Int, p1: String?) = set(p0, p1)
    override fun setNString(p0: Int, p1: String?) = set(p0, p1)
    override fun setBytes(p0: Int, p1: ByteArray?) = set(p0, p1)
    override fun setDate(p0: Int, p1: Date?) = set(p0, p1)
    override fun setTime(p0: Int, p1: Time?) = set(p0, p1)
    override fun setTimestamp(p0: Int, p1: Timestamp?) = set(p0, p1)
    override fun setDate(p0: Int, p1: Date?, p2: Calendar?) = set(p0, p1?.let { atZone(it.time, p2).toLocalDate() })
    override fun setTime(p0: Int, p1: Time?, p2: Calendar?) = set(p0, p1?.let { atZone(it.time, p2).toLocalTime() })
    override fun setTimestamp(p0: Int, p1: Timestamp?, p2: Calendar?) = set(p0, p1?.let {
        if (p2 == null) it else it.toInstant().atZone(p2.timeZone.toZoneId()).toLocalDateTime().toKotlinLocalDateTime()
    })
    private fun atZone(millis: Long, calendar: Calendar?) =
        java.time.Instant.ofEpochMilli(millis).atZone(calendar?.timeZone?.toZoneId() ?: java.time.ZoneId.systemDefault())

    override fun setObject(p0: Int, p1: Any?) = set(p0, unwrapArray(p1))
    override fun setObject(p0: Int, p1: Any?, p2: Int) = when {
        p1 == null -> setNull(p0, p2)
        p2 == Types.VARCHAR || p2 == Types.LONGVARCHAR || p2 == Types.CHAR || p2 == Types.NVARCHAR -> set(p0, p1.toString())
        else -> set(p0, unwrapArray(p1))
    }
    override fun setObject(p0: Int, p1: Any?, p2: Int, p3: Int) = setObject(p0, p1, p2)
    private fun unwrapArray(v: Any?): Any? = if (v is java.sql.Array) v.array else v
    override fun setArray(p0: Int, p1: java.sql.Array?) = set(p0, p1?.array)
    override fun setBinaryStream(p0: Int, p1: InputStream?) = set(p0, p1?.readBytes())
    override fun setBinaryStream(p0: Int, p1: InputStream?, p2: Int) = set(p0, p1?.readNBytes(p2))
    override fun setBinaryStream(p0: Int, p1: InputStream?, p2: Long) = set(p0, p1?.readNBytes(p2.toInt()))
    override fun setAsciiStream(p0: Int, p1: InputStream?) = set(p0, p1?.readBytes()?.decodeToString())
    override fun setAsciiStream(p0: Int, p1: InputStream?, p2: Int) = set(p0, p1?.readNBytes(p2)?.decodeToString())
    override fun setAsciiStream(p0: Int, p1: InputStream?, p2: Long) = set(p0, p1?.readNBytes(p2.toInt())?.decodeToString())
    override fun setCharacterStream(p0: Int, p1: Reader?) = set(p0, p1?.readText())
    override fun setCharacterStream(p0: Int, p1: Reader?, p2: Int) = set(p0, p1?.readText())
    override fun setCharacterStream(p0: Int, p1: Reader?, p2: Long) = set(p0, p1?.readText())
    override fun setNCharacterStream(p0: Int, p1: Reader?) = set(p0, p1?.readText())
    override fun setNCharacterStream(p0: Int, p1: Reader?, p2: Long) = set(p0, p1?.readText())
    override fun setURL(p0: Int, p1: java.net.URL?) = set(p0, p1?.toString())
    override fun setClob(p0: Int, p1: java.sql.Clob?) = set(p0, p1?.let { it.getSubString(1, it.length().toInt()) })
    override fun setClob(p0: Int, p1: Reader?) = set(p0, p1?.readText())
    override fun setClob(p0: Int, p1: Reader?, p2: Long) = set(p0, p1?.readText())
    override fun setNClob(p0: Int, p1: java.sql.NClob?) = setClob(p0, p1)
    override fun setNClob(p0: Int, p1: Reader?) = set(p0, p1?.readText())
    override fun setNClob(p0: Int, p1: Reader?, p2: Long) = set(p0, p1?.readText())
    override fun setBlob(p0: Int, p1: java.sql.Blob?) = set(p0, p1?.let { it.getBytes(1, it.length().toInt()) })
    override fun setBlob(p0: Int, p1: InputStream?) = set(p0, p1?.readBytes())
    override fun setBlob(p0: Int, p1: InputStream?, p2: Long) = set(p0, p1?.readNBytes(p2.toInt()))
}

private fun sqlTypeOfJdbc(type: Int): SqlType? = when (type) {
    Types.BIT, Types.BOOLEAN -> SqlType.BOOLEAN
    Types.TINYINT, Types.SMALLINT -> SqlType.SMALLINT
    Types.INTEGER -> SqlType.INTEGER
    Types.BIGINT -> SqlType.BIGINT
    Types.REAL, Types.FLOAT -> SqlType.REAL
    Types.DOUBLE -> SqlType.DOUBLE
    Types.DECIMAL, Types.NUMERIC -> SqlType.DECIMAL
    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> SqlType.BINARY
    Types.DATE -> SqlType.DATE
    Types.TIME -> SqlType.TIME
    Types.TIME_WITH_TIMEZONE -> SqlType.TIME_TZ
    Types.TIMESTAMP -> SqlType.TIMESTAMP
    Types.TIMESTAMP_WITH_TIMEZONE -> SqlType.TIMESTAMP_TZ
    else -> null
}

internal fun jdbcTypeOf(type: SqlType): Int = when (type) {
    SqlType.BOOLEAN -> Types.BOOLEAN
    SqlType.SMALLINT -> Types.SMALLINT
    SqlType.INTEGER -> Types.INTEGER
    SqlType.BIGINT -> Types.BIGINT
    SqlType.REAL -> Types.REAL
    SqlType.DOUBLE -> Types.DOUBLE
    SqlType.DECIMAL -> Types.NUMERIC
    SqlType.CHAR -> Types.CHAR
    SqlType.VARCHAR, SqlType.TEXT -> Types.VARCHAR
    SqlType.BINARY -> Types.BINARY
    SqlType.DATE -> Types.DATE
    SqlType.TIME -> Types.TIME
    SqlType.TIME_TZ -> Types.TIME_WITH_TIMEZONE
    SqlType.TIMESTAMP -> Types.TIMESTAMP
    SqlType.TIMESTAMP_TZ -> Types.TIMESTAMP_WITH_TIMEZONE
    SqlType.ARRAY -> Types.ARRAY
    SqlType.UUID, SqlType.JSON, SqlType.INTERVAL, SqlType.OTHER -> Types.OTHER
}

internal fun javaClassNameOf(type: SqlType): String = when (type) {
    SqlType.BOOLEAN -> "java.lang.Boolean"
    SqlType.SMALLINT, SqlType.INTEGER -> "java.lang.Integer"
    SqlType.BIGINT -> "java.lang.Long"
    SqlType.REAL -> "java.lang.Float"
    SqlType.DOUBLE -> "java.lang.Double"
    SqlType.DECIMAL -> "java.math.BigDecimal"
    SqlType.BINARY -> "[B"
    SqlType.DATE -> "java.sql.Date"
    SqlType.TIME, SqlType.TIME_TZ -> "java.sql.Time"
    SqlType.TIMESTAMP, SqlType.TIMESTAMP_TZ -> "java.sql.Timestamp"
    SqlType.UUID -> "java.util.UUID"
    SqlType.ARRAY -> "java.sql.Array"
    else -> "java.lang.String"
}

internal class JdbcResultSetMetaData(private val columns: List<ColumnInfo>) : ResultSetMetaData {
    private fun col(i: Int) = columns.getOrNull(i - 1) ?: throw SQLException("Column index $i is out of range", "07009")
    override fun getColumnCount() = columns.size
    override fun getColumnName(p0: Int) = col(p0).name
    override fun getColumnLabel(p0: Int) = col(p0).name
    override fun getColumnType(p0: Int) = jdbcTypeOf(col(p0).type)
    override fun getColumnTypeName(p0: Int) = col(p0).nativeTypeName
    override fun getColumnClassName(p0: Int) = javaClassNameOf(col(p0).type)
    override fun getPrecision(p0: Int) = col(p0).precision
    override fun getScale(p0: Int) = col(p0).scale
    override fun getColumnDisplaySize(p0: Int) = col(p0).precision.takeIf { it > 0 } ?: 40
    override fun isNullable(p0: Int) = when (col(p0).nullable) {
        true -> ResultSetMetaData.columnNullable
        false -> ResultSetMetaData.columnNoNulls
        null -> ResultSetMetaData.columnNullableUnknown
    }
    override fun isSigned(p0: Int) = !col(p0).unsigned && col(p0).type in setOf(SqlType.SMALLINT, SqlType.INTEGER, SqlType.BIGINT, SqlType.REAL, SqlType.DOUBLE, SqlType.DECIMAL)
    override fun isAutoIncrement(p0: Int): Boolean { col(p0); return false }
    override fun isCaseSensitive(p0: Int) = col(p0).type in setOf(SqlType.CHAR, SqlType.VARCHAR, SqlType.TEXT)
    override fun isSearchable(p0: Int): Boolean { col(p0); return true }
    override fun isCurrency(p0: Int): Boolean { col(p0); return false }
    override fun getSchemaName(p0: Int): String { col(p0); return "" }
    override fun getTableName(p0: Int): String { col(p0); return "" }
    override fun getCatalogName(p0: Int): String { col(p0); return "" }
    override fun isReadOnly(p0: Int): Boolean { col(p0); return false }
    override fun isWritable(p0: Int): Boolean { col(p0); return true }
    override fun isDefinitelyWritable(p0: Int): Boolean { col(p0); return false }
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}

private class JdbcParameterMetaData(private val types: List<SqlType>) : ParameterMetaData {
    private fun type(i: Int) = types.getOrNull(i - 1) ?: throw SQLException("Parameter index $i is out of range", "07009")
    override fun getParameterCount() = types.size
    override fun isNullable(p0: Int): Int { type(p0); return ParameterMetaData.parameterNullableUnknown }
    override fun isSigned(p0: Int) = type(p0) in setOf(SqlType.SMALLINT, SqlType.INTEGER, SqlType.BIGINT, SqlType.REAL, SqlType.DOUBLE, SqlType.DECIMAL)
    override fun getPrecision(p0: Int): Int { type(p0); return 0 }
    override fun getScale(p0: Int): Int { type(p0); return 0 }
    override fun getParameterType(p0: Int) = jdbcTypeOf(type(p0))
    override fun getParameterTypeName(p0: Int) = type(p0).name.lowercase()
    override fun getParameterClassName(p0: Int) = javaClassNameOf(type(p0))
    override fun getParameterMode(p0: Int): Int { type(p0); return ParameterMetaData.parameterModeIn }
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}

/** Read-only array value. */
internal class JdbcArray(private val typeName: String, private val values: List<Any?>) : java.sql.Array {
    override fun getBaseTypeName() = typeName
    override fun getBaseType() = Types.OTHER
    override fun getArray(): Any = values.toTypedArray<Any?>()
    override fun getArray(p0: MutableMap<String, Class<*>>?): Any = getArray()
    override fun getArray(p0: Long, p1: Int): Any = values.drop((p0 - 1).toInt()).take(p1).toTypedArray<Any?>()
    override fun getArray(p0: Long, p1: Int, p2: MutableMap<String, Class<*>>?): Any = getArray(p0, p1)
    override fun getResultSet(): ResultSet = unsupported("Array.getResultSet")
    override fun getResultSet(p0: MutableMap<String, Class<*>>?): ResultSet = unsupported("Array.getResultSet")
    override fun getResultSet(p0: Long, p1: Int): ResultSet = unsupported("Array.getResultSet")
    override fun getResultSet(p0: Long, p1: Int, p2: MutableMap<String, Class<*>>?): ResultSet = unsupported("Array.getResultSet")
    override fun free() = Unit
    override fun toString() = values.joinToString(",", "{", "}")
}
