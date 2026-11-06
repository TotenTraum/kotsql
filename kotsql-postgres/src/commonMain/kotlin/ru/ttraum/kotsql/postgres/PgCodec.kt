package ru.ttraum.kotsql.postgres

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.SqlCodec
import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlOffsetDateTime
import ru.ttraum.kotsql.SqlOffsetTime
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.TypedNull
import ru.ttraum.kotsql.spi.toHex
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object Oid {
    const val UNSPECIFIED = 0
    const val BOOL = 16; const val BYTEA = 17; const val CHAR = 18; const val NAME = 19; const val INT8 = 20
    const val INT2 = 21; const val INT4 = 23; const val TEXT = 25; const val OID = 26; const val JSON = 114
    const val FLOAT4 = 700; const val FLOAT8 = 701; const val BPCHAR = 1042; const val VARCHAR = 1043
    const val DATE = 1082; const val TIME = 1083; const val TIMESTAMP = 1114; const val TIMESTAMPTZ = 1184
    const val INTERVAL = 1186; const val TIMETZ = 1266; const val NUMERIC = 1700; const val UUID = 2950; const val JSONB = 3802
}

private class PgType(val oid: Int, val name: String, val type: SqlType, val arrayOid: Int)

private val BASE_TYPES = listOf(
    PgType(Oid.BOOL, "bool", SqlType.BOOLEAN, 1000),
    PgType(Oid.BYTEA, "bytea", SqlType.BINARY, 1001),
    PgType(Oid.CHAR, "char", SqlType.CHAR, 1002),
    PgType(Oid.NAME, "name", SqlType.VARCHAR, 1003),
    PgType(Oid.INT8, "int8", SqlType.BIGINT, 1016),
    PgType(Oid.INT2, "int2", SqlType.SMALLINT, 1005),
    PgType(Oid.INT4, "int4", SqlType.INTEGER, 1007),
    PgType(Oid.TEXT, "text", SqlType.TEXT, 1009),
    PgType(Oid.OID, "oid", SqlType.BIGINT, 1028),
    PgType(Oid.JSON, "json", SqlType.JSON, 199),
    PgType(Oid.FLOAT4, "float4", SqlType.REAL, 1021),
    PgType(Oid.FLOAT8, "float8", SqlType.DOUBLE, 1022),
    PgType(Oid.BPCHAR, "bpchar", SqlType.CHAR, 1014),
    PgType(Oid.VARCHAR, "varchar", SqlType.VARCHAR, 1015),
    PgType(Oid.DATE, "date", SqlType.DATE, 1182),
    PgType(Oid.TIME, "time", SqlType.TIME, 1183),
    PgType(Oid.TIMESTAMP, "timestamp", SqlType.TIMESTAMP, 1115),
    PgType(Oid.TIMESTAMPTZ, "timestamptz", SqlType.TIMESTAMP_TZ, 1185),
    PgType(Oid.INTERVAL, "interval", SqlType.INTERVAL, 1187),
    PgType(Oid.TIMETZ, "timetz", SqlType.TIME_TZ, 1270),
    PgType(Oid.NUMERIC, "numeric", SqlType.DECIMAL, 1231),
    PgType(Oid.UUID, "uuid", SqlType.UUID, 2951),
    PgType(Oid.JSONB, "jsonb", SqlType.JSON, 3807),
)
private val BY_OID = BASE_TYPES.associateBy { it.oid }
private val BY_ARRAY_OID = BASE_TYPES.associateBy { it.arrayOid }

/** A type that isn't built in, as `pg_type` describes it. */
internal class CatalogType(val name: String, val elementName: String?)

internal fun isBuiltInType(oid: Int) = oid in BY_OID || oid in BY_ARRAY_OID

/**
 * @param catalog what the catalog says of this type, if it isn't built in
 * @param elementCodec the codec for the elements of an array, as [codec] is for scalar values
 */
internal class PgColumn(name: String, oid: Int, typeModifier: Int, catalog: CatalogType?, codecs: CodecRegistry, val binary: Boolean = false) : ColumnInfo(
    name = name,
    type = if (catalog?.elementName != null) SqlType.ARRAY else sqlTypeOf(oid),
    nativeTypeName = catalog?.name ?: typeNameOf(oid),
    nativeTypeId = oid,
    precision = precisionOf(oid, typeModifier),
    scale = if (oid == Oid.NUMERIC && typeModifier >= 4) (typeModifier - 4) and 0xFFFF else 0,
    codec = codecs.forTypeName(catalog?.name ?: typeNameOf(oid)),
) {
    val elementCodec: SqlCodec<*>? = (catalog?.elementName ?: BY_ARRAY_OID[oid]?.name)?.let(codecs::forTypeName)
    val isCatalogArray = catalog?.elementName != null
}

@Suppress("UNCHECKED_CAST")
private fun decodeElements(list: List<*>, codec: SqlCodec<*>): List<Any?> =
    list.map { item -> if (item is List<*>) decodeElements(item, codec) else item?.let { (codec as SqlCodec<Any>).decode(it) } }

internal fun sqlTypeOf(oid: Int): SqlType = when {
    oid in BY_ARRAY_OID -> SqlType.ARRAY
    else -> BY_OID[oid]?.type ?: SqlType.OTHER
}

private fun typeNameOf(oid: Int): String =
    BY_OID[oid]?.name ?: BY_ARRAY_OID[oid]?.let { "_" + it.name } ?: "unknown($oid)"

private fun precisionOf(oid: Int, mod: Int): Int = when (oid) {
    Oid.NUMERIC -> if (mod >= 4) ((mod - 4) ushr 16) and 0xFFFF else 0
    Oid.VARCHAR, Oid.BPCHAR -> if (mod >= 4) mod - 4 else 0
    else -> 0
}

private fun oidOf(type: SqlType): Int = when (type) {
    SqlType.BOOLEAN -> Oid.BOOL
    SqlType.SMALLINT -> Oid.INT2
    SqlType.INTEGER -> Oid.INT4
    SqlType.BIGINT -> Oid.INT8
    SqlType.REAL -> Oid.FLOAT4
    SqlType.DOUBLE -> Oid.FLOAT8
    SqlType.DECIMAL -> Oid.NUMERIC
    SqlType.BINARY -> Oid.BYTEA
    SqlType.DATE -> Oid.DATE
    SqlType.TIME -> Oid.TIME
    SqlType.TIME_TZ -> Oid.TIMETZ
    SqlType.TIMESTAMP -> Oid.TIMESTAMP
    SqlType.TIMESTAMP_TZ -> Oid.TIMESTAMPTZ
    SqlType.UUID -> Oid.UUID
    SqlType.JSON -> Oid.JSONB
    SqlType.INTERVAL -> Oid.INTERVAL
    // Text-like, or unknown: let the server infer, so that a NULL or string fits any column type.
    SqlType.CHAR, SqlType.VARCHAR, SqlType.TEXT, SqlType.ARRAY, SqlType.OTHER -> Oid.UNSPECIFIED
}

/** A parameter in the text wire format, with the type it is declared as. */
internal class EncodedParam(val oid: Int, val text: ByteArray?)

internal fun encodeParam(value: Any?): EncodedParam = when (value) {
    null -> EncodedParam(Oid.UNSPECIFIED, null)
    is TypedNull -> EncodedParam(oidOf(value.type), null)
    is List<*> -> EncodedParam(arrayOidOf(value), encodeArray(value).encodeToByteArray())
    else -> EncodedParam(scalarOid(value), encodeScalar(value).encodeToByteArray())
}

private fun scalarOid(value: Any): Int = when (value) {
    is Boolean -> Oid.BOOL
    is Byte, is Short -> Oid.INT2
    is Int -> Oid.INT4
    is Long -> Oid.INT8
    is Float -> Oid.FLOAT4
    is Double -> Oid.FLOAT8
    is SqlDecimal -> Oid.NUMERIC
    is String -> Oid.UNSPECIFIED
    is ByteArray -> Oid.BYTEA
    is LocalDate -> Oid.DATE
    is LocalTime -> Oid.TIME
    is LocalDateTime -> Oid.TIMESTAMP
    is Instant -> Oid.TIMESTAMPTZ
    is SqlOffsetDateTime -> Oid.UNSPECIFIED
    is SqlOffsetTime -> Oid.TIMETZ
    is Uuid -> Oid.UUID
    else -> throw SqlException("Unsupported parameter type ${value::class.simpleName}", "22023")
}

private fun encodeScalar(value: Any): String = when (value) {
    is Boolean -> if (value) "t" else "f"
    is ByteArray -> "\\x" + value.toHex()
    is LocalDate -> formatDate(value)
    is LocalDateTime -> formatDate(value.date) + " " + value.time
    is SqlOffsetDateTime -> formatDate(value.dateTime.date) + " " + value.dateTime.time + value.offset
    is SqlOffsetTime -> value.time.toString() + value.offset
    is Byte, is Short, is Int, is Long, is Float, is Double, is SqlDecimal, is String, is LocalTime, is Instant, is Uuid -> value.toString()
    else -> throw SqlException("Unsupported parameter type ${value::class.simpleName}", "22023")
}

private fun formatDate(date: LocalDate): String =
    if (date.year > 0) date.toString() else "${LocalDate(1 - date.year, date.month, date.day)} BC"

private fun arrayOidOf(list: List<*>): Int {
    val sample = firstLeaf(list) ?: return Oid.UNSPECIFIED
    val oid = scalarOid(sample)
    return BY_OID[oid]?.arrayOid ?: Oid.UNSPECIFIED
}

private fun firstLeaf(list: List<*>): Any? {
    for (item in list) {
        val leaf = if (item is List<*>) firstLeaf(item) else item
        if (leaf != null) return leaf
    }
    return null
}

private fun encodeArray(list: List<*>): String = list.joinToString(",", "{", "}") { item ->
    when (item) {
        null -> "NULL"
        is List<*> -> encodeArray(item)
        else -> "\"" + encodeScalar(item).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

internal fun decodeValue(oid: Int, text: String): Any? = text.encodeToByteArray().let { decodeValue(oid, it, 0, it.size) }

/** Decodes the text-format value in [data] from [start] up to [end], without an intermediate string where possible. */
internal fun decodeValue(oid: Int, data: ByteArray, start: Int, end: Int): Any? {
    return try {
        when (oid) {
            Oid.BOOL -> data[start] == 't'.code.toByte()
            Oid.INT2 -> parseLong(data, start, end).toShort()
            Oid.INT4 -> parseLong(data, start, end).toInt()
            Oid.INT8, Oid.OID -> parseLong(data, start, end)
            Oid.FLOAT4 -> data.decodeToString(start, end).toFloat()
            Oid.FLOAT8 -> data.decodeToString(start, end).toDouble()
            Oid.NUMERIC -> SqlDecimal(data.decodeToString(start, end))
            Oid.BYTEA -> decodeBytea(data, start, end)
            Oid.DATE -> parseDate(data, start, end)
            Oid.TIME -> DateTimeScanner(data, start, end).time()
            Oid.TIMETZ -> DateTimeScanner(data, start, end).let { SqlOffsetTime(it.time(), it.offset() ?: UtcOffset.ZERO) }
            Oid.TIMESTAMP -> parseTimestamp(data, start, end).dateTime()
            Oid.TIMESTAMPTZ -> parseTimestamp(data, start, end).let { it.dateTime().toInstant(it.offset() ?: UtcOffset.ZERO) }
            Oid.UUID -> parseUuid(data, start, end)
            // Last, since this lookup on every value would be noticeable.
            else -> BY_ARRAY_OID[oid]?.let { decodeArray(it.oid, data.decodeToString(start, end)) } ?: data.decodeToString(start, end)
        }
    } catch (e: IllegalArgumentException) {
        throw notRepresentable(oid, data, start, end, e)
    } catch (e: IndexOutOfBoundsException) {
        throw notRepresentable(oid, data, start, end, e)
    }
}

private fun notRepresentable(oid: Int, data: ByteArray, start: Int, end: Int, cause: Throwable) =
    SqlException("Cannot represent the value '${data.decodeToString(start, end)}' of type ${typeNameOf(oid)}", "22008", cause = cause)

private fun parseLong(data: ByteArray, start: Int, end: Int): Long {
    val negative = data[start] == '-'.code.toByte()
    var i = if (negative) start + 1 else start
    require(i < end) { "not a number" }
    // Accumulating negatively covers Long.MIN_VALUE.
    var value = 0L
    while (i < end) {
        val digit = data[i++] - '0'.code.toByte()
        require(digit in 0..9) { "not a number" }
        value = value * 10 - digit
    }
    return if (negative) value else -value
}

// Inline, as are the scanner's small methods below: these run per byte, and on native targets every real call of a
// function that handles objects costs more than the work it does here.
@Suppress("NOTHING_TO_INLINE")
private inline fun hexDigit(b: Byte): Int {
    val c = b.toInt()
    return when {
        c >= 48 && c <= 57 -> c - 48 // 0-9
        c >= 97 && c <= 102 -> c - 87 // a-f
        c >= 65 && c <= 70 -> c - 55 // A-F
        else -> throw IllegalArgumentException("not a hexadecimal digit")
    }
}

private fun decodeBytea(data: ByteArray, start: Int, end: Int): ByteArray {
    require(end - start >= 2 && data[start] == '\\'.code.toByte() && data[start + 1] == 'x'.code.toByte()) { "unexpected bytea format" }
    return ByteArray((end - start - 2) / 2) { i ->
        ((hexDigit(data[start + 2 + i * 2]) shl 4) or hexDigit(data[start + 3 + i * 2])).toByte()
    }
}

private fun parseUuid(data: ByteArray, start: Int, end: Int): Uuid {
    require(end - start == 36) { "not a uuid" }
    var high = 0L
    var low = 0L
    var digits = 0
    for (i in start until end) {
        if (i - start == 8 || i - start == 13 || i - start == 18 || i - start == 23) {
            require(data[i] == '-'.code.toByte()) { "not a uuid" }
            continue
        }
        val digit = hexDigit(data[i]).toLong()
        if (digits++ < 16) high = (high shl 4) or digit else low = (low shl 4) or digit
    }
    return Uuid.fromLongs(high, low)
}

private fun endsWithBc(data: ByteArray, start: Int, end: Int): Boolean =
    end - start > 3 && data[end - 3] == ' '.code.toByte() && data[end - 2] == 'B'.code.toByte() && data[end - 1] == 'C'.code.toByte()

/** Parses `YYYY-MM-DD[ BC]`. Fails for `infinity`, which has no equivalent in kotlinx-datetime. */
private fun parseDate(data: ByteArray, start: Int, end: Int): LocalDate {
    val bc = endsWithBc(data, start, end)
    return DateTimeScanner(data, start, if (bc) end - 3 else end).date(bc)
}

/** Positions a scanner for `YYYY-MM-DD HH:MM:SS[.ffffff][+HH[:MM[:SS]]][ BC]`. */
private fun parseTimestamp(data: ByteArray, start: Int, end: Int): DateTimeScanner {
    val bc = endsWithBc(data, start, end)
    return DateTimeScanner(data, start, if (bc) end - 3 else end, bc)
}

/**
 * Reads dates and times in the formats of `DateStyle=ISO`. A hand-written parser, because `LocalTime.parse` and the like
 * go through `java.time.format` on the JVM, which took more time and memory than all of the rest of reading a row.
 */
private class DateTimeScanner(private val data: ByteArray, start: Int, private val end: Int, private val bc: Boolean = false) {
    private var pos = start

    @Suppress("NOTHING_TO_INLINE")
    private inline fun peek(): Int = if (pos < end) data[pos].toInt() else -1

    @Suppress("NOTHING_TO_INLINE")
    private inline fun expect(c: Char) {
        if (peek() != c.code) throw IllegalArgumentException("expected '$c'")
        pos++
    }

    private fun number(minDigits: Int): Int {
        val from = pos
        var value = 0
        while (pos < end) {
            // Compared as an Int: `Byte in 48..57` creates a range object per digit on native targets.
            val digit = data[pos] - 48
            if (digit < 0 || digit > 9) break
            if (pos - from >= 9) throw IllegalArgumentException("number too long")
            value = value * 10 + digit
            pos++
        }
        if (pos - from < minDigits) throw IllegalArgumentException("expected a number")
        return value
    }

    fun date(bc: Boolean = this.bc): LocalDate {
        val year = number(1)
        expect('-')
        val month = number(2)
        expect('-')
        val day = number(2)
        return LocalDate(if (bc) 1 - year else year, month, day)
    }

    fun time(): LocalTime {
        val hour = number(2)
        expect(':')
        val minute = number(2)
        expect(':')
        val second = number(2)
        var nanos = 0
        if (peek() == '.'.code) {
            pos++
            val from = pos
            nanos = number(1)
            repeat(9 - (pos - from)) { nanos *= 10 }
        }
        return LocalTime(hour, minute, second, nanos)
    }

    fun dateTime(): LocalDateTime {
        val date = date()
        expect(' ')
        return LocalDateTime(date, time())
    }

    /** The zone offset, if there is one, like `+03`, `-05:30` or `+05:53:28`. */
    fun offset(): UtcOffset? {
        val signChar = peek()
        if (signChar != '+'.code && signChar != '-'.code) return null
        pos++
        val sign = if (signChar == '-'.code) -1 else 1
        val hours = number(1)
        var minutes = 0
        var seconds = 0
        if (peek() == ':'.code) {
            pos++
            minutes = number(1)
            if (peek() == ':'.code) {
                pos++
                seconds = number(1)
            }
        }
        return UtcOffset(sign * hours, sign * minutes, sign * seconds)
    }
}

// ---- The binary format ----

private const val MICROS_PER_SECOND = 1_000_000L
private const val MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND

/** Days from 1970 to 2000, where the server counts dates from. */
private const val EPOCH_2000_DAYS = 10_957L

/**
 * What's asked for in binary. A value has to convert both ways without loss, as [SqlRow.getText] promises the server's own
 * formatting. So not `timestamptz`, whose text depends on the session's time zone, nor floating point, for which the JVM's
 * `toString` doesn't always give the shortest digits as the server does, nor `numeric`, which would go through text anyway.
 */
internal fun hasBinaryFormat(oid: Int) = when (oid) {
    Oid.BOOL, Oid.INT2, Oid.INT4, Oid.INT8, Oid.OID, Oid.BYTEA, Oid.UUID, Oid.DATE, Oid.TIME, Oid.TIMESTAMP -> true
    else -> false
}

private fun bigEndian(data: ByteArray, start: Int, end: Int): Long {
    var value = data[start].toLong() // the sign comes from here
    for (i in start + 1 until end) value = (value shl 8) or (data[i].toLong() and 0xFF)
    return value
}

private fun requireSize(start: Int, end: Int, size: Int) = require(end - start == size) { "$size bytes expected, not ${end - start}" }

private fun binaryUuid(data: ByteArray, start: Int, end: Int): Uuid {
    requireSize(start, end, 16)
    return Uuid.fromLongs(bigEndian(data, start, start + 8), bigEndian(data, start + 8, end))
}

private fun binaryDate(data: ByteArray, start: Int, end: Int): LocalDate {
    requireSize(start, end, 4)
    val days = bigEndian(data, start, end).toInt()
    // No equivalent of `infinity`, as with text.
    require(days != Int.MAX_VALUE && days != Int.MIN_VALUE) { "infinity" }
    return LocalDate.fromEpochDays(days + EPOCH_2000_DAYS)
}

private fun microsToTime(micros: Long): LocalTime = LocalTime(
    (micros / (3600 * MICROS_PER_SECOND)).toInt(),
    (micros / (60 * MICROS_PER_SECOND) % 60).toInt(),
    (micros / MICROS_PER_SECOND % 60).toInt(),
    (micros % MICROS_PER_SECOND).toInt() * 1000,
)

private fun binaryTime(data: ByteArray, start: Int, end: Int): LocalTime {
    requireSize(start, end, 8)
    return microsToTime(bigEndian(data, start, end))
}

private fun binaryTimestamp(data: ByteArray, start: Int, end: Int): LocalDateTime {
    requireSize(start, end, 8)
    val micros = bigEndian(data, start, end)
    require(micros != Long.MAX_VALUE && micros != Long.MIN_VALUE) { "infinity" }
    return LocalDateTime(
        LocalDate.fromEpochDays(micros.floorDiv(MICROS_PER_DAY) + EPOCH_2000_DAYS),
        microsToTime(micros.mod(MICROS_PER_DAY)),
    )
}

/** Decodes a value of a type that [hasBinaryFormat] accepts, to what [decodeValue] makes of its text. */
private fun decodeBinary(oid: Int, data: ByteArray, start: Int, end: Int): Any? = try {
    when (oid) {
        Oid.BOOL -> { requireSize(start, end, 1); data[start] != 0.toByte() }
        Oid.INT2 -> { requireSize(start, end, 2); bigEndian(data, start, end).toShort() }
        Oid.INT4 -> { requireSize(start, end, 4); bigEndian(data, start, end).toInt() }
        Oid.INT8 -> { requireSize(start, end, 8); bigEndian(data, start, end) }
        Oid.OID -> { requireSize(start, end, 4); bigEndian(data, start, end) and 0xFFFFFFFFL }
        Oid.BYTEA -> data.copyOfRange(start, end)
        Oid.UUID -> binaryUuid(data, start, end)
        Oid.DATE -> binaryDate(data, start, end)
        Oid.TIME -> binaryTime(data, start, end)
        Oid.TIMESTAMP -> binaryTimestamp(data, start, end)
        else -> throw IllegalArgumentException("no binary format")
    }
} catch (e: IllegalArgumentException) {
    throw SqlException("Cannot represent a value of type ${typeNameOf(oid)}: ${e.message}", "22008", cause = e)
} catch (e: IndexOutOfBoundsException) {
    throw SqlException("Cannot represent a value of type ${typeNameOf(oid)}: ${e.message}", "22008", cause = e)
}

/** The server's seconds with what it shows of the fraction: no trailing zeros. */
private fun formatTime(micros: Long): String = buildString(15) {
    fun two(value: Long) { if (value < 10) append('0'); append(value) }
    two(micros / (3600 * MICROS_PER_SECOND)); append(':')
    two(micros / (60 * MICROS_PER_SECOND) % 60); append(':')
    two(micros / MICROS_PER_SECOND % 60)
    val fraction = micros % MICROS_PER_SECOND
    if (fraction != 0L) append('.').append(fraction.toString().padStart(6, '0').trimEnd('0'))
}

private fun formatDateOrInfinity(days: Int): String = when (days) {
    Int.MAX_VALUE -> "infinity"
    Int.MIN_VALUE -> "-infinity"
    else -> formatDate(LocalDate.fromEpochDays(days + EPOCH_2000_DAYS))
}

/** What the server would have sent as text for a value that it has sent in binary, with `DateStyle=ISO` as set at startup. */
private fun formatBinary(oid: Int, data: ByteArray, start: Int, end: Int): String = try {
    when (oid) {
        Oid.BOOL -> if (data[start] != 0.toByte()) "t" else "f"
        Oid.INT2, Oid.INT4, Oid.INT8 -> bigEndian(data, start, end).toString()
        Oid.OID -> (bigEndian(data, start, end) and 0xFFFFFFFFL).toString()
        Oid.BYTEA -> "\\x" + data.copyOfRange(start, end).toHex()
        Oid.UUID -> binaryUuid(data, start, end).toString()
        Oid.DATE -> formatDateOrInfinity(bigEndian(data, start, end).toInt())
        Oid.TIME -> formatTime(bigEndian(data, start, end))
        Oid.TIMESTAMP -> when (val micros = bigEndian(data, start, end)) {
            Long.MAX_VALUE -> "infinity"
            Long.MIN_VALUE -> "-infinity"
            else -> {
                val date = LocalDate.fromEpochDays(micros.floorDiv(MICROS_PER_DAY) + EPOCH_2000_DAYS)
                val time = formatTime(micros.mod(MICROS_PER_DAY))
                // The era comes last, after the time.
                if (date.year > 0) "$date $time" else "${LocalDate(1 - date.year, date.month, date.day)} $time BC"
            }
        }
        else -> throw IllegalArgumentException("no binary format")
    }
} catch (e: IllegalArgumentException) {
    throw SqlException("Cannot represent a value of type ${typeNameOf(oid)}: ${e.message}", "22008", cause = e)
} catch (e: IndexOutOfBoundsException) {
    throw SqlException("Cannot represent a value of type ${typeNameOf(oid)}: ${e.message}", "22008", cause = e)
}

/** Parses the text form of an array, such as `{1,NULL,"a b",{2,3}}`. */
private fun decodeArray(elementOid: Int, text: String): List<Any?> {
    // A non-default lower bound comes as a prefix, like `[0:2]={...}`.
    val start = text.indexOf('{')
    if (start < 0) throw SqlException("Unexpected array format", "22P02")
    var pos = start

    fun parseLevel(): List<Any?> {
        pos++ // '{'
        val items = ArrayList<Any?>()
        while (true) {
            when (text[pos]) {
                '}' -> { pos++; return items }
                ',' -> pos++
                '{' -> items.add(parseLevel())
                '"' -> {
                    pos++
                    val sb = StringBuilder()
                    while (text[pos] != '"') {
                        if (text[pos] == '\\') pos++
                        sb.append(text[pos++])
                    }
                    pos++
                    items.add(decodeValue(elementOid, sb.toString()))
                }
                else -> {
                    val begin = pos
                    while (text[pos] != ',' && text[pos] != '}') pos++
                    val raw = text.substring(begin, pos)
                    items.add(if (raw == "NULL") null else decodeValue(elementOid, raw))
                }
            }
        }
    }
    return try {
        parseLevel()
    } catch (e: IndexOutOfBoundsException) {
        throw SqlException("Unexpected array format", "22P02", cause = e)
    }
}

/**
 * A DataRow message, decoded on demand. [data] is the message's body and [ranges] holds the start and the length of each
 * value in it, with a length of -1 for NULL, so that a row takes two arrays rather than one per value.
 */
internal class PgRow(
    override val columns: List<ColumnInfo>,
    private val data: ByteArray,
    private val ranges: IntArray,
    override val codecs: CodecRegistry,
) : SqlRow {
    private fun check(index: Int) {
        if (index < 0 || index * 2 >= ranges.size) throw IndexOutOfBoundsException("Column index $index, row has ${ranges.size / 2} columns")
    }

    override fun isNull(index: Int): Boolean {
        check(index)
        return ranges[index * 2 + 1] < 0
    }

    override fun get(index: Int): Any? {
        if (isNull(index)) return null
        val start = ranges[index * 2]
        val column = columns[index] as PgColumn
        val end = start + ranges[index * 2 + 1]
        val value = when {
            column.binary -> decodeBinary(column.nativeTypeId, data, start, end)
            column.isCatalogArray -> decodeArray(Oid.TEXT, data.decodeToString(start, end))
            else -> decodeValue(column.nativeTypeId, data, start, end)
        }
        return decodeWithCodec(column, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeWithCodec(column: PgColumn, value: Any?): Any? {
        if (column.codec == null && column.elementCodec == null) return value
        return try {
            when {
                value == null -> null
                column.elementCodec != null && value is List<*> -> decodeElements(value, column.elementCodec)
                column.codec != null -> (column.codec as SqlCodec<Any>).decode(value)
                else -> value
            }
        } catch (e: IllegalArgumentException) {
            throw SqlException("Cannot decode column '${column.name}' of type ${column.nativeTypeName}: ${e.message}", "22023", cause = e)
        }
    }

    override fun getText(index: Int): String? {
        if (isNull(index)) return null
        val start = ranges[index * 2]
        val end = start + ranges[index * 2 + 1]
        val column = columns[index] as PgColumn
        return if (column.binary) formatBinary(column.nativeTypeId, data, start, end) else data.decodeToString(start, end)
    }

    override fun longValue(index: Int): Long {
        if (isNull(index)) return 0
        if ((columns[index] as PgColumn).binary) return super.longValue(index)
        return when (columns[index].nativeTypeId) {
            Oid.INT2, Oid.INT4, Oid.INT8, Oid.OID -> {
                val start = ranges[index * 2]
                try {
                    parseLong(data, start, start + ranges[index * 2 + 1])
                } catch (e: IllegalArgumentException) {
                    throw notRepresentable(columns[index].nativeTypeId, data, start, start + ranges[index * 2 + 1], e)
                }
            }
            else -> super.longValue(index)
        }
    }

    override fun booleanValue(index: Int): Boolean {
        if (isNull(index)) return false
        if (columns[index].nativeTypeId != Oid.BOOL || (columns[index] as PgColumn).binary) return super.booleanValue(index)
        return data[ranges[index * 2]] == 't'.code.toByte()
    }
}

/** Reads the body of a DataRow message: a count of values, each with its length in front. */
internal fun parseDataRow(columns: List<ColumnInfo>, body: ByteArray, codecs: CodecRegistry): PgRow {
    fun malformed(): Nothing = throw SqlException("Malformed message from server", "08P01")
    if (body.size < 2) malformed()
    val count = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
    if (count != columns.size) malformed()
    val ranges = IntArray(count * 2)
    var pos = 2
    for (i in 0 until count) {
        if (pos + 4 > body.size) malformed()
        val length = ((body[pos].toInt() and 0xFF) shl 24) or ((body[pos + 1].toInt() and 0xFF) shl 16) or
            ((body[pos + 2].toInt() and 0xFF) shl 8) or (body[pos + 3].toInt() and 0xFF)
        pos += 4
        ranges[i * 2] = pos
        ranges[i * 2 + 1] = length
        if (length > 0) {
            if (length > body.size - pos) malformed()
            pos += length
        }
    }
    return PgRow(columns, body, ranges, codecs)
}
