package dev.ksql.mysql

import dev.ksql.CodecRegistry
import dev.ksql.ColumnInfo
import dev.ksql.SqlCodec
import dev.ksql.SqlDecimal
import dev.ksql.SqlException
import dev.ksql.SqlOffsetDateTime
import dev.ksql.SqlOffsetTime
import dev.ksql.SqlRow
import dev.ksql.SqlType
import dev.ksql.TypedNull
import dev.ksql.spi.ByteWriter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Column types in the protocol. */
internal object T {
    const val DECIMAL = 0x00; const val TINY = 0x01; const val SHORT = 0x02; const val LONG = 0x03; const val FLOAT = 0x04
    const val DOUBLE = 0x05; const val NULL = 0x06; const val TIMESTAMP = 0x07; const val LONGLONG = 0x08; const val INT24 = 0x09
    const val DATE = 0x0a; const val TIME = 0x0b; const val DATETIME = 0x0c; const val YEAR = 0x0d; const val VARCHAR = 0x0f
    const val BIT = 0x10; const val JSON = 0xf5; const val NEWDECIMAL = 0xf6; const val ENUM = 0xf7; const val SET = 0xf8
    const val TINY_BLOB = 0xf9; const val MEDIUM_BLOB = 0xfa; const val LONG_BLOB = 0xfb; const val BLOB = 0xfc
    const val VAR_STRING = 0xfd; const val STRING = 0xfe; const val GEOMETRY = 0xff
}

private const val FLAG_NOT_NULL = 0x1
private const val FLAG_UNSIGNED = 0x20
private const val FLAG_ENUM = 0x100
private const val FLAG_SET = 0x800
private const val CHARSET_BINARY = 63

private fun nameOf(type: Int, flags: Int): String = when {
    // These two come as strings with a flag.
    flags and FLAG_ENUM != 0 -> "ENUM"
    flags and FLAG_SET != 0 -> "SET"
    else -> nameOfProtocolType(type)
}

private fun nameOfProtocolType(type: Int): String = when (type) {
    T.DECIMAL, T.NEWDECIMAL -> "DECIMAL"
    T.TINY -> "TINYINT"; T.SHORT -> "SMALLINT"; T.LONG -> "INT"; T.INT24 -> "MEDIUMINT"; T.LONGLONG -> "BIGINT"
    T.FLOAT -> "FLOAT"; T.DOUBLE -> "DOUBLE"; T.NULL -> "NULL"; T.TIMESTAMP -> "TIMESTAMP"; T.DATE -> "DATE"; T.TIME -> "TIME"
    T.DATETIME -> "DATETIME"; T.YEAR -> "YEAR"; T.VARCHAR, T.VAR_STRING -> "VARCHAR"; T.BIT -> "BIT"; T.JSON -> "JSON"
    T.ENUM -> "ENUM"; T.SET -> "SET"; T.TINY_BLOB, T.MEDIUM_BLOB, T.LONG_BLOB, T.BLOB -> "BLOB"; T.STRING -> "CHAR"
    T.GEOMETRY -> "GEOMETRY"
    else -> "UNKNOWN($type)"
}

internal class MysqlColumn(name: String, val protocolType: Int, val flags: Int, val charset: Int, val length: Int, val decimals: Int, codecs: CodecRegistry) : ColumnInfo(
    name = name,
    type = sqlTypeOf(protocolType, flags, charset, length),
    nativeTypeName = nameOf(protocolType, flags) + if (flags and FLAG_UNSIGNED != 0) " UNSIGNED" else "",
    nativeTypeId = protocolType,
    precision = if (protocolType == T.DECIMAL || protocolType == T.NEWDECIMAL) (length - (if (decimals > 0) 1 else 0) - (if (flags and FLAG_UNSIGNED == 0) 1 else 0)).coerceAtLeast(0) else 0,
    scale = if (protocolType == T.DECIMAL || protocolType == T.NEWDECIMAL) decimals else 0,
    nullable = flags and FLAG_NOT_NULL == 0,
    unsigned = flags and FLAG_UNSIGNED != 0,
    codec = codecs.forTypeName(nameOf(protocolType, flags)),
) {
    val isUnsigned get() = flags and FLAG_UNSIGNED != 0
    val isBinary get() = charset == CHARSET_BINARY
}

private fun sqlTypeOf(type: Int, flags: Int, charset: Int, length: Int): SqlType {
    val unsigned = flags and FLAG_UNSIGNED != 0
    return when (type) {
        // TINYINT(1) is how MySQL spells BOOLEAN.
        T.TINY -> if (length == 1) SqlType.BOOLEAN else SqlType.SMALLINT
        T.SHORT, T.YEAR -> if (unsigned) SqlType.INTEGER else SqlType.SMALLINT
        T.INT24 -> SqlType.INTEGER
        T.LONG -> if (unsigned) SqlType.BIGINT else SqlType.INTEGER
        T.LONGLONG -> if (unsigned) SqlType.DECIMAL else SqlType.BIGINT
        T.FLOAT -> SqlType.REAL
        T.DOUBLE -> SqlType.DOUBLE
        T.DECIMAL, T.NEWDECIMAL -> SqlType.DECIMAL
        T.DATE -> SqlType.DATE
        T.TIME -> SqlType.TIME
        // The protocol carries no zone for either, and a TIMESTAMP comes converted to the session's.
        T.DATETIME, T.TIMESTAMP -> SqlType.TIMESTAMP
        T.JSON -> SqlType.JSON
        // BIT(1) is what Hibernate, among others, declares for a boolean.
        T.BIT -> if (length == 1) SqlType.BOOLEAN else SqlType.BINARY
        T.GEOMETRY -> SqlType.BINARY
        T.TINY_BLOB, T.MEDIUM_BLOB, T.LONG_BLOB, T.BLOB -> if (charset == CHARSET_BINARY) SqlType.BINARY else SqlType.TEXT
        T.VARCHAR, T.VAR_STRING -> if (charset == CHARSET_BINARY) SqlType.BINARY else SqlType.VARCHAR
        T.STRING, T.ENUM, T.SET -> if (charset == CHARSET_BINARY) SqlType.BINARY else SqlType.CHAR
        else -> SqlType.OTHER
    }
}

/** Reads a length-encoded integer at [position], and returns it with the position after it. */
internal inline fun <R> readLenEnc(data: ByteArray, position: Int, result: (value: Long, next: Int) -> R): R =
    when (val first = data[position].toInt() and 0xFF) {
        0xFC -> result(littleEndian(data, position + 1, 2), position + 3)
        0xFD -> result(littleEndian(data, position + 1, 3), position + 4)
        0xFE -> result(littleEndian(data, position + 1, 8), position + 9)
        else -> result(first.toLong(), position + 1)
    }

internal fun littleEndian(data: ByteArray, position: Int, bytes: Int): Long {
    var value = 0L
    for (i in 0 until bytes) value = value or ((data[position + i].toLong() and 0xFF) shl (8 * i))
    return value
}

private fun malformed(): Nothing = throw SqlException("Malformed message from server", "08P01")

private fun fixedSize(type: Int): Int = when (type) {
    T.TINY -> 1
    T.SHORT, T.YEAR -> 2
    T.INT24, T.LONG, T.FLOAT -> 4
    T.LONGLONG, T.DOUBLE -> 8
    else -> -1
}

/** Binary protocol: a 0x00 header, a bitmap of NULLs with two bits to spare, then the values that aren't NULL. */
internal fun parseBinaryRow(columns: List<ColumnInfo>, body: ByteArray, codecs: CodecRegistry): SqlRow {
    val bitmap = (columns.size + 7 + 2) / 8
    var pos = 1 + bitmap
    val ranges = IntArray(columns.size * 2)
    try {
        for (i in columns.indices) {
            val bit = i + 2
            if (body[1 + bit / 8].toInt() and (1 shl (bit % 8)) != 0) {
                ranges[i * 2 + 1] = -1
                continue
            }
            val type = (columns[i] as MysqlColumn).protocolType
            val fixed = fixedSize(type)
            when {
                fixed > 0 -> {
                    ranges[i * 2] = pos
                    ranges[i * 2 + 1] = fixed
                    pos += fixed
                }
                type == T.DATE || type == T.TIME || type == T.DATETIME || type == T.TIMESTAMP -> {
                    val size = body[pos].toInt() and 0xFF
                    ranges[i * 2] = pos + 1
                    ranges[i * 2 + 1] = size
                    pos += 1 + size
                }
                else -> {
                    readLenEnc(body, pos) { length, next ->
                        if (length > body.size - next) malformed()
                        ranges[i * 2] = next
                        ranges[i * 2 + 1] = length.toInt()
                        pos = next + length.toInt()
                    }
                }
            }
        }
    } catch (e: IndexOutOfBoundsException) {
        malformed()
    }
    if (pos > body.size) malformed()
    return BinaryRow(columns, body, ranges, codecs)
}

/** Text protocol: each value as a string with its length in front, or 0xFB for NULL. */
internal fun parseTextRow(columns: List<ColumnInfo>, body: ByteArray, codecs: CodecRegistry): SqlRow {
    var pos = 0
    val ranges = IntArray(columns.size * 2)
    try {
        for (i in columns.indices) {
            if (body[pos] == 0xFB.toByte()) {
                ranges[i * 2 + 1] = -1
                pos++
                continue
            }
            readLenEnc(body, pos) { length, next ->
                if (length > body.size - next) malformed()
                ranges[i * 2] = next
                ranges[i * 2 + 1] = length.toInt()
                pos = next + length.toInt()
            }
        }
    } catch (e: IndexOutOfBoundsException) {
        malformed()
    }
    return TextRow(columns, body, ranges, codecs)
}

private abstract class MysqlRow(
    override val columns: List<ColumnInfo>,
    protected val data: ByteArray,
    protected val ranges: IntArray,
    override val codecs: CodecRegistry,
) : SqlRow {
    protected fun check(index: Int) {
        if (index < 0 || index * 2 >= ranges.size) throw IndexOutOfBoundsException("Column index $index, row has ${ranges.size / 2} columns")
    }

    override fun isNull(index: Int): Boolean {
        check(index)
        return ranges[index * 2 + 1] < 0
    }

    override fun get(index: Int): Any? {
        if (isNull(index)) return null
        val column = columns[index] as MysqlColumn
        val start = ranges[index * 2]
        val length = ranges[index * 2 + 1]
        return try {
            val value = decode(column, start, length)
            @Suppress("UNCHECKED_CAST")
            if (column.codec != null && value != null) (column.codec as SqlCodec<Any>).decode(value) else value
        } catch (e: IllegalArgumentException) {
            throw SqlException("Cannot represent the value in column '${column.name}' of type ${column.nativeTypeName}: ${e.message}", "22008", cause = e)
        }
    }

    protected abstract fun decode(column: MysqlColumn, start: Int, length: Int): Any?
}

private fun formatDate(y: Int, m: Int, d: Int) = "${y.toString().padStart(4, '0')}-${two(m)}-${two(d)}"
private fun two(v: Int) = v.toString().padStart(2, '0')

private class BinaryRow(columns: List<ColumnInfo>, data: ByteArray, ranges: IntArray, codecs: CodecRegistry) : MysqlRow(columns, data, ranges, codecs) {
    private fun u8(i: Int) = data[i].toInt() and 0xFF
    private fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun i32(i: Int) = u16(i) or (u16(i + 2) shl 16)
    private fun i64(i: Int) = (i32(i).toLong() and 0xFFFFFFFFL) or (i32(i + 4).toLong() shl 32)

    override fun longValue(index: Int): Long {
        if (isNull(index)) return 0
        return when (val v = get(index)) {
            is Number -> v.toLong()
            is Boolean -> if (v) 1 else 0
            is SqlDecimal -> v.text.toLong()
            else -> throw ClassCastException("Not an integer: $v")
        }
    }

    override fun decode(column: MysqlColumn, start: Int, length: Int): Any? = when (column.protocolType) {
        T.BIT -> if (column.type == SqlType.BOOLEAN) data[start] != 0.toByte() else data.copyOfRange(start, start + length)
        T.TINY -> if (column.type == SqlType.BOOLEAN) data[start] != 0.toByte() else if (column.isUnsigned) u8(start).toShort() else data[start].toShort()
        T.SHORT, T.YEAR -> if (column.isUnsigned) u16(start) else u16(start).toShort()
        T.INT24, T.LONG -> if (column.isUnsigned) i32(start).toLong() and 0xFFFFFFFFL else i32(start)
        T.LONGLONG -> if (column.isUnsigned) SqlDecimal(i64(start).toULong().toString()) else i64(start)
        T.FLOAT -> Float.fromBits(i32(start))
        T.DOUBLE -> Double.fromBits(i64(start))
        T.DECIMAL, T.NEWDECIMAL -> SqlDecimal(data.decodeToString(start, start + length))
        T.DATE -> date(start, length)
        T.DATETIME, T.TIMESTAMP -> LocalDateTime(date(start, length), if (length > 4) timeOfDay(start + 4, length - 4) else LocalTime(0, 0))
        T.TIME -> time(start, length)
        else -> if (column.type == SqlType.BINARY) data.copyOfRange(start, start + length) else data.decodeToString(start, start + length)
    }

    private fun date(start: Int, length: Int): LocalDate {
        // MySQL's "zero date", whose length is 0, is no date at all.
        require(length >= 4) { "the zero date" }
        return LocalDate(u16(start), u8(start + 2), u8(start + 3))
    }

    private fun timeOfDay(start: Int, length: Int): LocalTime =
        LocalTime(u8(start), u8(start + 1), u8(start + 2), if (length >= 7) i32(start + 3) * 1000 else 0)

    private fun time(start: Int, length: Int): LocalTime {
        if (length == 0) return LocalTime(0, 0)
        // A TIME is really a duration, from -838 to 838 hours.
        require(data[start] == 0.toByte() && i32(start + 1) == 0) { "a time outside of the 24 hours of a day" }
        return timeOfDay(start + 5, length - 5)
    }

    override fun getText(index: Int): String? {
        if (isNull(index)) return null
        val column = columns[index] as MysqlColumn
        val start = ranges[index * 2]
        val length = ranges[index * 2 + 1]
        return when (column.protocolType) {
            T.DATE -> if (length == 0) "0000-00-00" else formatDate(u16(start), u8(start + 2), u8(start + 3))
            T.DATETIME, T.TIMESTAMP -> if (length == 0) "0000-00-00 00:00:00" else buildString {
                append(formatDate(u16(start), u8(start + 2), u8(start + 3))).append(' ')
                if (length > 4) appendTime(u8(start + 4), u8(start + 5), u8(start + 6), if (length >= 11) i32(start + 7) else 0) else append("00:00:00")
            }
            T.TIME -> if (length == 0) "00:00:00" else buildString {
                if (data[start] != 0.toByte()) append('-')
                val hours = i32(start + 1) * 24 + u8(start + 5)
                appendTime(hours, u8(start + 6), u8(start + 7), if (length >= 12) i32(start + 8) else 0)
            }
            T.BIT -> if (column.type == SqlType.BOOLEAN) (if (data[start] != 0.toByte()) "1" else "0") else data.decodeToString(start, start + length)
            T.TINY, T.SHORT, T.YEAR, T.INT24, T.LONG, T.LONGLONG -> when (val v = get(index)) {
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
            T.FLOAT, T.DOUBLE -> get(index).toString()
            else -> data.decodeToString(start, start + length)
        }
    }

    private fun StringBuilder.appendTime(h: Int, m: Int, s: Int, micros: Int) {
        append(two(h)).append(':').append(two(m)).append(':').append(two(s))
        if (micros != 0) append('.').append(micros.toString().padStart(6, '0'))
    }
}

private class TextRow(columns: List<ColumnInfo>, data: ByteArray, ranges: IntArray, codecs: CodecRegistry) : MysqlRow(columns, data, ranges, codecs) {
    override fun getText(index: Int): String? {
        if (isNull(index)) return null
        val start = ranges[index * 2]
        val column = columns[index] as MysqlColumn
        if (column.protocolType == T.BIT && column.type == SqlType.BOOLEAN) return if (data[start] != 0.toByte()) "1" else "0"
        return data.decodeToString(start, start + ranges[index * 2 + 1])
    }

    override fun decode(column: MysqlColumn, start: Int, length: Int): Any? {
        val text = data.decodeToString(start, start + length)
        return when (column.protocolType) {
            T.BIT -> if (column.type == SqlType.BOOLEAN) data[start] != 0.toByte() else data.copyOfRange(start, start + length)
            T.TINY -> if (column.type == SqlType.BOOLEAN) text != "0" else text.toShort()
            T.SHORT, T.YEAR -> if (column.isUnsigned) text.toInt() else text.toShort()
            T.INT24, T.LONG -> if (column.isUnsigned) text.toLong() else text.toInt()
            T.LONGLONG -> if (column.isUnsigned) SqlDecimal(text) else text.toLong()
            T.FLOAT -> text.toFloat()
            T.DOUBLE -> text.toDouble()
            T.DECIMAL, T.NEWDECIMAL -> SqlDecimal(text)
            T.DATE -> parseDate(text)
            T.DATETIME, T.TIMESTAMP -> LocalDateTime(parseDate(text.substring(0, 10)), parseTime(text.substring(11)))
            T.TIME -> parseTime(text)
            else -> if (column.type == SqlType.BINARY) data.copyOfRange(start, start + length) else text
        }
    }

    private fun parseDate(text: String): LocalDate {
        require(!text.startsWith("0000-00-00")) { "the zero date" }
        return LocalDate(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt())
    }

    private fun parseTime(text: String): LocalTime {
        val parts = text.substringBefore('.').split(':')
        require(parts.size == 3 && !text.startsWith("-") && parts[0].toInt() < 24) { "a time outside of the 24 hours of a day" }
        val fraction = text.substringAfter('.', "").padEnd(9, '0')
        return LocalTime(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), fraction.toInt())
    }
}

/** Writes parameters in the binary protocol: their types, then their values. */
internal fun ByteWriter.writeParameters(params: List<Any?>) {
    if (params.isEmpty()) return
    val bitmap = ByteArray((params.size + 7) / 8)
    params.forEachIndexed { i, p ->
        if (p == null || p is TypedNull) bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
    }
    bytes(bitmap)
    int8(1) // types follow
    for (p in params) {
        int8(parameterType(p))
        int8(0) // signed
    }
    for (p in params) writeValue(p)
}

private fun parameterType(value: Any?): Int = when (value) {
    null, is TypedNull -> T.NULL
    is Boolean, is Byte -> T.TINY
    is Short -> T.SHORT
    is Int -> T.LONG
    is Long -> T.LONGLONG
    is Float -> T.FLOAT
    is Double -> T.DOUBLE
    is SqlDecimal -> T.NEWDECIMAL
    is ByteArray -> T.BLOB
    is LocalDate -> T.DATE
    is LocalTime -> T.TIME
    is LocalDateTime, is SqlOffsetDateTime -> T.DATETIME
    is String, is Instant, is Uuid -> T.VAR_STRING
    is List<*> -> throw SqlException("MySQL has no array type", "0A000")
    is SqlOffsetTime -> throw SqlException("MySQL has no TIME WITH TIME ZONE type", "0A000")
    else -> throw SqlException("Unsupported parameter type ${value::class.simpleName}", "22023")
}

private fun ByteWriter.lenEnc(value: Int) {
    when {
        value < 0xFB -> int8(value)
        value < 0x10000 -> { int8(0xFC); int16LE(value) }
        value < 0x1000000 -> { int8(0xFD); int24LE(value) }
        else -> { int8(0xFE); int64LE(value.toLong()) }
    }
}

private fun ByteWriter.lenEncBytes(value: ByteArray) {
    lenEnc(value.size)
    bytes(value)
}

private fun ByteWriter.writeDateTime(value: LocalDateTime) {
    val micros = value.time.nanosecond / 1000
    int8(if (micros != 0) 11 else 7)
    int16LE(value.date.year); int8(value.date.month.ordinal + 1); int8(value.date.day)
    int8(value.time.hour); int8(value.time.minute); int8(value.time.second)
    if (micros != 0) int32LE(micros)
}

private fun ByteWriter.writeValue(value: Any?) {
    when (value) {
        null, is TypedNull -> Unit
        is Boolean -> int8(if (value) 1 else 0)
        is Byte -> int8(value.toInt())
        is Short -> int16LE(value.toInt())
        is Int -> int32LE(value)
        is Long -> int64LE(value)
        is Float -> int32LE(value.toRawBits())
        is Double -> int64LE(value.toRawBits())
        is SqlDecimal -> lenEncBytes(value.text.encodeToByteArray())
        is ByteArray -> lenEncBytes(value)
        is String -> lenEncBytes(value.encodeToByteArray())
        is Uuid -> lenEncBytes(value.toString().encodeToByteArray())
        // The server converts a value with an offset to the session's time zone, which is what a TIMESTAMP column wants.
        is Instant -> lenEncBytes(formatInstant(value).encodeToByteArray())
        is LocalDate -> { int8(4); int16LE(value.year); int8(value.month.ordinal + 1); int8(value.day) }
        is LocalTime -> {
            val micros = value.nanosecond / 1000
            int8(if (micros != 0) 12 else 8)
            int8(0); int32LE(0)
            int8(value.hour); int8(value.minute); int8(value.second)
            if (micros != 0) int32LE(micros)
        }
        is LocalDateTime -> writeDateTime(value)
        is SqlOffsetDateTime -> writeDateTime(value.dateTime)
        else -> throw SqlException("Unsupported parameter type ${value::class.simpleName}", "22023")
    }
}

private fun formatInstant(value: Instant): String =
    value.toString().removeSuffix("Z").replace('T', ' ') + "+00:00"
