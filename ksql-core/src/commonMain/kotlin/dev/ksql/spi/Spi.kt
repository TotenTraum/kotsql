package dev.ksql.spi

import dev.ksql.ConnectionConfig
import dev.ksql.SqlConnection
import dev.ksql.SqlDialect
import dev.ksql.SqlException
import dev.ksql.TlsOptions

/**
 * Entry point of a database driver. To add a database, implement this, [SqlConnection] and [SqlDialect], then register
 * the driver: on the JVM with a `META-INF/services/dev.ksql.spi.SqlDriver` file, elsewhere with [Drivers.register].
 * The JDBC and R2DBC adapters then work with it unchanged.
 *
 * Write the connection as ordinary suspending code that does all its I/O through [Transport] and takes no locks or
 * dispatchers of its own: the JDBC adapter runs it without suspending at all (see [runUnsuspended]).
 */
interface SqlDriver {
    /** URL schemes handled by the driver, e.g. `postgresql`. */
    val schemes: Set<String>
    val dialect: SqlDialect
    suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection
}

/** A byte stream to a server. */
interface Transport {
    val isSecure: Boolean
    fun setReadTimeout(millis: Long)

    /** Reads at least one byte into [buffer] and returns the count, or -1 at end of stream. */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun flush()

    /** Upgrades the open connection to TLS, for protocols that negotiate it in-band. */
    suspend fun startTls(options: TlsOptions)
    suspend fun close()
}

fun interface TransportFactory {
    suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport
}

object Drivers {
    private val explicit = ArrayList<SqlDriver>()

    fun register(driver: SqlDriver) { explicit += driver }

    fun find(scheme: String): SqlDriver? =
        (explicit + discoverPlatformDrivers()).firstOrNull { scheme in it.schemes }

    fun require(scheme: String): SqlDriver = find(scheme)
        ?: throw SqlException("No ksql driver registered for '$scheme'", "08001")
}

/** Drivers found automatically: `ServiceLoader` on the JVM, none elsewhere. */
internal expect fun discoverPlatformDrivers(): List<SqlDriver>
