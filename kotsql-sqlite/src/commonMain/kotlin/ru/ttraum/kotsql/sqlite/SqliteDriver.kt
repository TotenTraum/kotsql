package ru.ttraum.kotsql.sqlite

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.spi.SqlDriver

/**
 * SQLite, for URLs such as `sqlite:///relative.db`, `sqlite:////absolute/path.db` and `sqlite:///:memory:`.
 *
 * The database is in this process and its calls block the thread they run on, as they do no I/O that could be waited for
 * otherwise. So a dispatcher for that is up to the caller, for example `withContext(Dispatchers.IO)`.
 */
class SqliteDriver : SqlDriver {
    override val schemes: Set<String> = setOf("sqlite")
    override val dialect: SqlDialect get() = SqliteDialect

    override suspend fun connect(config: ConnectionConfig): SqlConnection = SqliteConnection.open(config)
}

/** Opens the database at [path], or a private one in memory with `:memory:`. */
suspend fun connectSqlite(path: String, codecs: CodecRegistry = CodecRegistry.Global): SqlConnection =
    SqliteConnection.open(ConnectionConfig(scheme = "sqlite", database = path, codecs = codecs))
