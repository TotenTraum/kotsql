package ru.ttraum.kotsql.sqlite

import ru.ttraum.kotsql.BatchItem
import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ColumnInfo
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.ConstraintViolation
import ru.ttraum.kotsql.ExecuteOptions
import ru.ttraum.kotsql.ServerInfo
import ru.ttraum.kotsql.SqlBatchException
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlCursor
import ru.ttraum.kotsql.SqlDecimal
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlErrorDetails
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlOffsetDateTime
import ru.ttraum.kotsql.SqlOffsetTime
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.StatementDescription
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.TypedNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val DEFAULT_BUSY_TIMEOUT = "5000"
private const val MODE = "mode"
private const val STATEMENT_CACHE = "statement_cache_size"
private const val DEFAULT_STATEMENT_CACHE = 64
private val PRAGMA_NAME = Regex("""[A-Za-z_]+""")
private val PRAGMA_VALUE = Regex("""[\w.-]+""")

// SQLite's result codes.
private const val SQLITE_ERROR = 1
private const val SQLITE_ABORT = 4
private const val SQLITE_BUSY = 5
private const val SQLITE_LOCKED = 6
private const val SQLITE_READONLY = 8
private const val SQLITE_INTERRUPT = 9
private const val SQLITE_CORRUPT = 11
private const val SQLITE_FULL = 13
private const val SQLITE_CANTOPEN = 14
private const val SQLITE_CONSTRAINT = 19
private const val SQLITE_TOOBIG = 18
private const val SQLITE_MISMATCH = 20
private const val SQLITE_RANGE = 25
private const val SQLITE_NOTADB = 26

private fun translate(e: SqliteFailure): SqlException {
    val code = e.code
    val text = e.message ?: "SQLite failed"
    fun failure(state: String, kind: SqlErrorKind = SqlErrorKind.ofSqlState(state), details: SqlErrorDetails = SqlErrorDetails.NONE) =
        SqlException(text, state, code, kind, e, details)
    // An extended code has the primary one in its low byte. The whole of it goes to the vendor code, for those who look.
    return when (code and 0xFF) {
        SQLITE_CONSTRAINT -> {
            val on = text.substringAfter("failed: ", "").ifEmpty { null }
            when {
                text.startsWith("UNIQUE") -> failure("23505", details = SqlErrorDetails(ConstraintViolation.UNIQUE, table = on?.substringBefore('.'), column = on?.substringAfter('.')))
                text.startsWith("NOT NULL") -> failure("23502", details = SqlErrorDetails(ConstraintViolation.NOT_NULL, table = on?.substringBefore('.'), column = on?.substringAfter('.')))
                text.startsWith("FOREIGN KEY") -> failure("23503", details = SqlErrorDetails(ConstraintViolation.FOREIGN_KEY))
                text.startsWith("CHECK") -> failure("23514", details = SqlErrorDetails(ConstraintViolation.CHECK, constraint = on))
                else -> failure("23000")
            }
        }
        SQLITE_ERROR -> when {
            text.startsWith("no such table") -> failure("42P01", details = SqlErrorDetails(table = text.substringAfterLast(": ")))
            text.startsWith("no such column") -> failure("42703", details = SqlErrorDetails(column = text.substringAfterLast(": ")))
            "syntax error" in text -> failure("42601")
            else -> failure("42000")
        }
        SQLITE_ABORT -> failure("40000")
        // Not the kind that a retry of the transaction is known to help, but another connection holding the database for too long.
        SQLITE_BUSY, SQLITE_LOCKED -> failure("55P03", SqlErrorKind.TIMEOUT)
        SQLITE_READONLY -> failure("25006")
        SQLITE_INTERRUPT -> failure("57014")
        SQLITE_CORRUPT, SQLITE_NOTADB -> failure("XX001", SqlErrorKind.OTHER)
        SQLITE_FULL -> failure("53100", SqlErrorKind.OTHER)
        SQLITE_CANTOPEN -> failure("08001")
        SQLITE_TOOBIG -> failure("22001")
        SQLITE_MISMATCH -> failure("22023")
        SQLITE_RANGE -> failure("07009", SqlErrorKind.OTHER)
        else -> failure("HY000", SqlErrorKind.OTHER)
    }
}

private inline fun <T> sqlite(block: () -> T): T = try {
    block()
} catch (e: SqliteFailure) {
    throw translate(e)
}

private fun nativeType(storageClass: Int) = when (storageClass) {
    SQLITE_DATA_INTEGER -> "INTEGER"
    SQLITE_DATA_FLOAT -> "REAL"
    SQLITE_DATA_TEXT -> "TEXT"
    SQLITE_DATA_BLOB -> "BLOB"
    else -> "NULL"
}

/** An integer is INTEGER when the first row's fits in 32 bits, for JDBC and R2DBC to give an Integer as other SQLite drivers do. */
private fun sqlType(storageClass: Int, first: Any?) = when (storageClass) {
    SQLITE_DATA_INTEGER -> if (first is Long && first in Int.MIN_VALUE..Int.MAX_VALUE) SqlType.INTEGER else SqlType.BIGINT
    SQLITE_DATA_FLOAT -> SqlType.DOUBLE
    SQLITE_DATA_TEXT -> SqlType.TEXT
    SQLITE_DATA_BLOB -> SqlType.BINARY
    else -> SqlType.OTHER
}

/**
 * Values are what the database holds them as: [Long], [Double], [String], [ByteArray] or null. A value's type in SQLite is its
 * own rather than its column's, and [ColumnInfo.type] says what the first row has there, as the driver can't see a declaration.
 */
private class SqliteRow(override val columns: List<ColumnInfo>, private val values: Array<Any?>, override val codecs: CodecRegistry) : SqlRow {
    override fun get(index: Int): Any? {
        if (index !in values.indices) throw IndexOutOfBoundsException("Column index $index, row has ${values.size} columns")
        return values[index]
    }

    override fun getText(index: Int): String? = when (val value = get(index)) {
        null -> null
        is ByteArray -> value.decodeToString()
        else -> value.toString()
    }

    override fun longValue(index: Int): Long = when (val value = get(index)) {
        null -> 0
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: throw SqlException("Cannot convert '$value' to an integer", "22018")
        else -> throw SqlException("Cannot convert a BLOB to an integer", "22018")
    }

    override fun booleanValue(index: Int): Boolean = longValue(index) != 0L
}

private fun readRow(statement: NativeStatement, columns: List<ColumnInfo>, codecs: CodecRegistry) =
    SqliteRow(columns, readValues(statement, columns.size), codecs)

private fun readValues(statement: NativeStatement, count: Int): Array<Any?> {
    val values = arrayOfNulls<Any?>(count)
    for (i in values.indices) {
        values[i] = when (statement.getColumnType(i)) {
            SQLITE_DATA_INTEGER -> statement.getLong(i)
            SQLITE_DATA_FLOAT -> statement.getDouble(i)
            SQLITE_DATA_TEXT -> statement.getText(i)
            SQLITE_DATA_BLOB -> statement.getBlob(i)
            SQLITE_DATA_NULL -> null
            else -> statement.getText(i)
        }
    }
    return values
}

/** The keys of the rows of one INSERT: SQLite gives the last one, and they are consecutive for the rows of a statement. */
private class InsertedKeys(private val last: Long, override val size: Int) : AbstractList<Long>() {
    override fun get(index: Int): Long {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index, size $size")
        return last - size + 1 + index
    }
}

/**
 * HH:MM:SS with the fraction if there is one. The text has to be the same for the same time, which `LocalTime.toString` without
 * the seconds of 12:00 isn't, for comparisons and SQLite's date functions.
 */
private fun timeText(time: LocalTime): String {
    fun two(n: Int) = n.toString().padStart(2, '0')
    val text = "${two(time.hour)}:${two(time.minute)}:${two(time.second)}"
    if (time.nanosecond == 0) return text
    return text + "." + time.nanosecond.toString().padStart(9, '0').trimEnd('0')
}

private fun bind(statement: NativeStatement, position: Int, value: Any?) {
    val i = position + 1
    when (value) {
        null, is TypedNull -> statement.bindNull(i)
        is Boolean -> statement.bindLong(i, if (value) 1 else 0)
        is Byte -> statement.bindLong(i, value.toLong())
        is Short -> statement.bindLong(i, value.toLong())
        is Int -> statement.bindLong(i, value.toLong())
        is Long -> statement.bindLong(i, value)
        is Float -> statement.bindDouble(i, value.toDouble())
        is Double -> statement.bindDouble(i, value)
        is String -> statement.bindText(i, value)
        is ByteArray -> statement.bindBlob(i, value)
        // Everything else as the text that SQLite's own date functions and NUMERIC columns understand.
        is SqlDecimal -> statement.bindText(i, value.text)
        is LocalDate -> statement.bindText(i, value.toString())
        is LocalTime -> statement.bindText(i, timeText(value))
        is LocalDateTime -> statement.bindText(i, "${value.date} ${timeText(value.time)}")
        is SqlOffsetDateTime -> statement.bindText(i, "${value.dateTime.date} ${timeText(value.dateTime.time)}")
        is SqlOffsetTime -> statement.bindText(i, "${timeText(value.time)}${value.offset}")
        is Instant -> statement.bindText(i, value.toString())
        is Uuid -> statement.bindText(i, value.toString())
        else -> throw SqlException("Unsupported parameter type ${value::class.simpleName}", "22023")
    }
}

internal class SqliteConnection private constructor(
    private val config: ConnectionConfig,
    private val db: NativeDatabase,
    private val cacheSize: Int,
) : SqlConnection {
    private var closed = false

    /**
     * Prepared statements that are free, by their text, the least recently used first. One in use is out of the map, so a
     * statement that runs while another with the same text still has rows gets one of its own.
     */
    private val cache = LinkedHashMap<String, Prepared>()

    private class Prepared(val statement: NativeStatement, val parameters: Int, val verb: String)

    private fun acquire(sql: String): Prepared {
        cache.remove(sql)?.let { return it }
        return Prepared(sqlite { db.prepare(sql) }, SqliteDialect.parameterCount(sql), SqliteDialect.verb(sql))
    }

    /** Back to the cache, ready for the next use, or closed if it can't be. */
    private fun release(sql: String, prepared: Prepared) {
        val kept = !closed && cacheSize > 0 && runCatching {
            prepared.statement.reset()
            prepared.statement.clearBindings()
        }.isSuccess
        if (!kept) {
            prepared.statement.close()
            return
        }
        cache.put(sql, prepared)?.statement?.close()
        if (cache.size > cacheSize) {
            val eldest = cache.keys.first()
            cache.remove(eldest)?.statement?.close()
        }
    }

    private inline fun <T> using(sql: String, block: (NativeStatement) -> T): T {
        val prepared = acquire(sql)
        try {
            return block(prepared.statement)
        } finally {
            release(sql, prepared)
        }
    }
    private var active: SqliteCursor? = null
    override val dialect: SqlDialect get() = SqliteDialect
    override val codecs: CodecRegistry get() = config.codecs
    override val isClosed get() = closed
    override lateinit var serverInfo: ServerInfo
        private set
    override val transactionState: TransactionState
        get() = if (!closed && sqlite { db.inTransaction() }) TransactionState.ACTIVE else TransactionState.IDLE

    private fun checkOpen() { if (closed) throw SqlException("The connection is closed", "08003") }

    /** Whatever is unread of the last execution goes to memory, as a statement may need the database to itself. */
    private fun beginOperation() {
        checkOpen()
        active?.detach()
        active = null
    }

    private fun checkedStatement(sql: String, params: List<Any?>): Prepared {
        val prepared = acquire(sql)
        try {
            bindAll(prepared, params)
        } catch (e: Throwable) {
            release(sql, prepared)
            throw e
        }
        return prepared
    }

    private fun bindAll(prepared: Prepared, params: List<Any?>) {
        if (prepared.parameters != params.size) throw SqlException("The statement has ${prepared.parameters} parameters, but ${params.size} were given", "07001")
        sqlite { params.forEachIndexed { i, value -> bind(prepared.statement, i, value) } }
    }

    private fun scalar(sql: String): Long = using(sql) { statement ->
        sqlite { statement.step() }
        statement.getLong(0)
    }

    private fun changedRows(verb: String) = if (verb == "INSERT" || verb == "UPDATE" || verb == "DELETE" || verb == "REPLACE" || verb == "WITH") scalar("SELECT changes()") else 0

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions): SqlCursor {
        beginOperation()
        val statements = SqliteDialect.splitStatements(sql)
        if (statements.size > 1 && params.isNotEmpty()) throw SqlException("Parameters take a single statement", "0A000")
        return SqliteCursor(statements, params.map(config.codecs::encode)).also { active = it }
    }

    override suspend fun executeScript(sql: String): SqlCursor = execute(sql, emptyList())

    override suspend fun executeBatch(items: List<BatchItem>): LongArray {
        beginOperation()
        val counts = LongArray(items.size)
        var done = 0
        val ownTransaction = transactionState == TransactionState.IDLE
        fun simple(sql: String) = using(sql) { sqlite { it.step() } }
        var prepared: Prepared? = null
        var preparedSql: String? = null
        try {
            if (ownTransaction) simple("BEGIN")
            for (item in items) {
                val encoded = item.params.map(config.codecs::encode)
                // The usual batch is one statement many times, which keeps it rather than going to the cache for each.
                if (preparedSql != item.sql) {
                    prepared?.let { release(preparedSql!!, it) }
                    prepared = null
                    prepared = acquire(item.sql)
                    preparedSql = item.sql
                }
                val current = prepared!!
                bindAll(current, encoded)
                sqlite {
                    while (current.statement.step()) { /* the rows of RETURNING are of no interest */ }
                    current.statement.reset()
                }
                counts[done++] = changedRows(current.verb)
            }
            if (ownTransaction) simple("COMMIT")
        } catch (e: SqlException) {
            // The failed statement is reset before the rollback.
            prepared?.let { release(preparedSql!!, it) }
            prepared = null
            if (ownTransaction && !closed) runCatching { simple("ROLLBACK") }
            throw SqlBatchException(counts.copyOf(done), e)
        } finally {
            prepared?.let { release(preparedSql!!, it) }
        }
        return counts
    }

    override suspend fun describe(sql: String): StatementDescription {
        beginOperation()
        return using(sql) { statement ->
            val columns = List(statement.getColumnCount()) { i ->
                ColumnInfo(statement.getColumnName(i), SqlType.OTHER, "", codec = null)
            }
            StatementDescription(List(SqliteDialect.parameterCount(sql)) { SqlType.OTHER }, columns)
        }
    }

    /** This API of SQLite has no way to interrupt a statement, which runs on the caller's thread anyway. */
    override suspend fun cancel() {}

    override suspend fun ping(): Boolean = !closed

    override fun setReadTimeout(millis: Long) {}
    override fun drainWarnings(): List<String> = emptyList()

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            active?.release()
        } finally {
            active = null
            for (prepared in cache.values) prepared.statement.close()
            cache.clear()
            sqlite { db.close() }
        }
    }

    private inner class SqliteCursor(statements: List<String>, private val params: List<Any?>) : SqlCursor {
        private val remaining = ArrayDeque(statements)

        /** Results that have been run ahead of being asked for. A failure among them comes up in its turn. */
        private val ahead = ArrayDeque<Any>()
        private var current: StatementRows? = null
        private var finished = false

        private fun run(sql: String): SqlResult {
            val prepared = checkedStatement(sql, params)
            val verb = prepared.verb
            val statement = prepared.statement
            var keep = false
            try {
                val columnCount = statement.getColumnCount()
                if (columnCount > 0) {
                    val hasRow = sqlite { statement.step() }
                    val values = if (hasRow) sqlite { readValues(statement, columnCount) } else null
                    val columns = List(columnCount) { i ->
                        val storageClass = if (hasRow) statement.getColumnType(i) else SQLITE_DATA_NULL
                        ColumnInfo(statement.getColumnName(i), sqlType(storageClass, values?.get(i)), nativeType(storageClass))
                    }
                    val first = values?.let { SqliteRow(columns, it, config.codecs) }
                    keep = true
                    return StatementRows(sql, prepared, columns, first, verb)
                }
                sqlite { statement.step() }
                val count = changedRows(verb)
                val keys = if ((verb == "INSERT" || verb == "REPLACE") && count > 0) {
                    InsertedKeys(scalar("SELECT last_insert_rowid()"), count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    emptyList()
                }
                return SqlResult.UpdateCount(count, keys)
            } finally {
                if (!keep) release(sql, prepared)
            }
        }

        private fun nextFromAhead(): SqlResult? {
            val item = ahead.removeFirstOrNull() ?: return null
            if (item is SqlException) {
                ahead.clear()
                remaining.clear()
                throw item
            }
            return item as SqlResult
        }

        override suspend fun nextResult(): SqlResult? {
            current?.drain()
            current = null
            nextFromAhead()?.let { return it }
            checkOpen()
            val sql = remaining.removeFirstOrNull()
            if (sql == null) {
                finished = true
                if (active === this) active = null
                return null
            }
            val result = try {
                run(sql)
            } catch (e: SqlException) {
                remaining.clear() // as a server would stop at the first statement of a script to fail
                throw e
            }
            if (result is StatementRows) current = result
            return result
        }

        /** Runs what's left, since a statement of a script is supposed to happen whether its result gets read or not. */
        fun detach() {
            current?.load()
            current = null
            while (true) {
                val sql = remaining.removeFirstOrNull() ?: break
                try {
                    val result = run(sql)
                    (result as? StatementRows)?.load()
                    ahead.addLast(result)
                } catch (e: SqlException) {
                    ahead.addLast(e)
                    remaining.clear()
                }
            }
        }

        override suspend fun close() {
            if (finished) return
            finished = true
            current?.drain()
            current = null
            if (!closed) {
                ahead.clear()
                while (true) {
                    val sql = remaining.removeFirstOrNull() ?: break
                    val result = try {
                        run(sql)
                    } catch (e: SqlException) {
                        remaining.clear()
                        if (active === this) active = null
                        throw e
                    }
                    (result as? StatementRows)?.drain()
                }
            }
            if (active === this) active = null
        }

        /** With the connection closing: nothing more runs, but the statements have to go first. */
        fun release() {
            current?.release()
            current = null
            remaining.clear()
        }
    }

    private inner class StatementRows(
        private val sql: String,
        private var prepared: Prepared?,
        override val columns: List<ColumnInfo>,
        private var first: SqliteRow?,
        private val verb: String,
    ) : SqlResult.Rows {
        private var loaded: ArrayDeque<SqlRow>? = null
        override var updateCount: Long? = null
            private set

        private fun finish() {
            prepared?.let { release(sql, it) }
            prepared = null
            updateCount = if (verb == "SELECT") null else changedRows(verb).takeIf { verb != "PRAGMA" && verb != "EXPLAIN" && verb != "VALUES" }
        }

        private fun take(): SqlRow? {
            loaded?.let { return it.removeFirstOrNull() }
            first?.let {
                first = null
                return it
            }
            val open = prepared?.statement ?: return null
            if (closed) throw SqlException("The connection is closed", "08003")
            if (sqlite { open.step() }) return sqlite { readRow(open, columns, config.codecs) }
            finish()
            return null
        }

        override suspend fun next(): SqlRow? = take()
        override fun nextBuffered(): SqlRow? = take()

        fun load() {
            if (loaded != null) return
            val rows = ArrayDeque<SqlRow>()
            while (true) rows.addLast(take() ?: break)
            loaded = rows
        }

        fun drain() {
            while (take() != null) { /* unread rows */ }
        }

        fun release() {
            prepared?.statement?.close()
            prepared = null
        }
    }

    companion object {
        fun open(config: ConnectionConfig): SqliteConnection {
            val path = config.database?.ifEmpty { null }
                ?: throw SqlException("SQLite takes a path, as in sqlite:///file.db, sqlite:////absolute/file.db or sqlite:///:memory:", "08001")
            if (config.host != "localhost") throw SqlException("SQLite has no server: '${config.host}' can't be reached, only a local file", "08001")
            val flags = when (val mode = config.properties[MODE] ?: "rwc") {
                "ro" -> OPEN_READONLY
                "rw" -> OPEN_READWRITE
                "rwc" -> OPEN_READWRITE or OPEN_CREATE
                else -> throw SqlException("Invalid value of $MODE: '$mode'; expected ro, rw or rwc", "08001")
            }
            val pragmas = linkedMapOf("busy_timeout" to DEFAULT_BUSY_TIMEOUT)
            for ((name, value) in config.properties) {
                if (name == MODE || name == STATEMENT_CACHE) continue
                // These go into the text of a statement.
                if (!PRAGMA_NAME.matches(name) || !PRAGMA_VALUE.matches(value)) throw SqlException("Invalid setting $name=$value", "08001")
                pragmas[name] = value
            }
            val cacheSize = config.properties[STATEMENT_CACHE]?.let { text ->
                text.toIntOrNull()?.takeIf { it >= 0 } ?: throw SqlException("Invalid value of $STATEMENT_CACHE: '$text'; expected a count, 0 for none", "08001")
            } ?: DEFAULT_STATEMENT_CACHE
            val db = sqlite { openDatabase(path, flags) }
            try {
                val connection = SqliteConnection(config, db, cacheSize)
                for ((name, value) in pragmas) {
                    val statement = sqlite { db.prepare("PRAGMA $name = $value") }
                    try { sqlite { while (statement.step()) { /* some of them report the new value */ } } } finally { statement.close() }
                }
                val version = db.prepare("SELECT sqlite_version()").let { statement ->
                    try {
                        sqlite { statement.step() }
                        statement.getText(0)
                    } finally {
                        statement.close()
                    }
                }
                val numbers = version.split('.')
                connection.serverInfo = ServerInfo("SQLite", version, numbers.getOrNull(0)?.toIntOrNull() ?: 0, numbers.getOrNull(1)?.toIntOrNull() ?: 0)
                return connection
            } catch (e: Throwable) {
                db.close()
                throw e
            }
        }
    }
}
