package ru.ttraum.kotsql.mysql

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.transport.NetworkSqlDriver
import ru.ttraum.kotsql.transport.TransportFactory
import ru.ttraum.kotsql.transport.connectToSuitableServer

class MysqlDriver : NetworkSqlDriver {
    override val schemes: Set<String> = setOf("mysql", "mariadb")
    override val dialect: SqlDialect get() = MysqlDialect
    override suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection =
        connectToSuitableServer(config, "SELECT @@global.read_only") { MysqlConnection.open(it, transports) }
}
