@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package ru.ttraum.kotsql

import kotlin.concurrent.atomics.AtomicReference
import kotlin.reflect.KClass
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Converts between an application's class and a canonical value, the types documented on [SqlRow]: usually a [String]. Since
 * no bytes of any protocol are involved, one codec works with every driver, on every platform and through JDBC and R2DBC.
 *
 * @param typeNames the database types whose columns decode to [T] by themselves, case-insensitively, without their schema.
 *   With none, a column only becomes a [T] when asked for one, with [SqlRow.get] or `getObject(i, Class)`. That suits a type
 *   that's stored as an ordinary one, and MySQL's `ENUM`, which has no name of its own.
 */
public abstract class SqlCodec<T : Any>(public val type: KClass<T>, public val typeNames: Set<String> = emptySet()) {
    /** The canonical value to send as a parameter. */
    public abstract fun encode(value: T): Any

    /** The object for a canonical value, which is never null. Throwing here reports the column's value as not convertible. */
    public abstract fun decode(value: Any): T

    public companion object {
        /** A codec from two functions. */
        public inline fun <reified T : Any> of(
            vararg typeNames: String,
            crossinline encode: (T) -> Any,
            crossinline decode: (Any) -> T,
        ): SqlCodec<T> = object : SqlCodec<T>(T::class, typeNames.toSet()) {
            override fun encode(value: T): Any = encode(value)
            override fun decode(value: Any): T = decode(value)
        }

        /**
         * An enum class stored by the names of its constants. Give the name of a PostgreSQL enum type for its columns to decode
         * by themselves. Enum parameters need no codec: they're sent by name in any case.
         */
        public inline fun <reified E : Enum<E>> enum(vararg typeNames: String): SqlCodec<E> = of(
            *typeNames,
            encode = { it.name },
            decode = { value ->
                val name = value.toString()
                enumValues<E>().firstOrNull { it.name == name }
                    ?: throw IllegalArgumentException("'$name' is not a constant of ${E::class.simpleName}")
            },
        )
    }
}

/** A set of codecs, safe to read and add to from any thread. Drivers take theirs from [ConnectionConfig.codecs]. */
public class CodecRegistry(vararg codecs: SqlCodec<*>) {
    private class Index(val all: List<SqlCodec<*>>) {
        val byClass = all.associateBy { it.type }
        val byTypeName = all.flatMap { codec -> codec.typeNames.map { it.lowercase() to codec } }.toMap()
    }

    private val index = AtomicReference(Index(codecs.toList()))

    public val isEmpty: Boolean get() = index.load().all.isEmpty()

    /** Adds a codec, which takes precedence over one for the same class or database type. Connections see it at once. */
    public fun register(codec: SqlCodec<*>): CodecRegistry {
        while (true) {
            val current = index.load()
            val replaced = current.all.filter { it.type != codec.type } + codec
            if (index.compareAndSet(current, Index(replaced))) return this
        }
    }

    /** The codec that decodes a column of this database type by itself. */
    public fun forTypeName(name: String): SqlCodec<*>? {
        val current = index.load()
        return if (current.all.isEmpty()) null else current.byTypeName[name.lowercase()]
    }

    /** The codec registered for exactly this class. */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> forClass(type: KClass<T>): SqlCodec<T>? = index.load().byClass[type] as SqlCodec<T>?

    @Suppress("UNCHECKED_CAST")
    private fun forValue(value: Any): SqlCodec<Any>? {
        val current = index.load()
        if (current.all.isEmpty()) return null
        return (current.byClass[value::class] ?: current.all.firstOrNull { it.type.isInstance(value) }) as SqlCodec<Any>?
    }

    /** What to send for a parameter: a codec's canonical value, an enum's name, or the value itself. Lists go by element. */
    public fun encode(value: Any?): Any? = when (value) {
        null, is String, is Long, is Int, is Boolean -> value
        is List<*> -> value.map(::encode)
        else -> forValue(value)?.encode(value) ?: if (value is Enum<*>) value.name else value
    }

    public companion object {
        /** What connections use unless told otherwise, and all that `DriverManager` and `ConnectionFactories` can offer. */
        public val Global: CodecRegistry = CodecRegistry()
    }
}

/** Reads a column as [type], through a codec if it isn't that already. Null for NULL. */
internal fun <T : Any> convertToClass(row: SqlRow, index: Int, type: KClass<T>): T? {
    val value = row[index] ?: return null
    if (type.isInstance(value)) {
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    val codec = row.codecs.forClass(type)
        ?: throw SqlException("Cannot convert a ${value::class.simpleName} to ${type.simpleName}: no codec is registered for it", "22023")
    return try {
        // The column may have decoded to another class, by another codec, but the text is what both started from.
        codec.decode(canonicalOf(value) ?: row.getText(index)!!)
    } catch (e: IllegalArgumentException) {
        throw SqlException("Cannot convert the column to ${type.simpleName}: ${e.message}", "22023", cause = e)
    }
}

private fun canonicalOf(value: Any): Any? = when (value) {
    is String, is Long, is Int, is Short, is Boolean, is Float, is Double, is SqlDecimal, is ByteArray, is LocalDate, is LocalTime,
    is LocalDateTime, is kotlin.time.Instant, is SqlOffsetTime, is Uuid, is List<*> -> value
    else -> null
}
