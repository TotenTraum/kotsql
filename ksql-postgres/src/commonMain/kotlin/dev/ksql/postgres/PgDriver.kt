package dev.ksql.postgres

import dev.ksql.ConnectionConfig
import dev.ksql.SqlConnection
import dev.ksql.SqlDialect
import dev.ksql.spi.SqlDriver
import dev.ksql.spi.TransportFactory

class PostgresDriver : SqlDriver {
    override val schemes: Set<String> = setOf("postgresql", "postgres")
    override val dialect: SqlDialect get() = PgDialect
    override suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection =
        PgConnection.open(config, transports)
}
