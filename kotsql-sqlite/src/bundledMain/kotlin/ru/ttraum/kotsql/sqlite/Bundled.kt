package ru.ttraum.kotsql.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

// [\s\S] rather than DOT_MATCHES_ALL, which code shared by the JVM and native targets doesn't have: a message can span lines.
private val ERROR_FORMAT = Regex("""^Error code: (\d+)(?:, message: ([\s\S]*))?$""")

/** androidx.sqlite puts SQLite's code into the message: `Error code: 19, message: UNIQUE constraint failed: t.a`. */
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: SQLiteException) {
    val match = ERROR_FORMAT.find(e.message.orEmpty())
    val code = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    throw SqliteFailure(code, match?.groupValues?.get(2)?.ifEmpty { null } ?: e.message ?: "SQLite failed", e)
}

private class BundledDatabase(private val db: SQLiteConnection) : NativeDatabase {
    override fun prepare(sql: String): NativeStatement = BundledStatement(guarded { db.prepare(sql) })
    override fun inTransaction() = guarded { db.inTransaction() }
    override fun close() = guarded { db.close() }
}

private class BundledStatement(private val s: SQLiteStatement) : NativeStatement {
    override fun bindBlob(index: Int, value: ByteArray) = guarded { s.bindBlob(index, value) }
    override fun bindDouble(index: Int, value: Double) = guarded { s.bindDouble(index, value) }
    override fun bindLong(index: Int, value: Long) = guarded { s.bindLong(index, value) }
    override fun bindText(index: Int, value: String) = guarded { s.bindText(index, value) }
    override fun bindNull(index: Int) = guarded { s.bindNull(index) }
    override fun getBlob(index: Int) = guarded { s.getBlob(index) }
    override fun getDouble(index: Int) = guarded { s.getDouble(index) }
    override fun getLong(index: Int) = guarded { s.getLong(index) }
    override fun getText(index: Int) = guarded { s.getText(index) }
    override fun getColumnCount() = guarded { s.getColumnCount() }
    override fun getColumnName(index: Int) = guarded { s.getColumnName(index) }
    override fun getColumnType(index: Int) = guarded { s.getColumnType(index) }
    override fun step() = guarded { s.step() }
    override fun reset() = guarded { s.reset() }
    override fun clearBindings() = guarded { s.clearBindings() }
    override fun close() = guarded { s.close() }
}

internal actual fun openDatabase(path: String, flags: Int): NativeDatabase = BundledDatabase(guarded { BundledSQLiteDriver().open(path, flags) })
