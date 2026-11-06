package dev.ksql.jdbc

import dev.ksql.JvmValues
import dev.ksql.SqlDecimal
import dev.ksql.SqlResult
import dev.ksql.SqlRow
import dev.ksql.SqlType
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Date
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Time
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar

internal class JdbcResultSet(
    private val statement: JdbcStatement?,
    private val rows: SqlResult.Rows,
    private val connection: JdbcConnection? = statement?.conn,
    private val maxRows: Long = 0,
    private val onClose: (() -> Unit)? = null,
    private val type: Int = ResultSet.TYPE_FORWARD_ONLY,
) : BaseResultSet() {
    /** For a scrollable result set, all the rows, read when first needed. The server's rows can only go forward. */
    private val loaded: List<SqlRow> by lazy {
        buildList {
            while (maxRows <= 0 || size < maxRows) {
                add((if (connection != null) connection.nextRow(rows) else translating { dev.ksql.spi.runUnsuspended { rows.next() } }) ?: break)
            }
        }
    }
    private val scrollable get() = type != ResultSet.TYPE_FORWARD_ONLY

    /** The 1-based position in [loaded]: 0 is before the first row, size + 1 after the last. */
    private var position = 0

    private fun moveTo(target: Int): Boolean {
        checkOpen()
        if (!scrollable) throw SQLException("The result set is forward only", "24000")
        position = target.coerceIn(0, loaded.size + 1)
        row = loaded.getOrNull(position - 1)
        rowNumber = position.toLong()
        return row != null
    }

    override fun first() = moveTo(1)
    override fun last() = moveTo(loaded.size)
    override fun beforeFirst() { moveTo(0) }
    override fun afterLast() { moveTo(loaded.size + 1) }
    override fun previous() = moveTo(position - 1)
    override fun absolute(p0: Int) = moveTo(if (p0 >= 0) p0 else loaded.size + 1 + p0)
    override fun relative(p0: Int) = moveTo(position + p0)
    override fun isLast(): Boolean {
        checkOpen()
        if (!scrollable) unsupported("isLast on a forward-only result set")
        return position == loaded.size && position > 0
    }

    private var row: SqlRow? = null
    private var rowNumber = 0L
    private var afterLast = false
    private var closed = false
    private var wasNull = false
    private var fetchSize = 0
    private val columns get() = rows.columns

    private fun checkOpen() { if (closed) throw SQLException("The result set is closed", "HY010") }

    override fun next(): Boolean {
        checkOpen()
        if (scrollable) return moveTo(position + 1)
        if (afterLast) return false
        val next = when {
            maxRows > 0 && rowNumber >= maxRows -> null
            connection != null -> connection.nextRow(rows)
            else -> translating { dev.ksql.spi.runUnsuspended { rows.next() } }
        }
        row = next
        if (next == null) afterLast = true else rowNumber++
        return next != null
    }

    fun closeQuietly() { closed = true; row = null }
    override fun close() {
        if (closed) return
        closeQuietly()
        onClose?.invoke()
        statement?.resultSetClosed()
    }
    override fun isClosed() = closed

    private fun current(index: Int): SqlRow {
        checkOpen()
        val r = row ?: throw SQLException("The result set is not positioned on a row; call next()", "24000")
        if (index !in 1..columns.size) throw SQLException("Column index $index is out of range 1..${columns.size}", "07009")
        return r
    }
    private fun raw(index: Int): Any? = translating { current(index)[index - 1] }.also { wasNull = it == null }
    @Suppress("UNCHECKED_CAST")
    private fun <T> convert(index: Int, type: Class<T>): T? = translating {
        val value = raw(index)
        // An application's class, for which a codec can make what JvmValues knows nothing of.
        // The cheap checks first: `kotlin` makes an object, on a path that every getBigDecimal and date takes.
        if (value != null && !current(index).codecs.isEmpty && !type.isInstance(value) && current(index).codecs.forClass((type as Class<Any>).kotlin) != null) {
            current(index).get(index - 1, type.kotlin) as T?
        } else {
            JvmValues.convert(value, type)
        }
    }

    override fun wasNull(): Boolean { checkOpen(); return wasNull }
    override fun findColumn(p0: String?): Int {
        checkOpen()
        val i = columns.indexOfFirst { it.name.equals(p0, ignoreCase = true) }
        if (i < 0) throw SQLException("No column named '$p0'", "42703")
        return i + 1
    }

    override fun getString(p0: Int): String? = current(p0).getText(p0 - 1).also { wasNull = it == null }
    override fun getNString(p0: Int): String? = getString(p0)
    override fun getBoolean(p0: Int): Boolean {
        val r = current(p0)
        if (columns[p0 - 1].type != SqlType.BOOLEAN) return convert(p0, java.lang.Boolean::class.java)?.booleanValue() ?: false
        wasNull = r.isNull(p0 - 1)
        return r.booleanValue(p0 - 1)
    }

    /** An integer column read as an integer needs no boxing, and no decoding beyond the digits. */
    private fun integer(p0: Int, target: Class<out Number>): Long {
        val r = current(p0)
        return when (columns[p0 - 1].type) {
            SqlType.SMALLINT, SqlType.INTEGER, SqlType.BIGINT -> {
                wasNull = r.isNull(p0 - 1)
                translating { r.longValue(p0 - 1) }
            }
            else -> convert(p0, target)?.toLong() ?: 0
        }
    }
    override fun getByte(p0: Int) = integer(p0, java.lang.Byte::class.java as Class<out Number>).toByte()
    override fun getShort(p0: Int) = integer(p0, java.lang.Short::class.java as Class<out Number>).toShort()
    override fun getInt(p0: Int) = integer(p0, java.lang.Integer::class.java as Class<out Number>).toInt()
    override fun getLong(p0: Int) = integer(p0, java.lang.Long::class.java as Class<out Number>)
    override fun getFloat(p0: Int) = when (val v = raw(p0)) {
        null -> 0f
        is SqlDecimal -> v.text.toFloat()
        else -> translating { JvmValues.convert(v, java.lang.Float::class.java)!!.toFloat() }
    }
    override fun getDouble(p0: Int) = when (val v = raw(p0)) {
        null -> 0.0
        is SqlDecimal -> v.text.toDouble()
        else -> translating { JvmValues.convert(v, java.lang.Double::class.java)!!.toDouble() }
    }
    override fun getBigDecimal(p0: Int): BigDecimal? = convert(p0, BigDecimal::class.java)
    @Deprecated("Deprecated in JDBC") override fun getBigDecimal(p0: Int, p1: Int): BigDecimal? = getBigDecimal(p0)?.setScale(p1, RoundingMode.HALF_UP)
    override fun getBytes(p0: Int): ByteArray? = convert(p0, ByteArray::class.java)
    override fun getDate(p0: Int): Date? = convert(p0, Date::class.java)
    override fun getTime(p0: Int): Time? = convert(p0, Time::class.java)
    override fun getTimestamp(p0: Int): Timestamp? = convert(p0, Timestamp::class.java)
    override fun getDate(p0: Int, p1: Calendar?): Date? {
        val v = convert(p0, LocalDate::class.java) ?: return null
        return Date(v.atStartOfDay(zone(p1)).toInstant().toEpochMilli())
    }
    override fun getTime(p0: Int, p1: Calendar?): Time? {
        val v = convert(p0, LocalTime::class.java) ?: return null
        return Time(v.atDate(LocalDate.EPOCH).atZone(zone(p1)).toInstant().toEpochMilli())
    }
    override fun getTimestamp(p0: Int, p1: Calendar?): Timestamp? {
        // A value with a time zone is an instant already, so the calendar applies only to one without.
        if (columns[p0.coerceIn(1, columns.size) - 1].type != SqlType.TIMESTAMP) return getTimestamp(p0)
        val v = convert(p0, LocalDateTime::class.java) ?: return null
        return Timestamp.from(v.atZone(zone(p1)).toInstant())
    }
    private fun zone(c: Calendar?) = c?.timeZone?.toZoneId() ?: java.time.ZoneId.systemDefault()

    override fun getArray(p0: Int): java.sql.Array? {
        val v = raw(p0) ?: return null
        val list = v as? List<*> ?: throw SQLException("The column is not an array", "22023")
        return JdbcArray(columns[p0 - 1].nativeTypeName.removePrefix("_"), list.map(JvmValues::toJava))
    }
    override fun getClob(p0: Int): java.sql.Clob? = getString(p0)?.let(::JdbcClob)
    override fun getNClob(p0: Int): java.sql.NClob? = getString(p0)?.let(::JdbcClob)
    override fun getBlob(p0: Int): java.sql.Blob? = getBytes(p0)?.let(::JdbcBlob)
    override fun getClob(p0: String?) = getClob(findColumn(p0))
    override fun getNClob(p0: String?) = getNClob(findColumn(p0))
    override fun getBlob(p0: String?) = getBlob(findColumn(p0))
    override fun getBinaryStream(p0: Int): InputStream? = getBytes(p0)?.let(::ByteArrayInputStream)
    override fun getAsciiStream(p0: Int): InputStream? = getString(p0)?.let { ByteArrayInputStream(it.toByteArray(Charsets.US_ASCII)) }
    @Deprecated("Deprecated in JDBC") override fun getUnicodeStream(p0: Int): InputStream? = getString(p0)?.let { ByteArrayInputStream(it.toByteArray()) }
    override fun getCharacterStream(p0: Int): Reader? = getString(p0)?.let(::StringReader)
    override fun getNCharacterStream(p0: Int): Reader? = getCharacterStream(p0)
    override fun getURL(p0: Int): java.net.URL? = getString(p0)?.let { java.net.URI(it).toURL() }

    override fun getObject(p0: Int): Any? {
        val v = raw(p0) ?: return null
        return when (columns[p0 - 1].type) {
            SqlType.SMALLINT -> (v as Short).toInt() // as the JDBC specification maps it
            SqlType.DATE -> convert(p0, Date::class.java)
            SqlType.TIME -> convert(p0, Time::class.java)
            SqlType.TIMESTAMP, SqlType.TIMESTAMP_TZ -> convert(p0, Timestamp::class.java)
            SqlType.ARRAY -> getArray(p0)
            else -> JvmValues.toJava(v)
        }
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getObject(p0: Int, p1: Class<T>?): T = convert(p0, p1 ?: throw SQLException("type is null", "HY009")) as T
    override fun getObject(p0: Int, p1: MutableMap<String, Class<*>>?): Any? = getObject(p0)

    override fun getString(p0: String?) = getString(findColumn(p0))
    override fun getNString(p0: String?) = getNString(findColumn(p0))
    override fun getBoolean(p0: String?) = getBoolean(findColumn(p0))
    override fun getByte(p0: String?) = getByte(findColumn(p0))
    override fun getShort(p0: String?) = getShort(findColumn(p0))
    override fun getInt(p0: String?) = getInt(findColumn(p0))
    override fun getLong(p0: String?) = getLong(findColumn(p0))
    override fun getFloat(p0: String?) = getFloat(findColumn(p0))
    override fun getDouble(p0: String?) = getDouble(findColumn(p0))
    override fun getBigDecimal(p0: String?) = getBigDecimal(findColumn(p0))
    @Deprecated("Deprecated in JDBC") @Suppress("DEPRECATION") override fun getBigDecimal(p0: String?, p1: Int) = getBigDecimal(findColumn(p0), p1)
    override fun getBytes(p0: String?) = getBytes(findColumn(p0))
    override fun getDate(p0: String?) = getDate(findColumn(p0))
    override fun getTime(p0: String?) = getTime(findColumn(p0))
    override fun getTimestamp(p0: String?) = getTimestamp(findColumn(p0))
    override fun getDate(p0: String?, p1: Calendar?) = getDate(findColumn(p0), p1)
    override fun getTime(p0: String?, p1: Calendar?) = getTime(findColumn(p0), p1)
    override fun getTimestamp(p0: String?, p1: Calendar?) = getTimestamp(findColumn(p0), p1)
    override fun getArray(p0: String?) = getArray(findColumn(p0))
    override fun getBinaryStream(p0: String?) = getBinaryStream(findColumn(p0))
    override fun getAsciiStream(p0: String?) = getAsciiStream(findColumn(p0))
    @Deprecated("Deprecated in JDBC") @Suppress("DEPRECATION") override fun getUnicodeStream(p0: String?) = getUnicodeStream(findColumn(p0))
    override fun getCharacterStream(p0: String?) = getCharacterStream(findColumn(p0))
    override fun getNCharacterStream(p0: String?) = getNCharacterStream(findColumn(p0))
    override fun getURL(p0: String?) = getURL(findColumn(p0))
    override fun getObject(p0: String?) = getObject(findColumn(p0))
    override fun <T> getObject(p0: String?, p1: Class<T>?): T = getObject(findColumn(p0), p1)
    override fun getObject(p0: String?, p1: MutableMap<String, Class<*>>?) = getObject(findColumn(p0), p1)

    override fun getMetaData(): ResultSetMetaData { checkOpen(); return JdbcResultSetMetaData(columns) }
    override fun getStatement(): java.sql.Statement? { checkOpen(); return statement }
    override fun getRow(): Int { checkOpen(); return if (row == null) 0 else rowNumber.toInt() }
    override fun isBeforeFirst(): Boolean {
        checkOpen()
        if (scrollable) return position == 0 && loaded.isNotEmpty()
        return rowNumber == 0L && !afterLast
    }
    override fun isAfterLast(): Boolean {
        checkOpen()
        if (scrollable) return position == loaded.size + 1 && loaded.isNotEmpty()
        return afterLast && rowNumber > 0
    }
    override fun isFirst(): Boolean { checkOpen(); return rowNumber == 1L && row != null }
    override fun getType() = type
    override fun getConcurrency() = ResultSet.CONCUR_READ_ONLY
    override fun getHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun getFetchDirection() = ResultSet.FETCH_FORWARD
    override fun setFetchDirection(p0: Int) { if (p0 != ResultSet.FETCH_FORWARD && !scrollable) unsupported("Reverse fetching") }
    override fun getFetchSize(): Int { checkOpen(); return fetchSize }
    override fun setFetchSize(p0: Int) { checkOpen(); fetchSize = p0 } // a hint; the page size was fixed at execution
    override fun getWarnings(): SQLWarning? { checkOpen(); return null }
    override fun clearWarnings() { checkOpen() }
    override fun rowUpdated() = false
    override fun rowInserted() = false
    override fun rowDeleted() = false
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}

