package ru.ttraum.kotsql.r2dbc

import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.jvm.JvmValues
import ru.ttraum.kotsql.SqlCursor
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.spi.generatedKeyRows
import io.r2dbc.spi.Blob
import io.r2dbc.spi.Clob
import io.r2dbc.spi.ColumnMetadata
import io.r2dbc.spi.Nullability
import io.r2dbc.spi.Readable
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.reactive.collect
import kotlinx.coroutines.reactive.publish
import kotlinx.coroutines.withContext
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate

private enum class State { UNCLAIMED, STREAMING, BUFFERING, CONSUMED }

/**
 * One result's data, consumable once. Rows are pulled straight from the connection for the subscriber, so backpressure
 * reaches the server. The statement publisher can only move on to the next result when this one is out of the way, so
 * if nobody has subscribed by the time it gets the chance (the subscriber may be collecting results to read later, or
 * have put a `publishOn` in between), the rows move into memory.
 */
internal class ResultSource(
    private val conn: R2dbcConnection,
    private val cursor: SqlCursor,
    private val result: SqlResult,
    private val wantKeys: Boolean = false,
) {
    private val state = AtomicReference(State.UNCLAIMED)
    private val buffer = ArrayDeque<SqlRow>()
    private val buffered = CompletableDeferred<Unit>()
    private val consumed = CompletableDeferred<Unit>()
    private var bufferError: Throwable? = null

    suspend fun awaitTurnEnd() {
        if (result !is SqlResult.Rows) return
        // A subscriber that cancelled the statement publisher, as Mono.from does, may read this result later.
        currentCoroutineContext().ensureActive()
        if (state.compareAndSet(State.UNCLAIMED, State.BUFFERING)) {
            try {
                conn.op { while (true) buffer += result.next() ?: break }
            } catch (e: Exception) {
                bufferError = e
            } finally {
                buffered.complete(Unit)
            }
        } else {
            consumed.await()
        }
    }

    /**
     * Reserves the result for one subscriber. This has to happen in `subscribe` itself, not later in the subscriber's
     * coroutine, or [awaitTurnEnd] would see no subscriber yet and needlessly buffer everything.
     *
     * @return whether the rows are to be read from memory
     */
    fun claim(): Boolean = when {
        state.compareAndSet(State.UNCLAIMED, State.STREAMING) -> false
        state.compareAndSet(State.BUFFERING, State.CONSUMED) -> true
        else -> throw IllegalStateException("A Result can be consumed only once")
    }

    fun abandon() = conn.abandon(cursor)

    /** Passes the result's segments to [handler] in order. */
    suspend fun consume(fromMemory: Boolean, handler: suspend (Result.Segment) -> Unit) {
        if (fromMemory) buffered.await()
        try {
            when (result) {
                is SqlResult.UpdateCount -> {
                    if (wantKeys && result.generatedKeys.isNotEmpty()) {
                        val keys = generatedKeyRows(result.generatedKeys)
                        val metadata = R2dbcRowMetadata(keys.columns)
                        while (true) handler(RowSegmentImpl(R2dbcRow(keys.nextBuffered() ?: break, metadata)))
                    }
                    handler(UpdateSegment(result.count))
                }
                is SqlResult.Rows -> {
                    val metadata = R2dbcRowMetadata(result.columns)
                    while (true) {
                        val row = if (fromMemory) buffer.removeFirstOrNull() else conn.nextRowWithoutWaiting(result) ?: conn.op { result.next() }
                        handler(RowSegmentImpl(R2dbcRow(row ?: break, metadata)))
                    }
                    bufferError?.takeIf { fromMemory }?.let { throw it }
                    result.updateCount?.let { handler(UpdateSegment(it)) }
                }
            }
        } catch (e: CancellationException) {
            // The subscriber lost interest: stop reading, which also ends a paged query on the server.
            withContext(NonCancellable) { conn.op { } }
            throw e
        } finally {
            consumed.complete(Unit)
        }
    }
}

/** Runs [action] on the cancelling thread, before the cancellation reaches the publisher's coroutine. */
private fun <T> Publisher<T>.onCancel(action: () -> Unit): Publisher<T> = Publisher { subscriber ->
    subscribe(object : Subscriber<T> {
        override fun onSubscribe(subscription: Subscription) = subscriber.onSubscribe(object : Subscription {
            override fun request(n: Long) = subscription.request(n)
            override fun cancel() {
                action()
                subscription.cancel()
            }
        })
        override fun onNext(value: T) = subscriber.onNext(value)
        override fun onError(error: Throwable) = subscriber.onError(error)
        override fun onComplete() = subscriber.onComplete()
    })
}

private class UpdateSegment(private val count: Long) : Result.UpdateCount {
    override fun value() = count
}

private class RowSegmentImpl(private val row: Row) : Result.RowSegment {
    override fun row() = row
}

internal class R2dbcResult(private val source: ResultSource, private val filter: Predicate<Result.Segment>? = null) : Result {
    private fun <T : Any> consuming(body: suspend ProducerScope<T>.(fromMemory: Boolean) -> Unit): Publisher<T> =
        Publisher { subscriber ->
            val claim = runCatching { source.claim() }
            publish<T>(PublisherContext) { translating { body(claim.getOrThrow()) } }
                .onCancel(source::abandon)
                .subscribe(subscriber)
        }

    private fun <T : Any> stream(block: suspend ProducerScope<T>.(Result.Segment) -> Unit): Publisher<T> =
        consuming { fromMemory ->
            source.consume(fromMemory) { segment -> if (filter == null || filter.test(segment)) block(segment) }
        }

    override fun getRowsUpdated(): Publisher<Long> = consuming { fromMemory ->
        var total: Long? = null
        source.consume(fromMemory) { segment ->
            if (segment is Result.UpdateCount && (filter == null || filter.test(segment))) total = (total ?: 0) + segment.value()
        }
        total?.let { send(it) }
    }

    override fun <T : Any> map(mapping: BiFunction<Row, RowMetadata, out T>): Publisher<T> {
        @Suppress("SENSELESS_COMPARISON")
        if (mapping == null) throw IllegalArgumentException("mapping must not be null")
        return stream { segment -> if (segment is Result.RowSegment) send(mapping.apply(segment.row(), segment.row().metadata)) }
    }

    override fun <T : Any> map(mapping: Function<in Readable, out T>): Publisher<T> {
        @Suppress("SENSELESS_COMPARISON")
        if (mapping == null) throw IllegalArgumentException("mapping must not be null")
        return stream { segment ->
            when (segment) {
                is Result.RowSegment -> send(mapping.apply(segment.row()))
                is Result.OutSegment -> send(mapping.apply(segment.outParameters()))
                else -> Unit
            }
        }
    }

    override fun filter(predicate: Predicate<Result.Segment>): Result {
        @Suppress("SENSELESS_COMPARISON")
        if (predicate == null) throw IllegalArgumentException("predicate must not be null")
        return R2dbcResult(source, filter?.and(predicate) ?: predicate)
    }

    override fun <T : Any> flatMap(mapping: Function<Result.Segment, out Publisher<out T>>): Publisher<T> {
        @Suppress("SENSELESS_COMPARISON")
        if (mapping == null) throw IllegalArgumentException("mapping must not be null")
        return stream { segment -> mapping.apply(segment).collect { send(it) } }
    }
}

internal class R2dbcRow(private val row: SqlRow, private val metadata: R2dbcRowMetadata) : Row {
    override fun getMetadata(): RowMetadata = metadata

    override fun <T : Any> get(index: Int, type: Class<T>): T? {
        @Suppress("SENSELESS_COMPARISON")
        if (type == null) throw IllegalArgumentException("type must not be null")
        return decode(translating { row[index] }, type, index)
    }

    override fun <T : Any> get(name: String, type: Class<T>): T? {
        @Suppress("SENSELESS_COMPARISON")
        if (name == null) throw IllegalArgumentException("name must not be null")
        val i = row.indexOf(name)
        if (i < 0) throw NoSuchElementException("No column named '$name'")
        return get(i, type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> decode(value: Any?, type: Class<T>, index: Int): T? {
        if (value == null) return null
        return try {
            when {
                // An application's class, which a codec can make from the column's value.
                !row.codecs.isEmpty && !type.isInstance(value) && row.codecs.forClass(type.kotlin) != null -> translating { row.get(index, type.kotlin) }
                // The SPI maps binary columns to ByteBuffer by default.
                type == Any::class.java && value is ByteArray -> ByteBuffer.wrap(value) as T
                type == Blob::class.java && value is ByteArray ->
                    Blob.from(one { ByteBuffer.wrap(value) }) as T
                type == Clob::class.java && value is String -> Clob.from(one<CharSequence> { value }) as T
                else -> JvmValues.convert(value, type)
            }
        } catch (e: SqlException) {
            throw IllegalArgumentException(e.message, e)
        }
    }
}

internal class R2dbcRowMetadata(private val columns: List<ColumnInfo>) : RowMetadata {
    private val metadata = columns.map(::R2dbcColumnMetadata)

    override fun getColumnMetadata(index: Int): ColumnMetadata =
        metadata.getOrNull(index) ?: throw IndexOutOfBoundsException("Column index $index, the row has ${metadata.size} columns")

    override fun getColumnMetadata(name: String): ColumnMetadata {
        @Suppress("SENSELESS_COMPARISON")
        if (name == null) throw IllegalArgumentException("name must not be null")
        return metadata.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: throw NoSuchElementException("No column named '$name'")
    }

    override fun getColumnMetadatas(): List<ColumnMetadata> = metadata
    override fun contains(columnName: String) = columns.any { it.name.equals(columnName, ignoreCase = true) }
}

private class R2dbcColumnMetadata(private val column: ColumnInfo) : ColumnMetadata {
    override fun getName() = column.name
    override fun getType(): Type = object : Type {
        override fun getJavaType() = this@R2dbcColumnMetadata.javaType
        override fun getName() = column.nativeTypeName
    }
    override fun getPrecision(): Int? = column.precision.takeIf { it > 0 }
    override fun getScale(): Int? = column.scale.takeIf { column.precision > 0 }
    override fun getNativeTypeMetadata(): Any = column.nativeTypeName
    override fun getNullability() = when (column.nullable) {
        true -> Nullability.NULLABLE
        false -> Nullability.NON_NULL
        null -> Nullability.UNKNOWN
    }
    override fun getJavaType(): Class<*> = when (column.type) {
        SqlType.BOOLEAN -> java.lang.Boolean::class.java
        SqlType.SMALLINT -> java.lang.Short::class.java
        SqlType.INTEGER -> java.lang.Integer::class.java
        SqlType.BIGINT -> java.lang.Long::class.java
        SqlType.REAL -> java.lang.Float::class.java
        SqlType.DOUBLE -> java.lang.Double::class.java
        SqlType.DECIMAL -> java.math.BigDecimal::class.java
        SqlType.BINARY -> ByteBuffer::class.java
        SqlType.DATE -> java.time.LocalDate::class.java
        SqlType.TIME -> java.time.LocalTime::class.java
        SqlType.TIME_TZ -> java.time.OffsetTime::class.java
        SqlType.TIMESTAMP -> java.time.LocalDateTime::class.java
        SqlType.TIMESTAMP_TZ -> java.time.OffsetDateTime::class.java
        SqlType.UUID -> java.util.UUID::class.java
        SqlType.ARRAY -> Array<Any>::class.java
        else -> java.lang.String::class.java
    }
}
