package ru.ttraum.kotsql.spi

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlDialect
import ru.ttraum.kotsql.SqlException

/**
 * Entry point of a database driver. To add a database, implement this, [SqlConnection] and [SqlDialect], then register
 * the driver: on the JVM with a `META-INF/services/ru.ttraum.kotsql.spi.SqlDriver` file, elsewhere with [Drivers.register].
 * The JDBC and R2DBC adapters then work with it unchanged. A database behind a socket implements `NetworkSqlDriver` of
 * kotsql-transport.
 *
 * Write the connection as ordinary suspending code that takes no locks or dispatchers of its own: the JDBC adapter runs it
 * without suspending at all (see [runUnsuspended]).
 */
interface SqlDriver {
    /** URL schemes handled by the driver, e.g. `postgresql`. */
    val schemes: Set<String>
    val dialect: SqlDialect
    suspend fun connect(config: ConnectionConfig): SqlConnection
}

object Drivers {
    private val explicit = ArrayList<SqlDriver>()

    fun register(driver: SqlDriver) { explicit += driver }

    fun find(scheme: String): SqlDriver? =
        (explicit + discoverPlatformDrivers()).firstOrNull { scheme in it.schemes }

    fun require(scheme: String): SqlDriver = find(scheme)
        ?: throw SqlException("No kotsql driver registered for '$scheme'", "08001")
}

/** Drivers found automatically: `ServiceLoader` on the JVM, none elsewhere. */
internal expect fun discoverPlatformDrivers(): List<SqlDriver>
