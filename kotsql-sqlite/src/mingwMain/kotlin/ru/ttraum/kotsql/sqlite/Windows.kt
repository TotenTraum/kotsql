@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.sqlite

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import ru.ttraum.kotsql.sqlite.c.SQLITE_DONE
import ru.ttraum.kotsql.sqlite.c.SQLITE_OK
import ru.ttraum.kotsql.sqlite.c.SQLITE_ROW
import ru.ttraum.kotsql.sqlite.c.kotsql_bind_blob
import ru.ttraum.kotsql.sqlite.c.kotsql_bind_text
import cnames.structs.sqlite3
import ru.ttraum.kotsql.sqlite.c.sqlite3_bind_double
import ru.ttraum.kotsql.sqlite.c.sqlite3_bind_int64
import ru.ttraum.kotsql.sqlite.c.sqlite3_bind_null
import ru.ttraum.kotsql.sqlite.c.sqlite3_clear_bindings
import ru.ttraum.kotsql.sqlite.c.sqlite3_close_v2
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_blob
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_bytes
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_count
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_double
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_int64
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_name
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_text
import ru.ttraum.kotsql.sqlite.c.sqlite3_column_type
import ru.ttraum.kotsql.sqlite.c.sqlite3_errmsg
import ru.ttraum.kotsql.sqlite.c.sqlite3_extended_errcode
import ru.ttraum.kotsql.sqlite.c.sqlite3_finalize
import ru.ttraum.kotsql.sqlite.c.sqlite3_get_autocommit
import ru.ttraum.kotsql.sqlite.c.sqlite3_open_v2
import ru.ttraum.kotsql.sqlite.c.sqlite3_prepare_v2
import ru.ttraum.kotsql.sqlite.c.sqlite3_reset
import ru.ttraum.kotsql.sqlite.c.sqlite3_step
import cnames.structs.sqlite3_stmt

private fun failure(db: CPointer<sqlite3>?, code: Int): SqliteFailure {
    if (db == null) return SqliteFailure(code, "SQLite failed with code $code")
    return SqliteFailure(sqlite3_extended_errcode(db), sqlite3_errmsg(db)?.toKString() ?: "SQLite failed with code $code")
}

private class WindowsDatabase(private val db: CPointer<sqlite3>) : NativeDatabase {
    private var closed = false

    override fun prepare(sql: String): NativeStatement = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        // Only the first statement, as androidx.sqlite does: the driver splits scripts itself.
        val code = sqlite3_prepare_v2(db, sql, -1, out.ptr, null)
        if (code != SQLITE_OK) throw failure(db, code)
        // A text of nothing but a comment or whitespace prepares to no statement at all.
        WindowsStatement(db, out.value ?: throw SqliteFailure(21, "Not a statement: '$sql'"))
    }

    override fun inTransaction() = sqlite3_get_autocommit(db) == 0

    override fun close() {
        if (closed) return
        closed = true
        val code = sqlite3_close_v2(db)
        if (code != SQLITE_OK) throw failure(null, code)
    }
}

private class WindowsStatement(private val db: CPointer<sqlite3>, private val statement: CPointer<sqlite3_stmt>) : NativeStatement {
    private var closed = false

    private fun check(code: Int) {
        if (code != SQLITE_OK) throw failure(db, code)
    }

    override fun bindBlob(index: Int, value: ByteArray) {
        if (value.isEmpty()) return check(kotsql_bind_blob(statement, index, EMPTY, 0))
        value.usePinned { check(kotsql_bind_blob(statement, index, it.addressOf(0), value.size)) }
    }

    override fun bindDouble(index: Int, value: Double) = check(sqlite3_bind_double(statement, index, value))
    override fun bindLong(index: Int, value: Long) = check(sqlite3_bind_int64(statement, index, value))

    override fun bindText(index: Int, value: String) {
        val bytes = value.encodeToByteArray()
        if (bytes.isEmpty()) return check(kotsql_bind_text(statement, index, EMPTY.reinterpret(), 0))
        bytes.usePinned { check(kotsql_bind_text(statement, index, it.addressOf(0), bytes.size)) }
    }

    override fun bindNull(index: Int) = check(sqlite3_bind_null(statement, index))

    private fun bytes(pointer: CPointer<*>?, index: Int): ByteArray {
        val size = sqlite3_column_bytes(statement, index)
        if (pointer == null || size == 0) return ByteArray(0)
        return pointer.reinterpret<ByteVar>().readBytes(size)
    }

    // The pointer first, then the size, which is the order SQLite asks for.
    override fun getBlob(index: Int) = bytes(sqlite3_column_blob(statement, index), index)
    override fun getText(index: Int) = bytes(sqlite3_column_text(statement, index), index).decodeToString()
    override fun getDouble(index: Int) = sqlite3_column_double(statement, index)
    override fun getLong(index: Int) = sqlite3_column_int64(statement, index)
    override fun getColumnCount() = sqlite3_column_count(statement)
    override fun getColumnName(index: Int) = sqlite3_column_name(statement, index)?.toKString() ?: ""
    override fun getColumnType(index: Int) = sqlite3_column_type(statement, index)

    override fun step(): Boolean = when (val code = sqlite3_step(statement)) {
        SQLITE_ROW -> true
        SQLITE_DONE -> false
        else -> throw failure(db, code)
    }

    /** Its result is the last step's, which that step has reported already: the statement is reset whatever it says. */
    override fun reset() { sqlite3_reset(statement) }

    override fun clearBindings() = check(sqlite3_clear_bindings(statement))

    override fun close() {
        if (closed) return
        closed = true
        sqlite3_finalize(statement)
    }

    private companion object {
        /** Something to point at for a value of no bytes, which SQLite takes as empty rather than as NULL. */
        val EMPTY = kotlinx.cinterop.nativeHeap.alloc<ByteVar>().ptr
    }
}

internal actual fun openDatabase(path: String, flags: Int): NativeDatabase = memScoped {
    val out = alloc<CPointerVar<sqlite3>>()
    val code = sqlite3_open_v2(path, out.ptr, flags, null)
    val db = out.value
    if (code != SQLITE_OK) {
        val error = failure(db, code)
        if (db != null) sqlite3_close_v2(db)
        throw error
    }
    WindowsDatabase(db ?: throw SqliteFailure(code, "SQLite opened no database"))
}
