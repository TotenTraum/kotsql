package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.jvm.JvmValues
import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.update
import kotlinx.coroutines.flow.toList
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
    private val updatable: UpdatableTarget? = null,
) : BaseResultSet() {
    /** For a scrollable or an updatable result set, all the rows, read when first needed. The server's rows only go forward. */
    private val loaded: MutableList<SqlRow> by lazy {
        val all = ArrayList<SqlRow>()
        while (maxRows <= 0 || all.size < maxRows) {
            all.add((if (connection != null) connection.nextRow(rows) else translating { ru.ttraum.kotsql.spi.runUnsuspended { rows.next() } }) ?: break)
        }
        all
    }
    private val scrollable get() = type != ResultSet.TYPE_FORWARD_ONLY || updatable != null

    /** The 1-based position in [loaded]: 0 is before the first row, size + 1 after the last. */
    private var position = 0

    private fun moveTo(target: Int): Boolean {
        checkOpen()
        if (!scrollable) throw SQLException("The result set is forward only", "24000")
        pendingChanges.clear()
        onInsertRow = false
        rowDeleted = false
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
            else -> translating { ru.ttraum.kotsql.spi.runUnsuspended { rows.next() } }
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
        // A value with a time zone is an instant already, so the calendar applies only to one without: a TIMESTAMP, or the text
        // of a date and time where the database keeps them as text.
        val local = columns[p0.coerceIn(1, columns.size) - 1].type == SqlType.TIMESTAMP || (raw(p0) as? String)?.let(JvmValues::isLocalDateTimeText) == true
        if (!local) return getTimestamp(p0)
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
            SqlType.INTEGER -> JvmValues.integer(v)
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
    override fun getConcurrency() = if (updatable != null) ResultSet.CONCUR_UPDATABLE else ResultSet.CONCUR_READ_ONLY
    override fun getHoldability() = ResultSet.HOLD_CURSORS_OVER_COMMIT
    override fun getFetchDirection() = ResultSet.FETCH_FORWARD
    override fun setFetchDirection(p0: Int) { if (p0 != ResultSet.FETCH_FORWARD && !scrollable) unsupported("Reverse fetching") }
    override fun getFetchSize(): Int { checkOpen(); return fetchSize }
    override fun setFetchSize(p0: Int) { checkOpen(); fetchSize = p0 } // a hint; the page size was fixed at execution
    override fun getWarnings(): SQLWarning? { checkOpen(); return null }
    override fun clearWarnings() { checkOpen() }
    override fun rowUpdated() = rowUpdated
    override fun rowInserted() = false
    override fun rowDeleted() = rowDeleted

    // ---- Changing rows ----

    /** The changes to the current row, by 1-based column, not yet sent. */
    private val pendingChanges = LinkedHashMap<Int, Any?>()
    private var onInsertRow = false
    private var rowUpdated = false
    private var rowDeleted = false
    private var rowBeforeInsert: SqlRow? = null

    private val target: UpdatableTarget
        get() = updatable ?: notUpdatable("it isn't CONCUR_UPDATABLE, or doesn't come from a plain query of one table")
    private val owner: JdbcConnection get() = connection ?: notUpdatable("it has no connection")

    private val primaryKey: List<String> by lazy {
        val t = target
        val qualifier = owner.dialect.tableQualifier
        val names = ArrayList<String>()
        owner.metaData.getPrimaryKeys(t.schema.takeIf { qualifier == ru.ttraum.kotsql.TableQualifier.CATALOG }, t.schema.takeIf { qualifier == ru.ttraum.kotsql.TableQualifier.SCHEMA }, t.table).use { keys ->
            while (keys.next()) names += keys.getString("COLUMN_NAME")
        }
        if (names.isEmpty()) notUpdatable("the table ${t.quotedName} has no primary key")
        for (name in names) if (columns.none { it.name == name }) notUpdatable("the query doesn't select the primary key column $name")
        names
    }

    private fun quoted(name: String) = owner.dialect.quoteIdentifier(name)

    private fun change(index: Int, value: Any?) {
        checkOpen()
        target
        if (index !in 1..columns.size) throw SQLException("Column index $index is out of range 1..${columns.size}", "07009")
        if (!onInsertRow && row == null) throw SQLException("The result set is not positioned on a row; call next()", "24000")
        pendingChanges[index] = translating { JvmValues.toCanonical(owner.core.codecs.encode(value)) }
        row = OverlayRow(columns, if (onInsertRow) null else loaded[position - 1], pendingChanges.toMap())
    }

    /** The WHERE clause that finds the current row, with its values. New values count, for reading the row back. */
    private fun whereCurrent(after: Boolean): Pair<String, List<Any?>> {
        val base = loaded[position - 1]
        val values = primaryKey.map { name ->
            val i = columns.indexOfFirst { it.name == name }
            if (after && i + 1 in pendingChanges) pendingChanges[i + 1] else translating { base[i] }
        }
        return primaryKey.joinToString(" AND ") { "${quoted(it)} = ?" } to values
    }

    private fun run(sql: String, params: List<Any?>): Long {
        val conn = owner
        conn.beforeStatement()
        val native = translating { conn.dialect.translatePositional(sql).sql }
        return conn.run { update(native, params) }
    }

    private fun checkOnRow() {
        checkOpen()
        if (onInsertRow) throw SQLException("This takes a row of the result, not the insert row", "24000")
        if (row == null) throw SQLException("The result set is not positioned on a row; call next()", "24000")
    }

    override fun updateRow() {
        checkOnRow()
        if (pendingChanges.isEmpty()) return
        val assignments = pendingChanges.keys.joinToString(", ") { "${quoted(columns[it - 1].name)} = ?" }
        val (where, keys) = whereCurrent(after = false)
        val count = run("UPDATE ${target.quotedName} SET $assignments WHERE $where", pendingChanges.values.toList() + keys)
        if (count != 1L) throw SQLException("The row is no longer there: $count rows changed", "01001")
        readBack()
        rowUpdated = true
    }

    /** Takes what the database has made of the row, with its defaults and triggers. */
    private fun readBack() {
        val conn = owner
        val (where, keys) = whereCurrent(after = true)
        val sql = "SELECT ${columns.joinToString(", ") { quoted(it.name) }} FROM ${target.quotedName} WHERE $where"
        val native = translating { conn.dialect.translatePositional(sql).sql }
        val fresh = conn.run { query(native, keys).toList() }.singleOrNull()
            ?: throw SQLException("The row is no longer there", "01001")
        loaded[position - 1] = fresh
        row = fresh
        pendingChanges.clear()
    }

    override fun refreshRow() {
        checkOnRow()
        target
        pendingChanges.clear()
        readBack()
    }

    override fun cancelRowUpdates() {
        checkOnRow()
        target
        pendingChanges.clear()
        row = loaded[position - 1]
    }

    override fun deleteRow() {
        checkOnRow()
        val (where, keys) = whereCurrent(after = false)
        val count = run("DELETE FROM ${target.quotedName} WHERE $where", keys)
        if (count != 1L) throw SQLException("The row is no longer there: $count rows changed", "01001")
        // The next row now has this one's place, and next() has to get there.
        loaded.removeAt(position - 1)
        position--
        rowNumber = position.toLong()
        row = null
        pendingChanges.clear()
        rowDeleted = true
    }

    override fun moveToInsertRow() {
        checkOpen()
        target
        loaded // reads what's left, as the connection is about to be needed for something else
        if (!onInsertRow) rowBeforeInsert = row
        onInsertRow = true
        pendingChanges.clear()
        row = OverlayRow(columns, null, emptyMap())
    }

    override fun moveToCurrentRow() {
        checkOpen()
        target
        if (!onInsertRow) return
        onInsertRow = false
        pendingChanges.clear()
        row = rowBeforeInsert
    }

    override fun insertRow() {
        checkOpen()
        if (!onInsertRow) throw SQLException("This takes the insert row: call moveToInsertRow()", "24000")
        if (pendingChanges.isEmpty()) throw SQLException("The insert row has no values", "07002")
        val names = pendingChanges.keys.joinToString(", ") { quoted(columns[it - 1].name) }
        val marks = pendingChanges.keys.joinToString(", ") { "?" }
        run("INSERT INTO ${target.quotedName} ($names) VALUES ($marks)", pendingChanges.values.toList())
        pendingChanges.clear()
        row = OverlayRow(columns, null, emptyMap())
    }

    override fun updateArray(p0: String?, p1: java.sql.Array?) = updateArray(findColumn(p0), p1)
    override fun updateArray(p0: Int, p1: java.sql.Array?) = change(p0, p1?.array)
    override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?, p2: Int) = updateAsciiStream(findColumn(p0), p1, p2)
    override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?, p2: Long) = updateAsciiStream(findColumn(p0), p1, p2)
    override fun updateAsciiStream(p0: String?, p1: java.io.InputStream?) = updateAsciiStream(findColumn(p0), p1)
    override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?, p2: Int) = change(p0, p1?.readNBytes(p2.toInt())?.decodeToString())
    override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?, p2: Long) = change(p0, p1?.readNBytes(p2.toInt())?.decodeToString())
    override fun updateAsciiStream(p0: Int, p1: java.io.InputStream?) = change(p0, p1?.readBytes()?.decodeToString())
    override fun updateBigDecimal(p0: String?, p1: java.math.BigDecimal?) = updateBigDecimal(findColumn(p0), p1)
    override fun updateBigDecimal(p0: Int, p1: java.math.BigDecimal?) = change(p0, p1)
    override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?, p2: Int) = updateBinaryStream(findColumn(p0), p1, p2)
    override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?, p2: Long) = updateBinaryStream(findColumn(p0), p1, p2)
    override fun updateBinaryStream(p0: String?, p1: java.io.InputStream?) = updateBinaryStream(findColumn(p0), p1)
    override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?, p2: Int) = change(p0, p1?.readNBytes(p2.toInt()))
    override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?, p2: Long) = change(p0, p1?.readNBytes(p2.toInt()))
    override fun updateBinaryStream(p0: Int, p1: java.io.InputStream?) = change(p0, p1?.readBytes())
    override fun updateBlob(p0: String?, p1: java.io.InputStream?, p2: Long) = updateBlob(findColumn(p0), p1, p2)
    override fun updateBlob(p0: String?, p1: java.io.InputStream?) = updateBlob(findColumn(p0), p1)
    override fun updateBlob(p0: String?, p1: java.sql.Blob?) = updateBlob(findColumn(p0), p1)
    override fun updateBlob(p0: Int, p1: java.io.InputStream?, p2: Long) = change(p0, p1?.readNBytes(p2.toInt()))
    override fun updateBlob(p0: Int, p1: java.io.InputStream?) = change(p0, p1?.readBytes())
    override fun updateBlob(p0: Int, p1: java.sql.Blob?) = change(p0, p1?.let { it.getBytes(1, it.length().toInt()) })
    override fun updateBoolean(p0: String?, p1: Boolean) = updateBoolean(findColumn(p0), p1)
    override fun updateBoolean(p0: Int, p1: Boolean) = change(p0, p1)
    override fun updateByte(p0: String?, p1: Byte) = updateByte(findColumn(p0), p1)
    override fun updateByte(p0: Int, p1: Byte) = change(p0, p1)
    override fun updateBytes(p0: String?, p1: ByteArray?) = updateBytes(findColumn(p0), p1)
    override fun updateBytes(p0: Int, p1: ByteArray?) = change(p0, p1)
    override fun updateCharacterStream(p0: String?, p1: java.io.Reader?, p2: Int) = updateCharacterStream(findColumn(p0), p1, p2)
    override fun updateCharacterStream(p0: String?, p1: java.io.Reader?, p2: Long) = updateCharacterStream(findColumn(p0), p1, p2)
    override fun updateCharacterStream(p0: String?, p1: java.io.Reader?) = updateCharacterStream(findColumn(p0), p1)
    override fun updateCharacterStream(p0: Int, p1: java.io.Reader?, p2: Int) = change(p0, p1?.readText())
    override fun updateCharacterStream(p0: Int, p1: java.io.Reader?, p2: Long) = change(p0, p1?.readText())
    override fun updateCharacterStream(p0: Int, p1: java.io.Reader?) = change(p0, p1?.readText())
    override fun updateClob(p0: String?, p1: java.io.Reader?, p2: Long) = updateClob(findColumn(p0), p1, p2)
    override fun updateClob(p0: String?, p1: java.io.Reader?) = updateClob(findColumn(p0), p1)
    override fun updateClob(p0: String?, p1: java.sql.Clob?) = updateClob(findColumn(p0), p1)
    override fun updateClob(p0: Int, p1: java.io.Reader?, p2: Long) = change(p0, p1?.readText())
    override fun updateClob(p0: Int, p1: java.io.Reader?) = change(p0, p1?.readText())
    override fun updateClob(p0: Int, p1: java.sql.Clob?) = change(p0, p1?.let { it.getSubString(1, it.length().toInt()) })
    override fun updateDate(p0: String?, p1: java.sql.Date?) = updateDate(findColumn(p0), p1)
    override fun updateDate(p0: Int, p1: java.sql.Date?) = change(p0, p1)
    override fun updateDouble(p0: String?, p1: Double) = updateDouble(findColumn(p0), p1)
    override fun updateDouble(p0: Int, p1: Double) = change(p0, p1)
    override fun updateFloat(p0: String?, p1: Float) = updateFloat(findColumn(p0), p1)
    override fun updateFloat(p0: Int, p1: Float) = change(p0, p1)
    override fun updateInt(p0: String?, p1: Int) = updateInt(findColumn(p0), p1)
    override fun updateInt(p0: Int, p1: Int) = change(p0, p1)
    override fun updateLong(p0: String?, p1: Long) = updateLong(findColumn(p0), p1)
    override fun updateLong(p0: Int, p1: Long) = change(p0, p1)
    override fun updateNCharacterStream(p0: String?, p1: java.io.Reader?, p2: Long) = updateNCharacterStream(findColumn(p0), p1, p2)
    override fun updateNCharacterStream(p0: String?, p1: java.io.Reader?) = updateNCharacterStream(findColumn(p0), p1)
    override fun updateNCharacterStream(p0: Int, p1: java.io.Reader?, p2: Long) = change(p0, p1?.readText())
    override fun updateNCharacterStream(p0: Int, p1: java.io.Reader?) = change(p0, p1?.readText())
    override fun updateNClob(p0: String?, p1: java.io.Reader?, p2: Long) = updateNClob(findColumn(p0), p1, p2)
    override fun updateNClob(p0: String?, p1: java.io.Reader?) = updateNClob(findColumn(p0), p1)
    override fun updateNClob(p0: String?, p1: java.sql.NClob?) = updateNClob(findColumn(p0), p1)
    override fun updateNClob(p0: Int, p1: java.io.Reader?, p2: Long) = change(p0, p1?.readText())
    override fun updateNClob(p0: Int, p1: java.io.Reader?) = change(p0, p1?.readText())
    override fun updateNClob(p0: Int, p1: java.sql.NClob?) = change(p0, p1?.let { it.getSubString(1, it.length().toInt()) })
    override fun updateNString(p0: String?, p1: String?) = updateNString(findColumn(p0), p1)
    override fun updateNString(p0: Int, p1: String?) = change(p0, p1)
    override fun updateNull(p0: String?) = updateNull(findColumn(p0))
    override fun updateNull(p0: Int) = change(p0, null)
    override fun updateObject(p0: String?, p1: Any?, p2: Int) = updateObject(findColumn(p0), p1, p2)
    override fun updateObject(p0: String?, p1: Any?) = updateObject(findColumn(p0), p1)
    override fun updateObject(p0: Int, p1: Any?, p2: Int) = change(p0, p1)
    override fun updateObject(p0: Int, p1: Any?) = change(p0, p1)
    override fun updateShort(p0: String?, p1: Short) = updateShort(findColumn(p0), p1)
    override fun updateShort(p0: Int, p1: Short) = change(p0, p1)
    override fun updateString(p0: String?, p1: String?) = updateString(findColumn(p0), p1)
    override fun updateString(p0: Int, p1: String?) = change(p0, p1)
    override fun updateTime(p0: String?, p1: java.sql.Time?) = updateTime(findColumn(p0), p1)
    override fun updateTime(p0: Int, p1: java.sql.Time?) = change(p0, p1)
    override fun updateTimestamp(p0: String?, p1: java.sql.Timestamp?) = updateTimestamp(findColumn(p0), p1)
    override fun updateTimestamp(p0: Int, p1: java.sql.Timestamp?) = change(p0, p1)
    override fun <T> unwrap(p0: Class<T>?): T = if (p0 != null && p0.isInstance(this)) p0.cast(this) else throw SQLException("Not a wrapper of $p0")
    override fun isWrapperFor(p0: Class<*>?) = p0 != null && p0.isInstance(this)
}

