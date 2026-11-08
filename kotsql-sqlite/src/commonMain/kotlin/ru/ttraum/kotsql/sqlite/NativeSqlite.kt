package ru.ttraum.kotsql.sqlite

/*
 * The part of SQLite that the driver uses, as androidx.sqlite has it, which builds it for the JVM, Linux and macOS. On Windows,
 * which it has no build for, the driver calls the C API of its own build of the same version.
 */

// SQLite's fundamental datatypes, as sqlite3_column_type gives them.
internal const val SQLITE_DATA_INTEGER = 1
internal const val SQLITE_DATA_FLOAT = 2
internal const val SQLITE_DATA_TEXT = 3
internal const val SQLITE_DATA_BLOB = 4
internal const val SQLITE_DATA_NULL = 5

// sqlite3_open_v2's flags.
internal const val OPEN_READONLY = 0x1
internal const val OPEN_READWRITE = 0x2
internal const val OPEN_CREATE = 0x4

/** A failure with SQLite's extended result code, and its message without the code. */
internal class SqliteFailure(val code: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

internal interface NativeDatabase {
    fun prepare(sql: String): NativeStatement
    fun inTransaction(): Boolean
    fun close()
}

/** Parameters and columns from 0, as androidx.sqlite numbers columns; parameters from 1, as SQLite does. */
internal interface NativeStatement {
    fun bindBlob(index: Int, value: ByteArray)
    fun bindDouble(index: Int, value: Double)
    fun bindLong(index: Int, value: Long)
    fun bindText(index: Int, value: String)
    fun bindNull(index: Int)
    fun getBlob(index: Int): ByteArray
    fun getDouble(index: Int): Double
    fun getLong(index: Int): Long
    fun getText(index: Int): String
    fun getColumnCount(): Int
    fun getColumnName(index: Int): String
    fun getColumnType(index: Int): Int
    fun step(): Boolean
    fun reset()
    fun clearBindings()
    fun close()
}

/** Opens [path] with [flags] of [OPEN_READONLY], [OPEN_READWRITE] and [OPEN_CREATE], or throws [SqliteFailure]. */
internal expect fun openDatabase(path: String, flags: Int): NativeDatabase
