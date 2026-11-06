package dev.ksql.r2dbc

import dev.ksql.ExecuteOptions
import dev.ksql.JvmValues
import dev.ksql.SqlCursor
import dev.ksql.SqlException
import dev.ksql.SqlType
import dev.ksql.TypedNull
import io.r2dbc.spi.Batch
import io.r2dbc.spi.Blob
import io.r2dbc.spi.Clob
import io.r2dbc.spi.Parameter
import io.r2dbc.spi.Result
import io.r2dbc.spi.Statement
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.publish
import org.reactivestreams.Publisher
import java.nio.ByteBuffer

private object Unbound

/** A bound parameter that must be read before the statement runs. */
private class Lob(val read: suspend () -> Any)

internal class R2dbcStatement(private val conn: R2dbcConnection, private val sql: String) : Statement {
    private val parameterCount = conn.parameterCount(sql)
    private var current = newBinding()
    private val bindings = ArrayList<Array<Any?>>()
    private var fetchSize = 0
    private var generatedColumns: List<String>? = null
    private var trailingAdd = false

    private fun newBinding() = arrayOfNulls<Any?>(parameterCount).also { it.fill(Unbound) }

    private fun set(index: Int, value: Any?) {
        if (index !in 0 until parameterCount) throw IndexOutOfBoundsException("Parameter index $index, the statement has $parameterCount")
        current[index] = value
        trailingAdd = false
    }

    private fun indexOf(name: String?): Int {
        if (name == null) throw IllegalArgumentException("The parameter name must not be null")
        return conn.parameterIndex(name)?.takeIf { it < parameterCount }
            ?: throw NoSuchElementException("No parameter named '$name'; use markers like the ones in the SQL")
    }

    private fun canonical(value: Any?): Any? = when (value) {
        null -> throw IllegalArgumentException("The value must not be null; use bindNull")
        is Parameter -> if (value.value == null) TypedNull(sqlTypeOf(value.type.javaType)) else canonical(value.value)
        is Blob -> Lob {
            val parts = value.stream().asFlow().toList()
            ByteArray(parts.sumOf { it.remaining() }).also { all ->
                val out = ByteBuffer.wrap(all)
                parts.forEach { out.put(it) }
            }
        }
        is Clob -> Lob { value.stream().asFlow().toList().joinToString("") }
        else -> try {
            JvmValues.toCanonical(conn.codecs.encode(value))
        } catch (e: SqlException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    override fun bind(index: Int, value: Any): Statement { val v = canonical(value); set(index, v); return this }
    override fun bind(name: String, value: Any): Statement { val i = indexOf(name); set(i, canonical(value)); return this }
    override fun bindNull(index: Int, type: Class<*>): Statement { set(index, nullOf(type)); return this }
    override fun bindNull(name: String, type: Class<*>): Statement { val i = indexOf(name); set(i, nullOf(type)); return this }

    @Suppress("SENSELESS_COMPARISON")
    private fun nullOf(type: Class<*>?): TypedNull {
        if (type == null) throw IllegalArgumentException("The type must not be null")
        return TypedNull(sqlTypeOf(type))
    }

    override fun add(): Statement {
        if (current.any { it === Unbound }) throw IllegalStateException("Not all parameters of the statement are bound")
        bindings += current
        current = newBinding()
        trailingAdd = true
        return this
    }

    override fun fetchSize(rows: Int): Statement {
        require(rows >= 0) { "The fetch size must not be negative" }
        fetchSize = rows
        return this
    }

    override fun returnGeneratedValues(vararg columns: String): Statement {
        @Suppress("SENSELESS_COMPARISON")
        if (columns == null) throw IllegalArgumentException("columns must not be null")
        generatedColumns = columns.toList()
        return this
    }

    override fun execute(): Publisher<out Result> {
        if (trailingAdd) throw IllegalStateException("add() must be followed by more bindings before execute()")
        val partlyBound = current.any { it !== Unbound }
        if (partlyBound || (parameterCount > 0 && bindings.isEmpty())) {
            add()
            trailingAdd = false
        }
        val runs = bindings.toList()
        val keysSql = generatedColumns?.let { conn.withGeneratedKeys(sql, it) }
        val text = keysSql ?: sql
        val wantKeys = keysSql != null
        return publish(PublisherContext) {
            if (runs.isEmpty()) {
                // Plain statements take the simple protocol, which allows several in one string.
                runCursor(conn, wantKeys) {
                    if (fetchSize > 0) execute(text, emptyList(), ExecuteOptions(fetchSize)) else executeScript(text)
                }
            } else for (binding in runs) {
                val params = binding.map { if (it is Lob) it.read() else it }
                runCursor(conn, wantKeys) { execute(text, params, ExecuteOptions(fetchSize)) }
            }
        }
    }
}

internal class R2dbcBatch(private val conn: R2dbcConnection) : Batch {
    private val statements = ArrayList<String>()

    override fun add(sql: String): Batch {
        @Suppress("SENSELESS_COMPARISON")
        if (sql == null) throw IllegalArgumentException("sql must not be null")
        statements += sql
        return this
    }

    override fun execute(): Publisher<out Result> {
        val script = statements.joinToString("; ")
        return publish(PublisherContext) { runCursor(conn) { executeScript(script) } }
    }
}

/**
 * Emits one [Result] per statement outcome.
 *
 * A subscriber may cancel this publisher right after the first result and still read it (`Mono.from(statement.execute())`
 * does so), so cancellation here must not close the cursor: only the result's own subscriber does that.
 */
private suspend fun ProducerScope<Result>.runCursor(conn: R2dbcConnection, wantKeys: Boolean = false, start: suspend dev.ksql.SqlConnection.() -> SqlCursor) {
    val cursor = conn.op {
        with(conn) { beginIfNeeded() }
        start()
    }
    while (true) {
        val result = conn.op { cursor.nextResult() } ?: return
        val source = ResultSource(conn, cursor, result, wantKeys)
        send(R2dbcResult(source))
        source.awaitTurnEnd()
    }
}

internal fun sqlTypeOf(type: Class<*>): SqlType = when (type.kotlin.javaObjectType) {
    java.lang.Boolean::class.java -> SqlType.BOOLEAN
    java.lang.Byte::class.java, java.lang.Short::class.java -> SqlType.SMALLINT
    java.lang.Integer::class.java -> SqlType.INTEGER
    java.lang.Long::class.java -> SqlType.BIGINT
    java.lang.Float::class.java -> SqlType.REAL
    java.lang.Double::class.java -> SqlType.DOUBLE
    java.math.BigDecimal::class.java, java.math.BigInteger::class.java -> SqlType.DECIMAL
    ByteArray::class.java, ByteBuffer::class.java, Blob::class.java -> SqlType.BINARY
    java.time.LocalDate::class.java -> SqlType.DATE
    java.time.LocalTime::class.java -> SqlType.TIME
    java.time.OffsetTime::class.java -> SqlType.TIME_TZ
    java.time.LocalDateTime::class.java -> SqlType.TIMESTAMP
    java.time.Instant::class.java, java.time.OffsetDateTime::class.java, java.time.ZonedDateTime::class.java -> SqlType.TIMESTAMP_TZ
    java.util.UUID::class.java -> SqlType.UUID
    else -> SqlType.TEXT
}

