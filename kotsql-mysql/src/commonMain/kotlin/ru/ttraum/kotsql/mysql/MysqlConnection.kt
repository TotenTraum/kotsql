package ru.ttraum.kotsql.mysql

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
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlErrorDetails
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlRow
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.StatementDescription
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.transport.tlsOptions
import ru.ttraum.kotsql.TransactionState
import ru.ttraum.kotsql.transport.BufferedInput
import ru.ttraum.kotsql.transport.ByteReader
import ru.ttraum.kotsql.transport.ByteWriter
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_PORT = 3306
private const val MAX_PACKET = 0xFFFFFF
private const val STATEMENT_CACHE_SIZE = 256

// Capabilities
private const val CLIENT_LONG_PASSWORD = 1
private const val CLIENT_LONG_FLAG = 4
private const val CLIENT_CONNECT_WITH_DB = 8
private const val CLIENT_PROTOCOL_41 = 0x200
private const val CLIENT_SSL = 0x800
private const val CLIENT_TRANSACTIONS = 0x2000
private const val CLIENT_SECURE_CONNECTION = 0x8000
private const val CLIENT_MULTI_STATEMENTS = 0x10000
private const val CLIENT_MULTI_RESULTS = 0x20000
private const val CLIENT_PS_MULTI_RESULTS = 0x40000
private const val CLIENT_PLUGIN_AUTH = 0x80000
private const val CLIENT_PLUGIN_AUTH_LENENC = 0x200000
private const val CLIENT_SESSION_TRACK = 0x800000
private const val CLIENT_DEPRECATE_EOF = 0x1000000

// Server status
private const val STATUS_IN_TRANSACTION = 0x1
private const val STATUS_CURSOR_EXISTS = 0x40
private const val STATUS_LAST_ROW_SENT = 0x80
private const val STATUS_MORE_RESULTS = 0x8
private const val STATUS_SESSION_STATE_CHANGED = 0x4000

// Commands
private const val COM_QUIT = 0x01
private const val COM_QUERY = 0x03
private const val COM_PING = 0x0e
private const val COM_STMT_PREPARE = 0x16
private const val COM_STMT_EXECUTE = 0x17
private const val COM_STMT_CLOSE = 0x19
private const val COM_STMT_RESET = 0x1a
private const val COM_STMT_FETCH = 0x1c

private const val UTF8MB4_GENERAL_CI = 45
private const val ER_QUERY_INTERRUPTED = 1317
private const val ER_UNSUPPORTED_PS = 1295

private const val ABANDON_CANCEL_MILLIS = 1_000L
private const val ALLOW_PUBLIC_KEY_RETRIEVAL = "allowPublicKeyRetrieval"

private class Terminator(val affected: Long, val lastInsertId: Long, val status: Int) {
    val moreResults get() = status and STATUS_MORE_RESULTS != 0
    val cursorOpen get() = status and STATUS_CURSOR_EXISTS != 0 && status and STATUS_LAST_ROW_SENT == 0
}

private class Prepared(val id: Int, val parameterCount: Int, val parameterTypes: List<SqlType>, val columns: List<ColumnInfo>)

private fun isError(p: ByteArray) = p.isNotEmpty() && p[0] == 0xFF.toByte()

/** OK or, with CLIENT_DEPRECATE_EOF, the end of rows. A row starting with 0xFE would take a whole maximum-size packet. */
private fun isTerminator(p: ByteArray) = p.isNotEmpty() && p[0] == 0xFE.toByte() && p.size < MAX_PACKET

/** MySQL's own limit on a result's columns. Anything else isn't a count, and shouldn't decide how much memory to take. */
private const val MAX_COLUMNS = 4096

private fun columnCount(p: ByteArray): Int {
    val count = readLenEnc(p, 0) { value, _ -> value }
    if (count !in 1..MAX_COLUMNS) throw SqlException("Malformed message from server", "08P01")
    return count.toInt()
}

/** The values generated for the rows of a statement, which may have affected a great many of them and needs nothing of this. */
private class ConsecutiveKeys(private val first: Long, override val size: Int) : AbstractList<Long>() {
    override fun get(index: Int): Long {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index, size $size")
        return first + index
    }
}

private fun parseOk(p: ByteArray): Terminator {
    var pos = 1
    return readLenEnc(p, pos) { affected, a ->
        readLenEnc(p, a) { lastId, b ->
            pos = b
            val status = if (pos + 1 < p.size) (p[pos].toInt() and 0xFF) or ((p[pos + 1].toInt() and 0xFF) shl 8) else 0
            Terminator(affected, lastId, status)
        }
    }
}

private fun parseError(p: ByteArray): SqlException {
    val r = ByteReader(p)
    r.int8()
    val code = r.int16LE()
    var state: String? = null
    if (r.remaining > 0 && p[r.position] == '#'.code.toByte()) {
        r.int8()
        state = r.bytes(5).decodeToString()
    }
    val text = r.rest().decodeToString()
    val kind = when (code) {
        ER_QUERY_INTERRUPTED, 3024 -> SqlErrorKind.TIMEOUT // 3024: max_execution_time exceeded
        1045, 1044, 1698 -> SqlErrorKind.AUTHENTICATION
        1142, 1143, 1227 -> SqlErrorKind.PERMISSION
        1205, 1213 -> SqlErrorKind.ROLLBACK // lock wait timeout, deadlock
        else -> SqlErrorKind.ofSqlState(state)
    }
    return SqlException(text, state, code, kind, details = errorDetails(code, text))
}

private val DUPLICATE_KEY = Regex("""for key '(?:([^']*)\.)?([^'.]+)'$""")
private val FOREIGN_KEY = Regex("""\(`([^`]+)`\.`([^`]+)`, CONSTRAINT `([^`]+)` FOREIGN KEY \(`([^`]+)`""")
private val QUOTED = Regex("""'([^']+)'""")
private val QUALIFIED_TABLE = Regex("""^Table '([^'.]+)\.([^']+)'""")

/**
 * MySQL has no fields for these, so the names come from the message. That only works in English, which is the default of
 * `lc_messages`: otherwise they stay null. The kind of violation comes from the error code, which doesn't depend on it.
 */
private fun errorDetails(code: Int, text: String): SqlErrorDetails {
    fun quoted() = QUOTED.find(text)?.groupValues?.get(1)
    return when (code) {
        1062, 1586 -> {
            val match = DUPLICATE_KEY.find(text)
            SqlErrorDetails(ConstraintViolation.UNIQUE, constraint = match?.groupValues?.get(2), table = match?.groupValues?.get(1)?.ifEmpty { null })
        }
        1451, 1452 -> {
            val match = FOREIGN_KEY.find(text)?.groupValues
            SqlErrorDetails(ConstraintViolation.FOREIGN_KEY, constraint = match?.get(3), schema = match?.get(1), table = match?.get(2), column = match?.get(4))
        }
        1048, 1364 -> SqlErrorDetails(ConstraintViolation.NOT_NULL, column = quoted())
        3819 -> SqlErrorDetails(ConstraintViolation.CHECK, constraint = quoted())
        1054, 1406, 1264, 1366 -> SqlErrorDetails(column = if (code == 1054) quoted() else Regex("""column '([^']+)'""").find(text)?.groupValues?.get(1))
        1146 -> QUALIFIED_TABLE.find(text)?.groupValues.let { SqlErrorDetails(schema = it?.get(1), table = it?.get(2)) }
        else -> SqlErrorDetails.NONE
    }
}

private fun parseColumn(p: ByteArray, codecs: CodecRegistry): MysqlColumn {
    var pos = 0
    fun skipString() {
        readLenEnc(p, pos) { length, next -> pos = next + length.toInt() }
    }
    repeat(4) { skipString() } // catalog, schema, table, original table
    var name = ""
    readLenEnc(p, pos) { length, next ->
        name = p.decodeToString(next, next + length.toInt())
        pos = next + length.toInt()
    }
    skipString() // original name
    readLenEnc(p, pos) { _, next -> pos = next } // size of the fixed part
    val r = ByteReader(p)
    r.position = pos
    val charset = r.int16LE()
    val length = r.int32LE()
    val type = r.int8()
    val flags = r.int16LE()
    val decimals = r.int8()
    return MysqlColumn(name, type, flags, charset, length, decimals, codecs)
}

internal class MysqlConnection private constructor(
    private val config: ConnectionConfig,
    private val transports: TransportFactory,
    private val transport: Transport,
    private var input: BufferedInput,
    /** False for the connection that does the stopping, or stopping it would take another one, and so on without end. */
    private val stopsAbandonedStatements: Boolean = true,
) : SqlConnection {
    private val out = ByteWriter()
    private var sequence = 0
    private var active: MysqlCursor? = null
    private var closed = false
    private var connectionId = 0
    private var status = 0
    private val statements = LinkedHashMap<String, Prepared>()
    private var statementsAreStale = false

    override val dialect: SqlDialect get() = MysqlDialect
    override val codecs: CodecRegistry get() = config.codecs
    override val isClosed get() = closed
    override lateinit var serverInfo: ServerInfo
        private set
    override val transactionState: TransactionState
        get() = if (status and STATUS_IN_TRANSACTION != 0) TransactionState.ACTIVE else TransactionState.IDLE

    override fun setReadTimeout(millis: Long) = transport.setReadTimeout(millis)
    override fun drainWarnings(): List<String> = emptyList()

    // ---- Framing ----

    private suspend inline fun <T> guarded(block: () -> T): T {
        if (closed) throw SqlException("The connection is closed", "08003")
        try {
            return block()
        } catch (e: CancellationException) {
            abandon()
            throw e
        } catch (e: SqlException) {
            if (e.kind == SqlErrorKind.CONNECTION) abort()
            throw e
        } catch (e: Exception) {
            abort()
            throw SqlException("Connection failure: ${e.message}", "08006", cause = e)
        }
    }

    /**
     * For what reads packets. One that makes no sense means the stream can't be trusted any more, whichever way that shows:
     * as a report of it, or as a parser running into something it didn't expect. Must not contain the application's code.
     */
    private suspend inline fun <T> reading(block: () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SqlException) {
            if (e.kind == SqlErrorKind.CONNECTION) abort()
            throw e
        } catch (e: Exception) {
            abort()
            throw SqlException("Malformed message from server: ${e.message}", "08P01", cause = e)
        }
    }

    /**
     * The caller has given up in the middle of a call, which leaves no way to tell where in the stream that was. Hanging up
     * doesn't stop the statement, though, as a server only notices when it next has something to send: left alone it could
     * go on for hours, and with enough of them use up all the server's connections. So it's asked to stop, within limits,
     * since the caller is waiting and that may be why it has given up.
     */
    private suspend fun abandon() {
        val wasOpen = !closed
        abort()
        if (wasOpen && connectionId != 0 && stopsAbandonedStatements) withContext(NonCancellable) { withTimeoutOrNull(ABANDON_CANCEL_MILLIS) { runCatching { cancel() } } }
    }

    private suspend fun abort() {
        closed = true
        transport.close()
    }

    /** Reads one logical packet, which spans several on the wire when it reaches the maximum size. */
    private suspend fun read(): ByteArray = guarded {
        var whole: ByteArray? = null
        while (true) {
            val length = input.readByte() or (input.readByte() shl 8) or (input.readByte() shl 16)
            sequence = input.readByte() + 1
            val part = input.readAnnounced(length)
            whole = if (whole == null) part else whole + part
            if (length < MAX_PACKET) break
        }
        whole!!
    }

    /** Sends one logical packet, split at the maximum size, as the protocol says. */
    private suspend fun sendPacket(payload: ByteArray, size: Int = payload.size) = guarded {
        var offset = 0
        while (true) {
            val part = minOf(size - offset, MAX_PACKET)
            val w = ByteWriter(4)
            w.int24LE(part)
            w.int8(sequence++)
            transport.write(w.buffer, 0, w.size)
            transport.write(payload, offset, part)
            offset += part
            if (part < MAX_PACKET) break
        }
        transport.flush()
    }

    /** Sends a command, which starts a new exchange. */
    private suspend fun command(code: Int, body: ByteWriter.() -> Unit) {
        out.clear()
        out.int8(code)
        out.body()
        sequence = 0
        sendPacket(out.buffer, out.size)
    }

    private suspend fun beginOperation() {
        if (closed) throw SqlException("The connection is closed", "08003")
        active?.detach()
        active = null
    }

    private fun onOk(t: Terminator) {
        status = t.status
        if (t.status and STATUS_SESSION_STATE_CHANGED != 0) statementsAreStale = true
    }

    /**
     * Forgets prepared statements after a change to the session, which the server reports however it came about, such as a
     * USE inside a procedure. A prepared statement stays with the database that was current when it was prepared, so reusing
     * it after a USE would quietly read from the wrong database.
     */
    private suspend fun discardStaleStatements() {
        if (!statementsAreStale) return
        statementsAreStale = false
        for (statement in statements.values) command(COM_STMT_CLOSE) { int32LE(statement.id) } // there is no response
        statements.clear()
    }

    private suspend fun fatalIfNeeded(e: SqlException): SqlException {
        // The server has ended the session, as when the connection was killed.
        if (e.sqlState == "08S01" || e.vendorCode == 1053 || e.vendorCode == 1927) abort()
        return e
    }

    // ---- Statements ----

    private suspend fun prepare(sql: String): Prepared {
        discardStaleStatements()
        statements.remove(sql)?.let {
            statements[sql] = it // the most recently used goes last
            return it
        }
        command(COM_STMT_PREPARE) { bytes(sql.encodeToByteArray()) }
        val first = read()
        if (isError(first)) throw fatalIfNeeded(parseError(first))
        val r = ByteReader(first)
        r.int8()
        val id = r.int32LE()
        val columnCount = r.int16LE()
        val parameterCount = r.int16LE()
        val parameters = List(parameterCount) { parseColumn(read(), config.codecs) }
        val columns = List(columnCount) { parseColumn(read(), config.codecs) }
        val prepared = Prepared(id, parameterCount, parameters.map { it.type }, columns)
        if (statements.size >= STATEMENT_CACHE_SIZE) {
            val oldest = statements.keys.first()
            val evicted = statements.remove(oldest)!!
            command(COM_STMT_CLOSE) { int32LE(evicted.id) } // there is no response
        }
        statements[sql] = prepared
        return prepared
    }

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions): SqlCursor {
        reading { beginOperation() }
        val prepared = try {
            reading { prepare(sql) }
        } catch (e: SqlException) {
            // Some statements have no prepared form; without parameters they can run as they are.
            if (e.vendorCode == ER_UNSUPPORTED_PS && params.isEmpty()) return startScript(sql)
            throw e
        }
        if (prepared.parameterCount != params.size) {
            throw SqlException("The statement has ${prepared.parameterCount} parameters, but ${params.size} were given", "07001")
        }
        val fetchSize = options.fetchSize
        val encoded = params.map(config.codecs::encode)
        command(COM_STMT_EXECUTE) {
            int32LE(prepared.id)
            int8(if (fetchSize > 0) 1 else 0) // CURSOR_TYPE_READ_ONLY
            int32LE(1)
            writeParameters(encoded)
        }
        return MysqlCursor(binary = true, statementId = prepared.id, fetchSize = fetchSize).also { active = it }
    }

    override suspend fun executeScript(sql: String): SqlCursor {
        reading { beginOperation() }
        return startScript(sql)
    }

    private suspend fun startScript(sql: String): SqlCursor {
        command(COM_QUERY) { bytes(sql.encodeToByteArray()) }
        return MysqlCursor(binary = false, statementId = 0, fetchSize = 0).also { active = it }
    }

    /** MySQL can't run statements in one exchange with an implicit transaction, so an explicit one provides the atomicity. */
    override suspend fun executeBatch(items: List<BatchItem>): LongArray {
        beginOperation()
        val counts = LongArray(items.size)
        var done = 0
        val ownTransaction = transactionState == TransactionState.IDLE
        suspend fun simple(sql: String) {
            val cursor = executeScript(sql)
            while (cursor.nextResult() != null) { /* no result of interest */ }
        }
        try {
            if (ownTransaction) simple("START TRANSACTION")
            for (item in items) {
                val cursor = execute(item.sql, item.params)
                while (true) when (val result = cursor.nextResult() ?: break) {
                    is SqlResult.UpdateCount -> counts[done] = result.count
                    is SqlResult.Rows -> while ((result.nextBuffered() ?: result.next()) != null) { /* skip */ }
                }
                done++
            }
            if (ownTransaction) simple("COMMIT")
        } catch (e: SqlException) {
            if (ownTransaction && !closed) runCatching { simple("ROLLBACK") }
            throw SqlBatchException(counts.copyOf(done), e)
        }
        return counts
    }

    override suspend fun describe(sql: String): StatementDescription {
        val prepared = reading {
            beginOperation()
            prepare(sql)
        }
        return StatementDescription(prepared.parameterTypes, prepared.columns)
    }

    /** A second connection, as the protocol has no out-of-band way to interrupt a statement. */
    override suspend fun cancel() {
        val killer = open(config, transports, stopsAbandonedStatements = false)
        try {
            val cursor = killer.executeScript("KILL QUERY $connectionId")
            while (cursor.nextResult() != null) { /* no result of interest */ }
        } finally {
            killer.close()
        }
    }

    override suspend fun ping(): Boolean = try {
        if (closed) false else {
            reading {
                beginOperation()
                command(COM_PING) {}
                val p = read()
                if (isError(p)) false else true.also { onOk(parseOk(p)) }
            }
        }
    } catch (e: SqlException) {
        false
    }

    override suspend fun close() {
        if (closed) return
        try {
            active?.close()
            active = null
            command(COM_QUIT) {}
        } catch (_: Exception) {
            // Closing anyway.
        } finally {
            closed = true
            transport.close()
        }
    }

    // ---- Results ----

    private enum class Phase { HEADER, COLUMNS, ROWS, PAUSED, DONE }

    /**
     * Reads one exchange's packets. [noteWire] follows the protocol for each packet taken from the socket, whoever reads it,
     * so that [detach] can tell where the exchange ends without understanding the rows.
     */
    private inner class MysqlCursor(private val binary: Boolean, private val statementId: Int, private val fetchSize: Int) : SqlCursor {
        private val pending = ArrayDeque<ByteArray>()
        private var phase = Phase.HEADER
        private var columnsLeft = 0L
        private var finished = false
        private var current: Rows? = null

        private fun noteWire(p: ByteArray) {
            when (phase) {
                Phase.HEADER -> when {
                    isError(p) -> phase = Phase.DONE
                    p[0] == 0.toByte() -> {
                        val ok = parseOk(p)
                        onOk(ok)
                        phase = if (ok.moreResults) Phase.HEADER else Phase.DONE
                    }
                    else -> {
                        columnsLeft = columnCount(p).toLong()
                        phase = Phase.COLUMNS
                    }
                }
                Phase.COLUMNS -> if (--columnsLeft == 0L) phase = Phase.ROWS
                Phase.ROWS -> when {
                    isError(p) -> phase = Phase.DONE
                    isTerminator(p) -> {
                        val t = parseOk(p)
                        onOk(t)
                        phase = when {
                            t.cursorOpen -> Phase.PAUSED
                            t.moreResults -> Phase.HEADER
                            else -> Phase.DONE
                        }
                    }
                }
                Phase.PAUSED, Phase.DONE -> error("no packet expected")
            }
            if (phase == Phase.DONE && active === this) active = null
        }

        private suspend fun nextPacket(): ByteArray {
            pending.removeFirstOrNull()?.let { return it }
            if (phase == Phase.PAUSED) {
                command(COM_STMT_FETCH) { int32LE(statementId); int32LE(fetchSize) }
                phase = Phase.ROWS
            }
            return read().also(::noteWire)
        }

        suspend fun detach() {
            while (phase != Phase.DONE) {
                if (phase == Phase.PAUSED) {
                    command(COM_STMT_FETCH) { int32LE(statementId); int32LE(Int.MAX_VALUE) }
                    phase = Phase.ROWS
                }
                pending.addLast(read().also(::noteWire))
            }
        }

        override suspend fun nextResult(): SqlResult? = reading {
            current?.let { rows -> while ((rows.nextBuffered() ?: rows.next()) != null) { /* skip the unread rows */ } }
            current = null
            if (finished) return@reading null
            val p = nextPacket()
            when {
                isError(p) -> {
                    finished = true
                    throw fatalIfNeeded(parseError(p))
                }
                p[0] == 0.toByte() -> {
                    val ok = parseOk(p)
                    if (!ok.moreResults) finished = true
                    SqlResult.UpdateCount(ok.affected, if (ok.lastInsertId != 0L) generatedKeys(ok) else emptyList())
                }
                else -> {
                    val columns = List(columnCount(p)) { parseColumn(nextPacket(), config.codecs) }
                    Rows(columns).also { current = it }
                }
            }
        }

        /** The server reports the first of the values generated, and they are consecutive unless configured otherwise. */
        private fun generatedKeys(ok: Terminator): List<Long> = ConsecutiveKeys(ok.lastInsertId, ok.affected.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())

        override suspend fun close() {
            if (finished || closed) {
                finished = true
                return
            }
            current = null
            pending.clear()
            reading {
                while (phase != Phase.DONE) {
                    if (phase == Phase.PAUSED) {
                        // Ends the server's cursor, without fetching what's left of it.
                        command(COM_STMT_RESET) { int32LE(statementId) }
                        val p = read()
                        if (isError(p)) throw parseError(p)
                        onOk(parseOk(p))
                        phase = Phase.DONE
                        if (active === this@MysqlCursor) active = null
                    } else {
                        noteWire(read())
                    }
                }
            }
            finished = true
        }

        private inner class Rows(override val columns: List<ColumnInfo>) : SqlResult.Rows {
            private var done = false
            override var updateCount: Long? = null
                private set

            private fun row(p: ByteArray): SqlRow = if (binary) parseBinaryRow(columns, p, config.codecs) else parseTextRow(columns, p, config.codecs)

            /** Reporting a broken row takes closing the connection, which suspends, so that's left to [next]. */
            private fun buffered(p: ByteArray): SqlRow? = try {
                row(p)
            } catch (e: SqlException) {
                pending.addFirst(p)
                null
            }

            override fun nextBuffered(): SqlRow? {
                if (done || finished || closed) return null
                val saved = pending.firstOrNull()
                if (saved != null) {
                    if (isError(saved) || isTerminator(saved)) return null
                    pending.removeFirst()
                    return buffered(saved)
                }
                if (phase != Phase.ROWS || input.buffered < 5) return null
                val length = input.peekByte(0) or (input.peekByte(1) shl 8) or (input.peekByte(2) shl 16)
                if (length == 0 || length >= MAX_PACKET || length > input.buffered - 4) return null
                val first = input.peekByte(4)
                if (first == 0xFF || first == 0xFE) return null
                input.skipBuffered(4)
                sequence = (sequence + 1) and 0xFF
                return buffered(input.takeBuffered(length))
            }

            override suspend fun next(): SqlRow? = reading {
                while (!done && !finished) {
                    val p = nextPacket()
                    when {
                        isError(p) -> {
                            done = true
                            finished = true
                            current = null
                            throw fatalIfNeeded(parseError(p))
                        }
                        isTerminator(p) -> {
                            val t = parseOk(p)
                            if (t.cursorOpen) continue // the next page is fetched by nextPacket
                            done = true
                            if (!t.moreResults) finished = true
                        }
                        else -> return@reading row(p)
                    }
                }
                null
            }
        }
    }

    // ---- Startup ----

    private suspend fun startup() {
        val greeting = read()
        if (isError(greeting)) throw parseError(greeting)
        val r = ByteReader(greeting)
        val protocol = r.int8()
        if (protocol != 10) throw SqlException("Unsupported protocol version $protocol", "08004")
        val version = r.cstring()
        connectionId = r.int32LE()
        val nonce1 = r.bytes(8)
        r.int8()
        var capabilities = r.int16LE()
        r.int8() // charset
        status = r.int16LE()
        capabilities = capabilities or (r.int16LE() shl 16)
        val authLength = r.int8()
        r.bytes(10)
        val nonce2 = r.bytes(maxOf(13, authLength - 8)).let { it.copyOf(it.size - 1) } // without its terminating zero
        val plugin = if (r.remaining > 0) r.cstring() else "mysql_native_password"
        val nonce = nonce1 + nonce2
        if (capabilities and CLIENT_DEPRECATE_EOF == 0) {
            throw SqlException("The server is too old for this driver: MySQL 5.7.5 or later, or MariaDB 10.2 or later, is needed", "08004")
        }

        var flags = CLIENT_LONG_PASSWORD or CLIENT_LONG_FLAG or CLIENT_PROTOCOL_41 or CLIENT_TRANSACTIONS or CLIENT_SECURE_CONNECTION or
            CLIENT_MULTI_STATEMENTS or CLIENT_MULTI_RESULTS or CLIENT_PS_MULTI_RESULTS or CLIENT_PLUGIN_AUTH or
            CLIENT_PLUGIN_AUTH_LENENC or CLIENT_DEPRECATE_EOF
        if (config.database != null) flags = flags or CLIENT_CONNECT_WITH_DB
        if (capabilities and CLIENT_SESSION_TRACK != 0) flags = flags or CLIENT_SESSION_TRACK

        var secure = false
        if (config.tlsMode != TlsMode.DISABLE) {
            if (capabilities and CLIENT_SSL != 0) {
                val w = ByteWriter(32)
                w.int32LE(flags or CLIENT_SSL); w.int32LE(MAX_PACKET); w.int8(UTF8MB4_GENERAL_CI); w.bytes(ByteArray(23))
                sendPacket(w.buffer, w.size)
                // Anything already received would be plaintext that an attacker could have injected.
                if (input.buffered > 0) throw SqlException("The server sent data before the TLS handshake", "08P01")
                transport.startTls(config.tlsOptions)
                flags = flags or CLIENT_SSL
                secure = true
            } else if (config.tlsMode != TlsMode.PREFER) {
                throw SqlException("The server does not support TLS, required by sslmode=${config.tlsMode.id}", "08004")
            }
        }

        val user = config.user ?: throw SqlException("No user specified", "28000")
        val password = config.password ?: ""
        val token = token(plugin, password, nonce)
        val w = ByteWriter(128)
        w.int32LE(flags); w.int32LE(MAX_PACKET); w.int8(UTF8MB4_GENERAL_CI); w.bytes(ByteArray(23))
        w.cstring(user)
        w.int8(token.size); w.bytes(token)
        config.database?.let { w.cstring(it) }
        w.cstring(plugin)
        sendPacket(w.buffer, w.size)
        authenticate(plugin, password, nonce, secure)

        val numbers = version.takeWhile { it.isDigit() || it == '.' }.split('.')
        serverInfo = ServerInfo(
            productName = if ("mariadb" in version.lowercase()) "MariaDB" else MysqlDialect.productName,
            version = version,
            majorVersion = numbers.getOrNull(0)?.toIntOrNull() ?: 0,
            minorVersion = numbers.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }

    private fun token(plugin: String, password: String, nonce: ByteArray): ByteArray = when (plugin) {
        "caching_sha2_password" -> cachingSha2Token(password, nonce)
        "mysql_native_password" -> nativePasswordToken(password, nonce)
        else -> throw SqlException("Unsupported authentication method '$plugin'", "28000", kind = SqlErrorKind.AUTHENTICATION)
    }

    private suspend fun authenticate(firstPlugin: String, password: String, firstNonce: ByteArray, secure: Boolean) {
        var plugin = firstPlugin
        var nonce = firstNonce
        while (true) {
            val p = read()
            when (p[0].toInt() and 0xFF) {
                0x00 -> {
                    onOk(parseOk(p))
                    return
                }
                0xFF -> throw parseError(p).let {
                    SqlException(it.message ?: "Authentication failed", it.sqlState, it.vendorCode, it.kind.takeIf { k -> k != SqlErrorKind.OTHER } ?: SqlErrorKind.AUTHENTICATION)
                }
                0xFE -> { // the server wants another method
                    val r = ByteReader(p)
                    r.int8()
                    plugin = r.cstring()
                    nonce = r.rest().let { if (it.isNotEmpty() && it.last() == 0.toByte()) it.copyOf(it.size - 1) else it }
                    sendPacket(token(plugin, password, nonce))
                }
                0x01 -> { // more data for the current method
                    when (if (p.size > 1) p[1].toInt() else 0) {
                        3 -> Unit // the server had the password's hash in its cache: OK follows
                        4 -> sendFullPassword(password, nonce, secure)
                        else -> throw SqlException("Unexpected authentication data from $plugin", "08P01")
                    }
                }
                else -> throw SqlException("Unexpected message during authentication", "08P01")
            }
        }
    }

    /**
     * `caching_sha2_password` asks for the password itself when it hasn't seen this user yet. Over TLS it can just be sent.
     * Without TLS it is encrypted with the server's public key, but a key received over an unprotected connection could be an
     * attacker's, who would then learn the password, so that takes explicit consent.
     */
    private suspend fun sendFullPassword(password: String, nonce: ByteArray, secure: Boolean) {
        if (secure) {
            sendPacket(password.encodeToByteArray() + 0)
            return
        }
        if (config.properties[ALLOW_PUBLIC_KEY_RETRIEVAL]?.toBoolean() != true) {
            throw SqlException(
                "The server needs the full password, which requires TLS, or $ALLOW_PUBLIC_KEY_RETRIEVAL=true if you accept the risk of an active attacker",
                "28000",
                kind = SqlErrorKind.AUTHENTICATION,
            )
        }
        sendPacket(byteArrayOf(2)) // request the public key
        val key = read()
        if (key[0] != 1.toByte()) throw SqlException("Unexpected reply to the request for the public key", "08P01")
        sendPacket(encryptPassword(password, nonce, key.copyOfRange(1, key.size)))
    }

    companion object {
        suspend fun open(config: ConnectionConfig, transports: TransportFactory, stopsAbandonedStatements: Boolean = true): MysqlConnection {
            val transport = transports.connect(config.host, config.port ?: DEFAULT_PORT, config.connectTimeoutMillis)
            var opened = false
            try {
                transport.setReadTimeout(config.socketTimeoutMillis)
                val connection = MysqlConnection(config, transports, transport, BufferedInput(transport), stopsAbandonedStatements)
                connection.reading { connection.startup() }
                opened = true
                return connection
            } finally {
                if (!opened) transport.close()
            }
        }
    }
}
