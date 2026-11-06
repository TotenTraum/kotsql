package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.TargetServerType

/**
 * Connects to the first of the configured servers that responds and suits [ConnectionConfig.targetServerType]. For failover
 * a connection that has been lost is simply replaced by a new one through this, as a connection pool does by itself.
 *
 * @param open opens a connection to the one server of the given configuration
 * @param roleQuery a query with a single value that is true, or non-zero, on a read-only server
 */
suspend fun <C : SqlConnection> connectToSuitableServer(config: ConnectionConfig, roleQuery: String, open: suspend (ConnectionConfig) -> C): C {
    val target = config.targetServerType
    if (config.alternateServers.isEmpty() && target == TargetServerType.ANY) return open(config)

    val servers = if (config.loadBalanceHosts) config.servers.shuffled() else config.servers
    var fallback: C? = null
    var failure: SqlException? = null
    var handedOut: C? = null
    try {
        for (server in servers) {
            val connection = try {
                open(config.copy(host = server.host, port = server.port, alternateServers = emptyList()))
            } catch (e: SqlException) {
                if (!isWorthAnotherServer(e)) throw e
                failure?.let(e::addSuppressed)
                failure = e
                continue
            }
            var keep = false
            try {
                if (target == TargetServerType.ANY) {
                    keep = true
                    handedOut = connection
                    return connection
                }
                val readOnly = isReadOnly(connection, roleQuery)
                if (target == TargetServerType.PRIMARY && !readOnly || target != TargetServerType.PRIMARY && readOnly) {
                    keep = true
                    handedOut = connection
                    return connection
                }
                if (target == TargetServerType.PREFER_SECONDARY && fallback == null) {
                    keep = true
                    fallback = connection
                }
            } catch (e: SqlException) {
                if (!isWorthAnotherServer(e)) throw e
                failure?.let(e::addSuppressed)
                failure = e
            } finally {
                if (!keep) connection.close()
            }
        }
        fallback?.let {
            handedOut = it
            return it
        }
        val names = servers.joinToString(", ") { a -> a.host + (a.port?.let { ":$it" } ?: "") }
        val reason = if (failure != null) "none of the servers can be reached ($names)" else "none of the servers is a ${target.id} ($names)"
        throw SqlException("Cannot connect: $reason", "08001", cause = failure)
    } finally {
        fallback?.let { if (it !== handedOut) it.close() }
    }
}

/** A server that refuses a password would refuse it anywhere, while one that is down or full says nothing of the others. */
private fun isWorthAnotherServer(e: SqlException) = e.kind == SqlErrorKind.CONNECTION || e.sqlState?.startsWith("53") == true

private suspend fun isReadOnly(connection: SqlConnection, roleQuery: String): Boolean {
    val cursor = connection.executeScript(roleQuery)
    try {
        val rows = cursor.nextResult() as? SqlResult.Rows ?: throw SqlException("The server's role can't be told", "08P01")
        val row = rows.next() ?: throw SqlException("The server's role can't be told", "08P01")
        return when (val value = row[0]) {
            is Boolean -> value
            is Number -> value.toLong() != 0L
            else -> throw SqlException("The server's role can't be told from '$value'", "08P01")
        }
    } finally {
        cursor.close()
    }
}
