package ru.ttraum.kotsql

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import kotlin.reflect.KClass

private val EMPTY_CODECS = CodecRegistry()

/** Logical type of a column, shared by every driver. The exact server type is in [ColumnInfo.nativeTypeName]. */
public enum class SqlType {
    BOOLEAN, SMALLINT, INTEGER, BIGINT, REAL, DOUBLE, DECIMAL,
    CHAR, VARCHAR, TEXT, BINARY,
    DATE, TIME, TIME_TZ, TIMESTAMP, TIMESTAMP_TZ,
    UUID, JSON, INTERVAL, ARRAY, OTHER,
}

/**
 * Column description.
 *
 * @param precision declared precision or length, 0 if unknown
 * @param scale declared scale, 0 if unknown
 * @param nullable null if the server does not report it
 */
public open class ColumnInfo(
    public val name: String,
    public val type: SqlType,
    public val nativeTypeName: String,
    public val nativeTypeId: Int = 0,
    public val precision: Int = 0,
    public val scale: Int = 0,
    public val nullable: Boolean? = null,
    public val unsigned: Boolean = false,
    public val codec: SqlCodec<*>? = null,
)

/** Arbitrary precision decimal in its plain textual form, e.g. `-12.340` or `NaN`. */
public data class SqlDecimal(val text: String) {
    override fun toString(): String = text
}

/** `TIME WITH TIME ZONE` value. */
public data class SqlOffsetTime(val time: LocalTime, val offset: UtcOffset)

/**
 * A timestamp parameter that fits both `TIMESTAMP` (keeps the wall clock) and `TIMESTAMP WITH TIME ZONE` (keeps the
 * instant) columns, needed by `java.sql.Timestamp`, whose target column type is unknown.
 */
public data class SqlOffsetDateTime(val dateTime: LocalDateTime, val offset: UtcOffset)

/** A NULL parameter of a known type. Plain `null` means a NULL whose type the server must infer. */
public data class TypedNull(val type: SqlType)

/**
 * One row. Values are decoded to these canonical types:
 * [Boolean], [Short], [Int], [Long], [Float], [Double], [SqlDecimal], [String], [ByteArray],
 * [kotlinx.datetime.LocalDate], [LocalTime], [LocalDateTime], [kotlin.time.Instant], [SqlOffsetTime],
 * [kotlin.uuid.Uuid], and [List] for arrays. Any type without a mapping is returned as [String], or as an object if a
 * [SqlCodec] handles its name.
 *
 * The same types (plus [Byte], [SqlOffsetDateTime] and [TypedNull]) are accepted as parameters, along with enums, by name, and
 * any class that a [SqlCodec] is registered for.
 */
public interface SqlRow {
    public val columns: List<ColumnInfo>

    /** Decoded value of the 0-based column [index]. */
    public operator fun get(index: Int): Any?

    /** The value as text, in the server's own formatting. */
    public fun getText(index: Int): String?

    /** The codecs of the connection that the row came from. */
    public val codecs: CodecRegistry get() = EMPTY_CODECS

    /** The value as [type], through a codec registered for it if it isn't that already. Null for NULL. */
    public fun <T : Any> get(index: Int, type: KClass<T>): T? = convertToClass(this, index, type)

    public fun <T : Any> get(name: String, type: KClass<T>): T? {
        val i = indexOf(name)
        if (i < 0) throw NoSuchElementException("No column named '$name'")
        return get(i, type)
    }

    public fun isNull(index: Int): Boolean = get(index) == null

    /** An integer column's value without boxing, or 0 if it is NULL. Check [isNull] to tell them apart. */
    public fun longValue(index: Int): Long = (get(index) as Number?)?.toLong() ?: 0

    /** A boolean column's value without boxing, or false if it is NULL. Check [isNull] to tell them apart. */
    public fun booleanValue(index: Int): Boolean = get(index) as Boolean? ?: false

    /** 0-based index of a column by case-insensitive name, or -1. */
    public fun indexOf(name: String): Int = columns.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    public operator fun get(name: String): Any? {
        val i = indexOf(name)
        if (i < 0) throw NoSuchElementException("No column named '$name'")
        return get(i)
    }
}
