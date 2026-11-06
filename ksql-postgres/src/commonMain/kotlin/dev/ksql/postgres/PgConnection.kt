package dev.ksql.postgres

import dev.ksql.BatchItem
import dev.ksql.CodecRegistry
import dev.ksql.ColumnInfo
import dev.ksql.ConnectionConfig
import dev.ksql.ConstraintViolation
import dev.ksql.ExecuteOptions
import dev.ksql.ServerInfo
import dev.ksql.SqlBatchException
import dev.ksql.SqlConnection
import dev.ksql.SqlCursor
import dev.ksql.SqlDialect
import dev.ksql.SqlErrorDetails
import dev.ksql.SqlErrorKind
import dev.ksql.SqlException
import dev.ksql.SqlResult
import dev.ksql.SqlRow
import dev.ksql.StatementDescription
import dev.ksql.TlsMode
import dev.ksql.TlsOptions
import dev.ksql.TransactionState
import dev.ksql.spi.BufferedInput
import dev.ksql.spi.ByteReader
import dev.ksql.spi.ByteWriter
import dev.ksql.spi.Transport
import dev.ksql.spi.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

private const val PROTOCOL_3_0 = 196608
private const val SSL_REQUEST = 80877103
private const val CANCEL_REQUEST = 80877102
private const val DEFAULT_PORT = 5432
private const val MAX_PARAMS = 65535

/** Executions of the same statement on a connection before it is prepared on the server. */
private const val DEFAULT_PREPARE_THRESHOLD = 5
private const val STATEMENT_CACHE_SIZE = 256
private const val MAX_CACHED_SQL_LENGTH = 64 * 1024

/** The server's complaints about a prepared statement that a fresh parse would cure. */
private const val STALE_PLAN = "0A000" // "cached plan must not change result type", after a change to a table
private const val STATEMENT_GONE = "26000" // after DEALLOCATE or DISCARD ALL

/** Driver settings in the connection's properties. Any other property is a setting for the server. */
private const val PREPARE_THRESHOLD = "prepareThreshold"

/** Bytes per CopyData message when making them from rows. */
private const val COPY_CHUNK = 64 * 1024

/** Statements sent per round trip in a batch. Keeps both sides from blocking on full socket buffers. */
private const val BATCH_CHUNK = 128

private class Message(val type: Char, val body: ByteArray) {
    fun reader() = ByteReader(body)
}

private class StatementKey(val sql: String, val oids: IntArray) {
    private val hash = 31 * sql.hashCode() + oids.contentHashCode()
    override fun hashCode() = hash
    override fun equals(other: Any?) = other is StatementKey && hash == other.hash && sql == other.sql && oids.contentEquals(other.oids)
}

private class CachedStatement(val key: StatementKey) {
    var uses = 0

    /** Its name on the server; null until the server has confirmed parsing it. */
    var name: String? = null
}

/** What it takes to send an execution again. */
private class Execution(val sql: String, val oids: IntArray, val params: List<EncodedParam>, val fetchSize: Int, val statement: CachedStatement?)

internal class PgConnection private constructor(
    private val config: ConnectionConfig,
    private val transports: TransportFactory,
    private val transport: Transport,
    private var input: BufferedInput,
) : PostgresConnection {
    private val notificationQueue = ArrayDeque<PgNotification>(0)
    private val out = ByteWriter()
    private val warnings = ArrayList<String>()
    private val parameters = HashMap<String, String>()
    private var backendPid = 0
    private var backendSecret = ByteArray(0)
    private var active: PgCursor? = null
    private var closed = false
    private val prepareThreshold = config.properties[PREPARE_THRESHOLD]?.let {
        it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw SqlException("Invalid value of $PREPARE_THRESHOLD: '$it'", "08001")
    } ?: DEFAULT_PREPARE_THRESHOLD

    /** In order of last use. */
    private val statements = LinkedHashMap<StatementKey, CachedStatement>()
    private val statementsToClose = ArrayList<String>()
    private var statementCounter = 0

    /** The name in the Parse most recently sent, which takes effect when the server confirms it. */
    private var pendingName: String? = null

    /** What `pg_type` said of the types seen so far that aren't built in. */
    private val catalogTypes = HashMap<Int, CatalogType>()

    override val dialect: SqlDialect get() = PgDialect
    override val codecs: CodecRegistry get() = config.codecs
    override var transactionState = TransactionState.IDLE
        private set
    override val isClosed get() = closed
    override lateinit var serverInfo: ServerInfo
        private set

    override fun setReadTimeout(millis: Long) = transport.setReadTimeout(millis)

    override fun drainWarnings(): List<String> = warnings.toList().also { warnings.clear() }

    // ---- Framing ----

    /** Appends a message, filling in the length prefix once [body] has written the content. */
    private inline fun message(type: Char, body: ByteWriter.() -> Unit) {
        out.int8(type.code)
        lengthPrefixed(body)
    }

    private inline fun lengthPrefixed(body: ByteWriter.() -> Unit) {
        val lengthAt = out.size
        out.int32(0)
        out.body()
        out.patchInt32(lengthAt, out.size - lengthAt)
    }

    private suspend fun send() = guarded {
        transport.write(out.buffer, 0, out.size)
        transport.flush()
        out.clear()
    }

    /** Reads the next message, handling the asynchronous ones that may arrive at any point. */
    private suspend fun read(): Message = guarded { readMessage(stopAtNotification = false)!! }

    /** Reads up to the next message for the caller. With [stopAtNotification], returns null after one has been queued. */
    private suspend fun readMessage(stopAtNotification: Boolean): Message? {
        while (true) {
            val type = input.readByte().toChar()
            val length = input.readInt32()
            if (length < 4) throw SqlException("Malformed message from server", "08P01")
            val body = ByteArray(length - 4)
            input.readFully(body)
            when (type) {
                'N' -> warnings += ServerError.parse(ByteReader(body)).toString()
                'S' -> ByteReader(body).let { parameters[it.cstring()] = it.cstring() }
                'A' -> {
                    val r = ByteReader(body)
                    val senderPid = r.int32()
                    if (notificationQueue.size == PostgresConnection.MAX_BUFFERED_NOTIFICATIONS) notificationQueue.removeFirst()
                    notificationQueue.addLast(PgNotification(r.cstring(), r.cstring(), senderPid))
                    if (stopAtNotification) return null
                }
                else -> return Message(type, body)
            }
        }
    }

    // ---- LISTEN and NOTIFY ----

    override fun drainNotifications(): List<PgNotification> = notificationQueue.toList().also { notificationQueue.clear() }

    override suspend fun notify(channel: String, payload: String) {
        val cursor = execute("SELECT pg_notify($1, $2)", listOf(channel, payload))
        while (true) {
            val result = cursor.nextResult() ?: break
            if (result is SqlResult.Rows) while (result.next() != null) { /* nothing of interest */ }
        }
    }

    override fun notifications(vararg channels: String): Flow<PgNotification> = flow {
        listenAndDeliver(channels.toList(), {}) { emit(it) }
    }

    private suspend fun simple(sql: String) {
        val cursor = executeScript(sql)
        while (cursor.nextResult() != null) { /* nothing of interest */ }
    }

    internal suspend fun listenAndDeliver(channels: List<String>, onListening: suspend () -> Unit, deliver: suspend (PgNotification) -> Unit) {
        beginOperation()
        if (transactionState != TransactionState.IDLE) throw SqlException("Listening takes a connection outside of a transaction", "25001")
        for (channel in channels) simple("LISTEN ${PgDialect.quoteIdentifier(channel)}")
        // Whichever way this ends, a connection still in order stops receiving what nobody is going to read.
        var inOrder = true
        try {
            onListening()
            setReadTimeout(0) // the wait may be as long as it likes
            while (true) {
                while (true) deliver(notificationQueue.removeFirstOrNull() ?: break)
                val unexpected = guarded { readMessage(stopAtNotification = true) } ?: continue
                inOrder = false
                throw if (unexpected.type == 'E') serverError(unexpected) else unexpected(unexpected)
            }
        } finally {
            if (inOrder && !closed) withContext(NonCancellable) { runCatching { simple("UNLISTEN *") } }
        }
    }

    // ---- COPY ----

    /** Reads up to where the server asks for data or provides it, or says what went wrong. */
    private suspend fun readCopyStart(expected: Char, sql: String) {
        while (true) {
            val m = read()
            when (m.type) {
                expected -> return
                'E' -> {
                    val error = serverError(m)
                    if (!closed) readUntilReady()
                    throw error
                }
                'G', 'H', 'W' -> throw SqlException("The statement is not the kind of COPY that this expects: $sql", "42601").also { abort() }
                'Z' -> {
                    onReady(m)
                    throw SqlException("The statement is not a COPY: $sql", "42601")
                }
            }
        }
    }

    override suspend fun copyIn(sql: String, data: Flow<ByteArray>): Long {
        beginOperation()
        message('Q') { cstring(sql) }
        send()
        readCopyStart('G', sql)
        var failure: Throwable? = null
        try {
            data.collect { chunk ->
                if (chunk.isNotEmpty()) {
                    message('d') { bytes(chunk) }
                    send()
                }
            }
        } catch (e: Exception) {
            failure = e
        }
        if (closed) throw failure ?: SqlException("The connection is closed", "08003")
        if (failure != null) {
            withContext(NonCancellable) {
                message('f') { cstring("The source of the data failed: ${failure.message}") }
                send()
                readUntilReady() // holds the server's report of the failure, which says less than this one
            }
            throw failure
        }
        message('c') {}
        send()
        var count = 0L
        var error: SqlException? = null
        while (true) {
            val m = read()
            when (m.type) {
                'C' -> count = commandCount(m)
                'E' -> if (error == null) error = serverError(m)
                'Z' -> {
                    onReady(m)
                    error?.let { throw it }
                    return count
                }
            }
        }
    }

    override fun copyOut(sql: String): Flow<ByteArray> = flow {
        beginOperation()
        message('Q') { cstring(sql) }
        send()
        readCopyStart('H', sql)
        var finished = false
        try {
            while (true) {
                val m = read()
                when (m.type) {
                    'd' -> emit(m.body)
                    'c', 'C' -> Unit
                    'E' -> {
                        finished = true
                        val error = serverError(m)
                        if (!closed) readUntilReady()
                        throw error
                    }
                    'Z' -> {
                        finished = true
                        onReady(m)
                        return@flow
                    }
                    else -> throw unexpected(m)
                }
            }
        } finally {
            // The collector has seen enough, and the rest may be huge: the server stops, and what it sent by then is dropped.
            if (!finished && !closed) {
                withContext(NonCancellable) {
                    runCatching { cancel() }
                    runCatching { readUntilReady() }
                }
            }
        }
    }

    override suspend fun copyInRows(table: String, columns: List<String>, rows: Flow<List<Any?>>): Long {
        val list = columns.joinToString(", ") { PgDialect.quoteIdentifier(it) }
        val sql = "COPY ${PgDialect.quoteIdentifier(table)} ($list) FROM STDIN"
        return copyIn(
            sql,
            flow {
                val chunk = ByteWriter(COPY_CHUNK + 1024)
                rows.collect { row ->
                    if (row.size != columns.size) throw SqlException("A row has ${row.size} values, for ${columns.size} columns", "22023")
                    writeCopyRow(chunk, row.map { encodeParam(config.codecs.encode(it)) })
                    if (chunk.size >= COPY_CHUNK) {
                        emit(chunk.buffer.copyOf(chunk.size))
                        chunk.clear()
                    }
                }
                if (chunk.size > 0) emit(chunk.buffer.copyOf(chunk.size))
            },
        )
    }

    override fun copyOutRows(source: String): Flow<List<String?>> = flow {
        val from = if (source.trimStart().startsWith("(")) source else PgDialect.quoteIdentifier(source)
        copyOut("COPY $from TO STDOUT").collect { emit(parseCopyRow(it)) }
    }

    /** Any I/O failure leaves the stream in an unknown state, so the connection is closed for good. */
    private suspend inline fun <T> guarded(block: () -> T): T {
        if (closed) throw SqlException("The connection is closed", "08003")
        try {
            return block()
        } catch (e: CancellationException) {
            abort()
            throw e
        } catch (e: SqlException) {
            if (e.kind == SqlErrorKind.CONNECTION) abort()
            throw e
        } catch (e: Exception) {
            abort()
            throw SqlException("Connection failure: ${e.message}", "08006", cause = e)
        }
    }

    private suspend fun abort() {
        closed = true
        transport.close()
    }

    // ---- Extended protocol pieces ----

    private fun writeParse(sql: String, oids: IntArray, name: String = "") = message('P') {
        cstring(name)
        cstring(sql)
        int16(oids.size)
        oids.forEach(::int32)
    }

    private fun writeBind(params: List<EncodedParam>, statement: String = "") = message('B') {
        cstring("")
        cstring(statement)
        int16(0) // all parameters in text format
        int16(params.size)
        for (p in params) {
            if (p.text == null) int32(-1) else { int32(p.text.size); bytes(p.text) }
        }
        int16(0) // all results in text format
    }

    private fun writeDescribe(kind: Char) = message('D') { int8(kind.code); cstring("") }
    private fun writeExecute(maxRows: Int) = message('E') { cstring(""); int32(maxRows) }
    private fun writeSync() = message('S') {}
    private fun writeFlush() = message('H') {}
    private fun writeClosePortal() = message('C') { int8('P'.code); cstring("") }
    private fun writeCloseStatement(name: String) = message('C') { int8('S'.code); cstring(name) }

    // ---- Statement cache ----

    /** Counts a use of a statement, and makes it the most recently used. */
    private fun cachedStatement(sql: String, oids: IntArray): CachedStatement? {
        if (prepareThreshold == 0 || sql.length > MAX_CACHED_SQL_LENGTH) return null
        val key = StatementKey(sql, oids)
        val statement = statements.remove(key) ?: CachedStatement(key)
        statements[key] = statement
        if (statements.size > STATEMENT_CACHE_SIZE) {
            val oldest = statements.values.first()
            statements.remove(oldest.key)
            oldest.name?.let { statementsToClose += it }
        }
        statement.uses++
        return statement
    }

    /** Sends an execution. Returns the statement that the server is parsing under a new name, if any. */
    private suspend fun sendExecution(execution: Execution, useCache: Boolean): CachedStatement? {
        // In a failed transaction these would be ignored and the names lost, so leave them for later.
        if (transactionState != TransactionState.FAILED) {
            statementsToClose.forEach(::writeCloseStatement)
            statementsToClose.clear()
        }
        val statement = execution.statement?.takeIf { useCache }
        val existing = statement?.name
        var parsing: CachedStatement? = null
        var pendingName: String? = null
        when {
            existing != null -> Unit
            statement != null && statement.uses >= prepareThreshold -> {
                pendingName = "ksql_${++statementCounter}"
                parsing = statement
                writeParse(execution.sql, execution.oids, pendingName)
            }
            else -> writeParse(execution.sql, execution.oids)
        }
        writeBind(execution.params, existing ?: pendingName ?: "")
        writeDescribe('P')
        writeExecute(execution.fetchSize)
        // Without Sync the portal survives between pages, inside or outside a transaction.
        if (execution.fetchSize > 0) writeFlush() else writeSync()
        send()
        this.pendingName = pendingName
        return parsing
    }

    /** The server doesn't have the statement as it was, so it has to be parsed again. */
    private fun invalidate(statement: CachedStatement, sqlState: String?) {
        if (sqlState == STATEMENT_GONE) {
            // Most likely DEALLOCATE ALL or DISCARD ALL: nothing else is there either.
            statements.values.forEach { it.name = null; it.uses = 0 }
            statementsToClose.clear()
        } else {
            statement.name?.let { statementsToClose += it }
            statement.name = null
            statement.uses = 0
        }
    }

    private fun encodeAll(params: List<Any?>): List<EncodedParam> {
        if (params.size > MAX_PARAMS) throw SqlException("Too many parameters: ${params.size}, the limit is $MAX_PARAMS", "54023")
        return params.map { encodeParam(config.codecs.encode(it)) }
    }

    // ---- SqlConnection ----

    /** A new operation needs the wire: whatever is left of the previous cursor moves to its own memory. */
    private suspend fun beginOperation() {
        if (closed) throw SqlException("The connection is closed", "08003")
        active?.detach()
        active = null
    }

    override suspend fun execute(sql: String, params: List<Any?>, options: ExecuteOptions): SqlCursor {
        beginOperation()
        val encoded = encodeAll(params)
        val oids = IntArray(encoded.size) { encoded[it].oid }
        val execution = Execution(sql, oids, encoded, options.fetchSize, cachedStatement(sql, oids))
        val startedIdle = transactionState == TransactionState.IDLE
        val parsing = sendExecution(execution, useCache = true)
        return PgCursor(options.fetchSize, synced = options.fetchSize == 0, execution, startedIdle, parsing).also { active = it }
    }

    override suspend fun executeScript(sql: String): SqlCursor {
        beginOperation()
        message('Q') { cstring(sql) }
        send()
        return PgCursor(fetchSize = 0, synced = true).also { active = it }
    }

    override suspend fun executeBatch(items: List<BatchItem>): LongArray {
        beginOperation()
        val counts = LongArray(items.size)
        var done = 0
        var lastSql: String? = null
        var lastOids: IntArray? = null
        try {
            for (chunk in items.chunked(BATCH_CHUNK)) {
                for (item in chunk) {
                    val encoded = encodeAll(item.params)
                    val oids = IntArray(encoded.size) { encoded[it].oid }
                    if (item.sql != lastSql || !oids.contentEquals(lastOids)) {
                        writeParse(item.sql, oids)
                        lastSql = item.sql
                        lastOids = oids
                    }
                    writeBind(encoded)
                    writeExecute(0)
                }
                writeFlush()
                send()
                var left = chunk.size
                while (left > 0) {
                    val m = read()
                    when (m.type) {
                        '1', '2', 'n', 'T', 'D' -> Unit
                        'C' -> { counts[done++] = commandCount(m); left-- }
                        'I' -> { done++; left-- }
                        'E' -> throw serverError(m)
                        else -> throw unexpected(m)
                    }
                }
            }
        } catch (e: SqlException) {
            if (!closed) { writeSync(); send(); readUntilReady() }
            throw SqlBatchException(counts.copyOf(done), e)
        }
        writeSync()
        send()
        readUntilReady()?.let { throw SqlBatchException(counts.copyOf(done), it) }
        return counts
    }

    override suspend fun describe(sql: String): StatementDescription {
        beginOperation()
        writeParse(sql, IntArray(0))
        writeDescribe('S')
        writeSync()
        send()
        var parameterTypes = emptyList<dev.ksql.SqlType>()
        var columns = emptyList<ColumnInfo>()
        var description: Message? = null
        var error: SqlException? = null
        while (true) {
            val m = read()
            when (m.type) {
                '1', 'n' -> Unit
                't' -> m.reader().let { r -> parameterTypes = List(r.int16()) { sqlTypeOf(r.int32()) } }
                'T' -> description = m
                'E' -> error = serverError(m)
                'Z' -> {
                    onReady(m)
                    error?.let { throw it }
                    description?.let { d ->
                        loadCatalogTypes(d)
                        columns = parseColumns(d)
                    }
                    return StatementDescription(parameterTypes, columns)
                }
                else -> throw unexpected(m)
            }
        }
    }

    override suspend fun cancel() {
        val pid = backendPid
        val secret = backendSecret
        val port = config.port ?: DEFAULT_PORT
        val cancelTransport = transports.connect(config.host, port, config.connectTimeoutMillis)
        try {
            if (config.tlsMode != TlsMode.DISABLE && transport.isSecure) {
                negotiateTls(cancelTransport, config)
            }
            val w = ByteWriter(16 + secret.size)
            w.int32(12 + secret.size)
            w.int32(CANCEL_REQUEST)
            w.int32(pid)
            w.bytes(secret)
            cancelTransport.write(w.buffer, 0, w.size)
            cancelTransport.flush()
            // The server closes the connection when done; waiting ensures the request has been taken.
            cancelTransport.read(ByteArray(1), 0, 1)
        } finally {
            cancelTransport.close()
        }
    }

    override suspend fun ping(): Boolean = try {
        if (closed) false else {
            val cursor = executeScript("")
            cursor.close()
            !closed
        }
    } catch (e: SqlException) {
        false
    }

    override suspend fun close() {
        if (closed) return
        try {
            active?.detach()
            active = null
            message('X') {}
            send()
        } catch (_: Exception) {
            // Closing anyway.
        } finally {
            closed = true
            transport.close()
        }
    }

    // ---- Responses ----

    private fun onReady(m: Message) {
        transactionState = when (val status = m.body[0].toInt().toChar()) {
            'I' -> TransactionState.IDLE
            'T' -> TransactionState.ACTIVE
            'E' -> TransactionState.FAILED
            else -> throw SqlException("Unknown transaction status '$status'", "08P01")
        }
    }

    private suspend fun serverError(m: Message): SqlException {
        val error = ServerError.parse(m.reader())
        val severity = error.severity
        val exception = SqlException(error.toString(), error.sqlState, details = error.details())
        // The server is about to hang up.
        if (severity == "FATAL" || severity == "PANIC") abort()
        return exception
    }

    private fun unexpected(m: Message) = SqlException("Unexpected message '${m.type}' from server", "08P01")

    /** Reads up to ReadyForQuery. Returns the first error met on the way. */
    private suspend fun readUntilReady(): SqlException? {
        var error: SqlException? = null
        while (!closed) {
            val m = read()
            when (m.type) {
                'Z' -> { onReady(m); return error }
                'E' -> { val e = serverError(m); if (error == null) error = e }
            }
        }
        return error
    }

    private fun parseColumns(m: Message): List<ColumnInfo> {
        val r = m.reader()
        return List(r.int16()) {
            val name = r.cstring()
            r.int32() // table OID
            r.int16() // attribute number
            val oid = r.int32()
            r.int16() // type size
            val modifier = r.int32()
            r.int16() // format
            PgColumn(name, oid, modifier, catalogTypes[oid], config.codecs)
        }
    }

    /** The types in a RowDescription that nothing is known of yet. */
    private fun unknownTypes(m: Message): List<Int> {
        val r = m.reader()
        var unknown: ArrayList<Int>? = null
        repeat(r.int16()) {
            r.skipCString()
            r.int32()
            r.int16()
            val oid = r.int32()
            r.int16()
            r.int32()
            r.int16()
            if (!isBuiltInType(oid) && oid !in catalogTypes) (unknown ?: ArrayList<Int>(1).also { unknown = it }).add(oid)
        }
        return unknown ?: emptyList()
    }

    /**
     * Asks the catalog about types that aren't built in, once each, the first time a result has them. That costs nothing
     * until a connection meets such a type, and sees a type created a moment ago too. The connection must be free for it.
     */
    private suspend fun loadCatalogTypes(description: Message) {
        val unknown = unknownTypes(description)
        if (unknown.isEmpty()) return
        val cursor = executeScript(
            """SELECT t.oid, t.typname, CASE WHEN t.typcategory = 'A' THEN e.typname END
               FROM pg_catalog.pg_type t LEFT JOIN pg_catalog.pg_type e ON e.oid = t.typelem
               WHERE t.oid IN (${unknown.joinToString(",")})""",
        )
        val rows = cursor.nextResult() as SqlResult.Rows
        while (true) {
            val row = rows.nextBuffered() ?: rows.next() ?: break
            catalogTypes[row.getText(0)!!.toInt()] = CatalogType(row.getText(1)!!, row.getText(2))
        }
        cursor.close()
    }

    /** The row count in a command tag such as `INSERT 0 5` or `UPDATE 3`. Zero for `CREATE TABLE` and the like. */
    private fun commandCount(m: Message): Long =
        m.reader().cstring().substringAfterLast(' ').toLongOrNull() ?: 0

    /**
     * Reads one execution's messages. Normally they come straight from the socket, on demand; [detach] moves them to
     * [pending] when the connection is needed for something else.
     */
    private inner class PgCursor(
        private val fetchSize: Int,
        private var synced: Boolean,
        private val execution: Execution? = null,
        private val startedIdle: Boolean = false,
        private var parsing: CachedStatement? = null,
    ) : SqlCursor {
        private val pending = ArrayDeque<Message>()
        private var wireDone = false // ReadyForQuery has been read from the socket
        private var finished = false // ...and has been seen by the reader of this cursor
        private var current: Rows? = null

        private suspend fun nextMessage(): Message {
            pending.removeFirstOrNull()?.let { return it }
            val m = read()
            noteWire(m)
            return m
        }

        /** Bookkeeping needed for every message taken off the wire, whoever reads it. */
        private suspend fun noteWire(m: Message) {
            when (m.type) {
                'Z' -> {
                    wireDone = true
                    onReady(m)
                    if (active === this) active = null
                }
                '1' -> {
                    parsing?.name = pendingName
                    parsing = null
                }
                'E' -> {
                    parsing = null // the statement never made it, so it stays unnamed in the cache
                    sync()
                }
                'C', 'I' -> sync()
                'G' -> {
                    // A COPY from STDIN, by a statement that didn't come through copyIn: the server now waits for data, and
                    // would for ever, so this can't wait for anyone to read this far. It ignores any Sync during a COPY, even
                    // one sent before it started, so the extended protocol needs another.
                    message('f') { cstring("Use copyIn for COPY FROM STDIN") }
                    if (execution != null) writeSync()
                    send()
                }
            }
        }

        /** Once a statement ends, Sync (if not sent up front) releases the implicit transaction and brings on 'Z'. */
        private suspend fun sync() {
            if (!synced && !closed) {
                synced = true
                writeSync()
                send()
            }
        }

        suspend fun detach() {
            if (wireDone) return
            while (!wireDone) {
                val m = read()
                if (m.type == 's') { // the rest of the portal, all at once
                    writeExecute(0)
                    synced = true
                    writeSync()
                    send()
                    continue
                }
                noteWire(m)
                pending.addLast(m)
            }
        }

        override suspend fun nextResult(): SqlResult? {
            current?.let { rows -> while (rows.next() != null) { /* skip the unread rows */ } }
            current = null
            while (!finished) {
                val m = nextMessage()
                when (m.type) {
                    '1', '2', '3', 'n', 't', 'I' -> Unit
                    'T' -> {
                        if (unknownTypes(m).isNotEmpty()) {
                            // Asking takes the connection, so what's left of this result moves to memory, this once.
                            detach()
                            loadCatalogTypes(m)
                        }
                        return Rows(parseColumns(m)).also { current = it }
                    }
                    'C' -> return SqlResult.UpdateCount(commandCount(m))
                    'E' -> if (!retried(m)) fail(m)
                    'Z' -> finished = true
                    'G', 'H', 'W', 'd', 'c' -> Unit // COPY OUT data is skipped; a COPY IN has been refused by noteWire
                    else -> throw unexpected(m)
                }
            }
            return null
        }

        /**
         * Handles a prepared statement that the server no longer accepts. This is seen before any result. Without a
         * transaction to worry about the execution starts over, and the caller notices nothing. In a transaction the
         * error aborted it, so all that can be done is to make the next attempt work.
         */
        private suspend fun retried(m: Message): Boolean {
            val statement = execution?.statement ?: return false
            if (statement.name == null) return false
            val sqlState = ServerError.parse(m.reader()).sqlState
            if (sqlState != STALE_PLAN && sqlState != STATEMENT_GONE) return false
            invalidate(statement, sqlState)
            val live = active === this && pending.isEmpty()
            if (!live || !startedIdle) return false
            while (!wireDone) nextMessage()
            wireDone = false
            synced = fetchSize == 0
            active = this
            sendExecution(execution, useCache = false)
            return true
        }

        private suspend fun fail(m: Message): Nothing {
            val error = serverError(m)
            if (!closed) {
                while (!finished) if (nextMessage().type == 'Z') finished = true
            }
            throw error
        }

        override suspend fun close() {
            if (finished || closed) { finished = true; return }
            current = null
            if (wireDone) {
                pending.clear()
                finished = true
                return
            }
            while (!wireDone) {
                pending.clear()
                val m = read()
                if (m.type == 's' && !synced) closePortal() else noteWire(m)
            }
            pending.clear()
            finished = true
        }

        private suspend fun closePortal() {
            writeClosePortal()
            synced = true
            writeSync()
            send()
        }

        private inner class Rows(override val columns: List<ColumnInfo>) : SqlResult.Rows {
            private var done = false
            override var updateCount: Long? = null
                private set

            override fun nextBuffered(): SqlRow? {
                if (done || finished || closed) return null
                val saved = pending.firstOrNull()
                if (saved != null) {
                    if (saved.type != 'D') return null
                    pending.removeFirst()
                    return parseDataRow(columns, saved.body, config.codecs)
                }
                if (wireDone || input.buffered < 5 || input.peekByte(0) != 'D'.code) return null
                val length = input.peekInt32(1)
                if (length < 4 || length - 4 > input.buffered - 5) return null
                input.skipBuffered(5)
                return parseDataRow(columns, input.takeBuffered(length - 4), config.codecs)
            }

            override suspend fun next(): SqlRow? {
                while (!done && !finished) { // the cursor may have been closed from under its rows
                    val m = nextMessage()
                    when (m.type) {
                        'D' -> return parseDataRow(columns, m.body, config.codecs)
                        'C' -> {
                            done = true
                            val tag = m.reader().cstring()
                            if (!tag.startsWith("SELECT") && !tag.startsWith("FETCH")) updateCount = tag.substringAfterLast(' ').toLongOrNull()
                        }
                        's' -> {
                            writeExecute(fetchSize)
                            writeFlush()
                            send()
                        }
                        'E' -> { done = true; current = null; fail(m) }
                        else -> throw unexpected(m)
                    }
                }
                return null
            }
        }
    }

    // ---- Startup ----

    private suspend fun startup() {
        val user = config.user ?: throw SqlException("No user specified", "28000")
        out.clear()
        lengthPrefixed {
            int32(PROTOCOL_3_0)
            fun param(k: String, v: String?) { if (v != null) { cstring(k); cstring(v) } }
            param("user", user)
            param("database", config.database ?: user)
            param("client_encoding", "UTF8")
            param("DateStyle", "ISO, MDY")
            param("extra_float_digits", "3")
            param("application_name", config.applicationName)
            for ((k, v) in config.properties) if (k != PREPARE_THRESHOLD) param(k, v)
            int8(0)
        }
        send()
        authenticate(user)
        var ready = false
        while (!ready) {
            val m = read()
            when (m.type) {
                'K' -> m.reader().let { backendPid = it.int32(); backendSecret = it.rest() }
                'Z' -> { onReady(m); ready = true }
                'E' -> throw serverError(m)
                'v' -> Unit // NegotiateProtocolVersion: the server does without options it does not know
                else -> throw unexpected(m)
            }
        }
        val version = parameters["server_version"] ?: "0"
        val numbers = version.takeWhile { it.isDigit() || it == '.' }.split('.')
        serverInfo = ServerInfo(
            productName = PgDialect.productName,
            version = version,
            majorVersion = numbers.getOrNull(0)?.toIntOrNull() ?: 0,
            minorVersion = numbers.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }

    private suspend fun authenticate(user: String) {
        fun password() = config.password
            ?: throw SqlException("The server requested a password, but none was given", "28P01", kind = SqlErrorKind.AUTHENTICATION)
        var scram: ScramClient? = null
        while (true) {
            val m = read()
            if (m.type == 'E') throw serverError(m)
            if (m.type != 'R') throw unexpected(m)
            val r = m.reader()
            when (val code = r.int32()) {
                0 -> return
                3 -> { message('p') { cstring(password()) }; send() }
                5 -> { val hash = md5Password(user, password(), r.bytes(4)); message('p') { cstring(hash) }; send() }
                10 -> {
                    val mechanisms = generateSequence { r.cstring().ifEmpty { null } }.toList()
                    if (SCRAM_SHA_256 !in mechanisms) {
                        throw SqlException("No supported SASL mechanism among $mechanisms", "28000", kind = SqlErrorKind.AUTHENTICATION)
                    }
                    val client = ScramClient(password()).also { scram = it }
                    val first = client.clientFirst()
                    message('p') { cstring(SCRAM_SHA_256); int32(first.size); bytes(first) }
                    send()
                }
                11 -> {
                    val client = scram ?: throw unexpected(m)
                    val final = client.clientFinal(r.rest())
                    message('p') { bytes(final) }
                    send()
                }
                12 -> (scram ?: throw unexpected(m)).verifyServerFinal(r.rest())
                else -> throw SqlException("Unsupported authentication method (code $code)", "28000", kind = SqlErrorKind.AUTHENTICATION)
            }
        }
    }

    companion object {
        suspend fun open(config: ConnectionConfig, transports: TransportFactory): PgConnection {
            val transport = transports.connect(config.host, config.port ?: DEFAULT_PORT, config.connectTimeoutMillis)
            var opened = false
            try {
                if (config.tlsMode != TlsMode.DISABLE) negotiateTls(transport, config)
                transport.setReadTimeout(config.socketTimeoutMillis)
                val connection = PgConnection(config, transports, transport, BufferedInput(transport))
                connection.startup()
                opened = true
                return connection
            } finally {
                if (!opened) transport.close()
            }
        }

        /**
         * Reads the reply one byte at a time on purpose: bytes the server sent after `S` would be plaintext that an
         * attacker could have injected, so they must not be treated as part of the encrypted stream (CVE-2021-23214).
         */
        private suspend fun negotiateTls(transport: Transport, config: ConnectionConfig) {
            val w = ByteWriter(8)
            w.int32(8)
            w.int32(SSL_REQUEST)
            transport.write(w.buffer, 0, w.size)
            transport.flush()
            val input = BufferedInput(transport, capacity = 1)
            when (val reply = input.readByte().toChar()) {
                'S' -> transport.startTls(TlsOptions(config.tlsMode, config.host, config.tlsRootCertPath))
                'N' -> if (config.tlsMode != TlsMode.PREFER) {
                    throw SqlException("The server does not support TLS, required by sslmode=${config.tlsMode.id}", "08004")
                }
                else -> throw SqlException("Unexpected reply to the TLS request: '$reply'", "08P01")
            }
        }
    }
}

/** ErrorResponse and NoticeResponse fields. */
private class ServerError(private val fields: Map<Char, String>) {
    val severity get() = fields['V'] ?: fields['S']
    val sqlState get() = fields['C']
    val text get() = fields['M'] ?: "Unknown error"

    fun details() = SqlErrorDetails(
        violation = when (sqlState) {
            "23505" -> ConstraintViolation.UNIQUE
            "23503" -> ConstraintViolation.FOREIGN_KEY
            "23502" -> ConstraintViolation.NOT_NULL
            "23514" -> ConstraintViolation.CHECK
            "23P01" -> ConstraintViolation.EXCLUSION
            else -> null
        },
        constraint = fields['n'],
        schema = fields['s'],
        table = fields['t'],
        column = fields['c'],
        dataType = fields['d'],
        severity = severity,
        detail = fields['D'],
        hint = fields['H'],
        position = fields['P']?.toIntOrNull(),
        where = fields['W'],
        routine = fields['R'],
    )

    override fun toString() = buildString {
        append(severity ?: "ERROR").append(": ").append(text)
        fields['D']?.let { append("\n  Detail: ").append(it) }
        fields['H']?.let { append("\n  Hint: ").append(it) }
    }

    companion object {
        fun parse(r: ByteReader): ServerError {
            val fields = HashMap<Char, String>()
            while (true) {
                val code = r.int8()
                if (code == 0) break
                fields[code.toChar()] = r.cstring()
            }
            return ServerError(fields)
        }
    }
}

private const val BACKSLASH: Byte = 92
private const val TAB: Byte = 9
private const val NEWLINE: Byte = 10
private const val CARRIAGE_RETURN: Byte = 13

/** A row in COPY's text format, in which a tab, a newline and a backslash in a value need a backslash, and NULL is `\\N`. */
private fun writeCopyRow(out: ByteWriter, values: List<EncodedParam>) {
    values.forEachIndexed { i, value ->
        if (i > 0) out.int8('\t'.code)
        val text = value.text
        if (text == null) {
            out.int8('\\'.code)
            out.int8('N'.code)
        } else {
            // Stretches with nothing to escape go in whole: writing byte by byte took most of the time on native targets.
            var from = 0
            for (i in text.indices) {
                val escape = when (text[i]) {
                    BACKSLASH -> '\\'
                    TAB -> 't'
                    NEWLINE -> 'n'
                    CARRIAGE_RETURN -> 'r'
                    else -> continue
                }
                out.bytes(text, from, i - from)
                out.int8('\\'.code)
                out.int8(escape.code)
                from = i + 1
            }
            out.bytes(text, from, text.size - from)
        }
    }
    out.int8('\n'.code)
}

/** The opposite of [writeCopyRow], for what the server sends. */
private fun parseCopyRow(line: ByteArray): List<String?> {
    val end = if (line.isNotEmpty() && line.last() == '\n'.code.toByte()) line.size - 1 else line.size
    val values = ArrayList<String?>()
    val current = ByteWriter(64)
    var isNull = false
    var i = 0
    fun finish() {
        values += if (isNull) null else current.buffer.decodeToString(0, current.size)
        current.clear()
        isNull = false
    }
    while (i < end) {
        val b = line[i++].toInt()
        when {
            b == '\t'.code -> finish()
            b == '\\'.code && i < end -> when (val next = line[i++].toInt().toChar()) {
                'N' -> isNull = true
                'n' -> current.int8('\n'.code)
                't' -> current.int8('\t'.code)
                'r' -> current.int8('\r'.code)
                'b' -> current.int8(8)
                'f' -> current.int8(12)
                'v' -> current.int8(11)
                else -> current.int8(next.code)
            }
            else -> current.int8(b)
        }
    }
    finish()
    return values
}
