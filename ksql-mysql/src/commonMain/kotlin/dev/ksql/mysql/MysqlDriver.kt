package dev.ksql.mysql

import dev.ksql.ConnectionConfig
import dev.ksql.SqlConnection
import dev.ksql.SqlDialect
import dev.ksql.spi.SqlDriver
import dev.ksql.spi.TransportFactory

class MysqlDriver : SqlDriver {
    override val schemes: Set<String> = setOf("mysql", "mariadb")
    override val dialect: SqlDialect get() = MysqlDialect
    override suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection =
        MysqlConnection.open(config, transports)
}
