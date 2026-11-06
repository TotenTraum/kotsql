package ru.ttraum.kotsql.postgres

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.transport.NetworkSqlDriver
import ru.ttraum.kotsql.transport.TransportFactory
import ru.ttraum.kotsql.transport.connectToSuitableServer

class PostgresDriver : NetworkSqlDriver {
    override val schemes: Set<String> = setOf("postgresql", "postgres")
    override val dialect: SqlDialect get() = PgDialect
    override suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection =
        connectToSuitableServer(config, "SELECT pg_catalog.pg_is_in_recovery()") { PgConnection.open(it, transports) }
}
