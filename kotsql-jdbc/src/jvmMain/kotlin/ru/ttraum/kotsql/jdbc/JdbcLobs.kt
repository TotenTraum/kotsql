package ru.ttraum.kotsql.jdbc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.sql.Blob
import java.sql.NClob
import java.sql.SQLException

/**
 * Large values as JDBC's objects, held in memory: the databases here have no server-side handles for them, so the whole value
 * arrives with the row anyway. Tools such as Hibernate go through these for `@Lob`.
 */
internal class JdbcClob(initial: String = "") : NClob {
    private var text: StringBuilder? = StringBuilder(initial)
    private fun value() = text ?: throw SQLException("The Clob has been freed")

    private fun position1(position: Long): Int {
        if (position < 1 || position > value().length + 1L) throw SQLException("Position $position is out of range", "22011")
        return (position - 1).toInt()
    }

    override fun length() = value().length.toLong()
    override fun getSubString(pos: Long, length: Int): String {
        val start = position1(pos)
        if (length < 0) throw SQLException("Negative length", "22011")
        return value().substring(start, minOf(start.toLong() + length, value().length.toLong()).toInt())
    }
    override fun getCharacterStream(): Reader = StringReader(value().toString())
    override fun getCharacterStream(pos: Long, length: Long): Reader = StringReader(getSubString(pos, length.toInt()))
    override fun getAsciiStream(): InputStream = ByteArrayInputStream(value().toString().toByteArray(Charsets.US_ASCII))
    override fun position(searchstr: String, start: Long): Long = value().indexOf(searchstr, position1(start)).let { if (it < 0) -1 else it + 1L }
    override fun position(searchstr: java.sql.Clob, start: Long): Long = position(searchstr.getSubString(1, searchstr.length().toInt()), start)
    override fun setString(pos: Long, str: String): Int = setString(pos, str, 0, str.length)
    override fun setString(pos: Long, str: String, offset: Int, len: Int): Int {
        val start = position1(pos)
        val part = str.substring(offset, offset + len)
        value().replace(start, minOf(start + len, value().length), part)
        return len
    }
    override fun setAsciiStream(pos: Long): OutputStream = object : ByteArrayOutputStream() {
        override fun close() { setString(pos, toString(Charsets.US_ASCII)) }
    }
    override fun setCharacterStream(pos: Long): Writer = object : StringWriter() {
        override fun close() { setString(pos, toString()) }
    }
    override fun truncate(len: Long) { value().setLength(len.toInt()) }
    override fun free() { text = null }
    override fun toString() = value().toString()
}

internal class JdbcBlob(initial: ByteArray = ByteArray(0)) : Blob {
    private var data: ByteArray? = initial
    private fun value() = data ?: throw SQLException("The Blob has been freed")

    private fun position1(position: Long): Int {
        if (position < 1 || position > value().size + 1L) throw SQLException("Position $position is out of range", "22011")
        return (position - 1).toInt()
    }

    override fun length() = value().size.toLong()
    override fun getBytes(pos: Long, length: Int): ByteArray {
        val start = position1(pos)
        if (length < 0) throw SQLException("Negative length", "22011")
        return value().copyOfRange(start, minOf(start.toLong() + length, value().size.toLong()).toInt())
    }
    override fun getBinaryStream(): InputStream = ByteArrayInputStream(value())
    override fun getBinaryStream(pos: Long, length: Long): InputStream = ByteArrayInputStream(getBytes(pos, length.toInt()))
    override fun position(pattern: ByteArray, start: Long): Long {
        val bytes = value()
        for (i in position1(start)..bytes.size - pattern.size) {
            if (pattern.indices.all { bytes[i + it] == pattern[it] }) return i + 1L
        }
        return -1
    }
    override fun position(pattern: Blob, start: Long): Long = position(pattern.getBytes(1, pattern.length().toInt()), start)
    override fun setBytes(pos: Long, bytes: ByteArray): Int = setBytes(pos, bytes, 0, bytes.size)
    override fun setBytes(pos: Long, bytes: ByteArray, offset: Int, len: Int): Int {
        val start = position1(pos)
        val target = if (start + len > value().size) value().copyOf(start + len) else value()
        bytes.copyInto(target, start, offset, offset + len)
        data = target
        return len
    }
    override fun setBinaryStream(pos: Long): OutputStream = object : ByteArrayOutputStream() {
        override fun close() { setBytes(pos, toByteArray()) }
    }
    override fun truncate(len: Long) { data = value().copyOf(len.toInt()) }
    override fun free() { data = null }
}
