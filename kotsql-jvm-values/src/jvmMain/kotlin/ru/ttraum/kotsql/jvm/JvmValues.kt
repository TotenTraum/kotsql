package ru.ttraum.kotsql.jvm

import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlOffsetDateTime
import ru.ttraum.kotsql.SqlOffsetTime
import ru.ttraum.kotsql.TypedNull
import kotlin.time.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toJavaZoneOffset
import kotlin.time.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.datetime.toKotlinUtcOffset
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Conversion between Java types, as used by JDBC and R2DBC, and the canonical types of [SqlRow]. */
object JvmValues {
    /** Converts a Java parameter value to its canonical type. */
    fun toCanonical(value: Any?): Any? = when (value) {
        null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is String, is ByteArray,
        is SqlDecimal, is SqlOffsetTime, is SqlOffsetDateTime, is TypedNull -> value
        is BigDecimal -> SqlDecimal(value.toPlainString())
        is BigInteger -> SqlDecimal(value.toString())
        is Number -> SqlDecimal(value.toString())
        is CharSequence -> value.toString()
        is Char -> value.toString()
        is java.sql.Date -> value.toLocalDate().toKotlinLocalDate()
        is java.sql.Time -> value.toLocalTime().toKotlinLocalTime()
        is java.sql.Timestamp -> {
            val wall = value.toLocalDateTime()
            SqlOffsetDateTime(wall.toKotlinLocalDateTime(), ZoneId.systemDefault().rules.getOffset(wall).toKotlinUtcOffset())
        }
        is java.util.Date -> value.toInstant().toKotlinInstant()
        is LocalDate -> value.toKotlinLocalDate()
        is LocalTime -> value.toKotlinLocalTime()
        is LocalDateTime -> value.toKotlinLocalDateTime()
        is Instant -> value.toKotlinInstant()
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        is ZonedDateTime -> value.toInstant().toKotlinInstant()
        is OffsetTime -> SqlOffsetTime(value.toLocalTime().toKotlinLocalTime(), value.offset.toKotlinUtcOffset())
        is UUID -> value.toKotlinUuid()
        is ByteBuffer -> ByteArray(value.remaining()).also { value.duplicate().get(it) }
        is Collection<*> -> value.map(::toCanonical)
        is Array<*> -> value.map(::toCanonical)
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is ShortArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is BooleanArray -> value.toList()
        else -> if (value::class.java.name.startsWith("kotlin")) value else
            throw SqlException("Unsupported parameter type ${value.javaClass.name}", "22023")
    }

    /** Converts a canonical value to the natural Java type (`java.time`, `BigDecimal`, `UUID`, ...). */
    fun toJava(value: Any?): Any? = when (value) {
        is SqlDecimal -> decimal(value)
        is kotlinx.datetime.LocalDate -> value.toJavaLocalDate()
        is kotlinx.datetime.LocalTime -> value.toJavaLocalTime()
        is kotlinx.datetime.LocalDateTime -> value.toJavaLocalDateTime()
        is kotlin.time.Instant -> value.toJavaInstant().atOffset(ZoneOffset.UTC)
        is SqlOffsetTime -> OffsetTime.of(value.time.toJavaLocalTime(), value.offset.toJavaZoneOffset())
        is kotlin.uuid.Uuid -> value.toJavaUuid()
        is List<*> -> value.map(::toJava).toTypedArray<Any?>()
        else -> value
    }

    /** Converts a canonical value to [target], applying the conversions JDBC and R2DBC expect. */
    @Suppress("UNCHECKED_CAST")
    fun <T> convert(value: Any?, target: Class<T>): T? {
        if (value == null) return null
        val boxed: Class<*> = if (target.isPrimitive) (target as Class<Any>).kotlin.javaObjectType else target
        val jv = toJava(value)
        if (boxed != Any::class.java && boxed.isInstance(jv)) return jv as T
        if (jv is String && boxed in TEMPORAL_CLASSES) return convert(parseTemporal(jv), target)
        val converted: Any? = when (boxed) {
            Any::class.java -> jv
            java.lang.String::class.java -> asString(jv)
            java.lang.Boolean::class.java -> asBoolean(jv)
            java.lang.Byte::class.java -> number(jv).toByte()
            java.lang.Short::class.java -> number(jv).toShort()
            java.lang.Integer::class.java -> number(jv).toInt()
            java.lang.Long::class.java -> number(jv).toLong()
            java.lang.Float::class.java -> number(jv).toFloat()
            java.lang.Double::class.java -> number(jv).toDouble()
            BigDecimal::class.java -> number(jv)
            BigInteger::class.java -> number(jv).toBigInteger()
            ByteArray::class.java -> if (jv is String) jv.encodeToByteArray() else null
            ByteBuffer::class.java -> if (jv is ByteArray) ByteBuffer.wrap(jv) else null
            UUID::class.java -> if (jv is String) UUID.fromString(jv) else null
            LocalDate::class.java -> when (jv) {
                is LocalDateTime -> jv.toLocalDate()
                is OffsetDateTime -> jv.toLocalDate()
                else -> null
            }
            LocalTime::class.java -> when (jv) {
                is LocalDateTime -> jv.toLocalTime()
                is OffsetDateTime -> jv.toLocalTime()
                is OffsetTime -> jv.toLocalTime()
                else -> null
            }
            LocalDateTime::class.java -> when (jv) {
                is OffsetDateTime -> jv.toLocalDateTime()
                is LocalDate -> jv.atStartOfDay()
                else -> null
            }
            OffsetDateTime::class.java -> if (jv is LocalDateTime) jv.atZone(ZoneId.systemDefault()).toOffsetDateTime() else null
            ZonedDateTime::class.java -> when (jv) {
                is OffsetDateTime -> jv.toZonedDateTime()
                is LocalDateTime -> jv.atZone(ZoneId.systemDefault())
                else -> null
            }
            Instant::class.java -> when (jv) {
                is OffsetDateTime -> jv.toInstant()
                is LocalDateTime -> jv.atZone(ZoneId.systemDefault()).toInstant()
                else -> null
            }
            java.sql.Date::class.java -> convert(value, LocalDate::class.java)?.let(java.sql.Date::valueOf)
            java.sql.Time::class.java -> convert(value, LocalTime::class.java)?.let(java.sql.Time::valueOf)
            java.sql.Timestamp::class.java -> when (jv) {
                is LocalDateTime -> java.sql.Timestamp.valueOf(jv)
                is LocalDate -> java.sql.Timestamp.valueOf(jv.atStartOfDay())
                is OffsetDateTime -> java.sql.Timestamp.from(jv.toInstant())
                else -> null
            }
            java.util.Date::class.java -> convert(value, java.sql.Timestamp::class.java)
            else -> null
        }
        return (converted ?: throw SqlException("Cannot convert ${jv?.javaClass?.simpleName} to ${target.simpleName}", "22023")) as T
    }

    private val TEMPORAL_CLASSES = setOf<Class<*>>(
        LocalDate::class.java, LocalTime::class.java, LocalDateTime::class.java, OffsetDateTime::class.java, ZonedDateTime::class.java, Instant::class.java,
        java.sql.Date::class.java, java.sql.Time::class.java, java.sql.Timestamp::class.java, java.util.Date::class.java,
    )

    /** Dates and times held as text, as they are where the database has no type for them. */
    private fun parseTemporal(text: String): Any {
        val t = text.trim().replace(' ', 'T')
        try {
            return when {
                t.length == 10 -> LocalDate.parse(t)
                !t.contains('-') && !t.contains('T') -> LocalTime.parse(t)
                t.endsWith("Z") || t.substringAfter('T').let { '+' in it || '-' in it } -> OffsetDateTime.parse(t)
                else -> LocalDateTime.parse(t)
            }
        } catch (e: java.time.format.DateTimeParseException) {
            throw SqlException("Cannot read '$text' as a date or a time", "22007", cause = e)
        }
    }

    /** Not `toBigDecimalOrNull`, which runs a regular expression first. */
    private fun decimal(value: SqlDecimal): Any = if (value.text == "NaN") value.text else BigDecimal(value.text)

    private fun asString(v: Any?): String = when (v) {
        is ByteArray -> v.decodeToString()
        is Array<*> -> v.joinToString(",", "{", "}")
        is BigDecimal -> v.toPlainString()
        else -> v.toString()
    }

    private fun asBoolean(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.trim().lowercase()) {
            "t", "true", "1", "y", "yes", "on" -> true
            "f", "false", "0", "n", "no", "off" -> false
            else -> throw SqlException("Cannot convert '$v' to Boolean", "22018")
        }
        else -> throw SqlException("Cannot convert ${v?.javaClass?.simpleName} to Boolean", "22023")
    }

    private fun number(v: Any?): BigDecimal = try {
        when (v) {
            is BigDecimal -> v
            is Boolean -> if (v) BigDecimal.ONE else BigDecimal.ZERO
            is Float, is Double -> BigDecimal((v as Number).toDouble().toString())
            is Number -> BigDecimal(v.toString())
            is String -> BigDecimal(v.trim())
            else -> throw SqlException("Cannot convert ${v?.javaClass?.simpleName} to a number", "22023")
        }
    } catch (e: NumberFormatException) {
        throw SqlException("Cannot convert '$v' to a number", "22018", cause = e)
    }
}
