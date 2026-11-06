package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.SqlException

/** Growable output buffer with primitives in either byte order. */
class ByteWriter(initialCapacity: Int = 512) {
    var buffer = ByteArray(initialCapacity)
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun clear() { size = 0 }
    fun int8(v: Int) { ensure(1); buffer[size++] = v.toByte() }
    fun int16(v: Int) { int8(v ushr 8); int8(v) }
    fun int32(v: Int) { int16(v ushr 16); int16(v) }
    fun int16LE(v: Int) { int8(v); int8(v ushr 8) }
    fun int24LE(v: Int) { int16LE(v); int8(v ushr 16) }
    fun int32LE(v: Int) { int16LE(v); int16LE(v ushr 16) }
    fun int64LE(v: Long) { int32LE(v.toInt()); int32LE((v ushr 32).toInt()) }
    fun bytes(v: ByteArray) = bytes(v, 0, v.size)
    fun bytes(v: ByteArray, offset: Int, length: Int) {
        ensure(length)
        v.copyInto(buffer, size, offset, offset + length)
        size += length
    }
    fun cstring(v: String) { bytes(v.encodeToByteArray()); int8(0) }

    /** Overwrites 4 bytes at [position], for length prefixes. */
    fun patchInt32(position: Int, v: Int) {
        buffer[position] = (v ushr 24).toByte()
        buffer[position + 1] = (v ushr 16).toByte()
        buffer[position + 2] = (v ushr 8).toByte()
        buffer[position + 3] = v.toByte()
    }
}

/** Sequential reader over a byte array. */
class ByteReader(private val data: ByteArray) {
    var position = 0
    val remaining get() = data.size - position

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw SqlException("Malformed message from server", "08P01")
    }
    fun int8(): Int { need(1); return data[position++].toInt() and 0xFF }
    fun int16(): Int = (int8() shl 8) or int8()
    fun int32(): Int = (int16() shl 16) or int16()
    fun int16LE(): Int = int8() or (int8() shl 8)
    fun int24LE(): Int = int16LE() or (int8() shl 16)
    fun int32LE(): Int = int16LE() or (int16LE() shl 16)
    fun int64LE(): Long = (int32LE().toLong() and 0xFFFFFFFFL) or (int32LE().toLong() shl 32)
    fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(position, position + n).also { position += n } }
    fun rest(): ByteArray = bytes(remaining)
    /** Moves past a zero-terminated string without making it. */
    fun skipCString() {
        while (position < data.size && data[position] != 0.toByte()) position++
        if (position == data.size) throw SqlException("Malformed message from server", "08P01")
        position++
    }

    fun cstring(): String {
        var end = position
        while (end < data.size && data[end] != 0.toByte()) end++
        if (end == data.size) throw SqlException("Malformed message from server", "08P01")
        return data.decodeToString(position, end).also { position = end + 1 }
    }
}

private const val MAX_UNVERIFIED_ALLOCATION = 1024 * 1024

/** Buffered reads over a [Transport]. */
class BufferedInput(private val transport: Transport, capacity: Int = 16 * 1024) {
    private val buffer = ByteArray(capacity)
    private var start = 0
    private var end = 0

    /** Bytes received but not consumed yet. */
    val buffered get() = end - start

    private suspend fun fill() {
        start = 0
        end = 0
        val n = transport.read(buffer, 0, buffer.size)
        if (n < 0) throw SqlException("The server closed the connection", "08006")
        end = n
    }

    /** The byte at [offset] among the [buffered] ones, without consuming it. */
    fun peekByte(offset: Int): Int = buffer[start + offset].toInt() and 0xFF

    /** The big-endian integer at [offset] among the [buffered] ones, without consuming it. */
    fun peekInt32(offset: Int): Int = (peekByte(offset) shl 24) or (peekByte(offset + 1) shl 16) or
        (peekByte(offset + 2) shl 8) or peekByte(offset + 3)

    fun skipBuffered(count: Int) {
        check(count <= buffered)
        start += count
    }

    /** Consumes [count] of the [buffered] bytes. */
    fun takeBuffered(count: Int): ByteArray {
        check(count <= buffered)
        return buffer.copyOfRange(start, start + count).also { start += count }
    }

    // The methods below have a fast path that returns before the state machine of the suspending slow path exists.
    // Kotlin only allocates a continuation for a suspending function with a call that isn't in tail position.

    suspend fun readByte(): Int {
        if (start == end) return readByteSlow()
        return buffer[start++].toInt() and 0xFF
    }

    private suspend fun readByteSlow(): Int {
        fill()
        return buffer[start++].toInt() and 0xFF
    }

    suspend fun readInt32(): Int {
        if (buffered < 4) return readInt32Slow()
        return peekInt32(0).also { start += 4 }
    }

    private suspend fun readInt32Slow(): Int = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    suspend fun readFully(target: ByteArray) {
        if (buffered < target.size) return readFullySlow(target)
        buffer.copyInto(target, 0, start, start + target.size)
        start += target.size
    }

    /**
     * [size] bytes that a length prefix announced. Memory grows with what actually arrives, since the prefix itself is only
     * a claim: a broken or hostile server could announce 2 GB and send nothing.
     */
    suspend fun readAnnounced(size: Int): ByteArray {
        if (size <= MAX_UNVERIFIED_ALLOCATION) return ByteArray(size).also { readFully(it) }
        return readAnnouncedSlow(size)
    }

    private suspend fun readAnnouncedSlow(size: Int): ByteArray {
        var target = ByteArray(MAX_UNVERIFIED_ALLOCATION)
        var done = 0
        while (done < size) {
            if (done == target.size) target = target.copyOf(if (target.size > size / 2) size else target.size * 2)
            if (start == end) fill()
            val n = minOf(end - start, target.size - done)
            buffer.copyInto(target, done, start, start + n)
            start += n
            done += n
        }
        return target
    }

    private suspend fun readFullySlow(target: ByteArray) {
        var done = 0
        while (done < target.size) {
            if (start == end) fill()
            val n = minOf(end - start, target.size - done)
            buffer.copyInto(target, done, start, start + n)
            start += n
            done += n
        }
    }
}
