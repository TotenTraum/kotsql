package ru.ttraum.kotsql.transport

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.spi.SqlDriver

/**
 * @param certPath a PEM file with the client certificate to present, and any intermediates
 * @param keyPath a PEM file with its private key, without a password
 */
data class TlsOptions(
    val mode: TlsMode,
    val serverName: String,
    val rootCertPath: String? = null,
    val certPath: String? = null,
    val keyPath: String? = null,
)

/** What a transport needs in order to encrypt a connection to the server of this configuration. */
val ConnectionConfig.tlsOptions: TlsOptions get() = TlsOptions(tlsMode, host, tlsRootCertPath, tlsCertPath, tlsKeyPath)

/** A byte stream to a server. */
interface Transport {
    val isSecure: Boolean

    /** The `tls-server-end-point` channel binding of an encrypted connection, if this transport can tell. */
    val tlsServerEndPoint: ByteArray? get() = null

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

/**
 * A driver of a database behind a socket. It does all its I/O through [Transport] and takes no locks or dispatchers of its
 * own, as the JDBC adapter runs it without suspending at all (see [ru.ttraum.kotsql.spi.runUnsuspended]).
 */
interface NetworkSqlDriver : SqlDriver {
    suspend fun connect(config: ConnectionConfig, transports: TransportFactory): SqlConnection

    override suspend fun connect(config: ConnectionConfig): SqlConnection = throw SqlException(
        "This database is reached over a network, which takes a transport: use connect() of kotsql-transport-ktor, or one with a TransportFactory",
        "08001",
    )
}

/** Connects with [transports] if this driver needs a network at all. For an adapter, which works with any driver. */
suspend fun SqlDriver.connectVia(config: ConnectionConfig, transports: TransportFactory): SqlConnection =
    if (this is NetworkSqlDriver) connect(config, transports) else connect(config)

/** Opens a connection using the driver registered for the URL's scheme, e.g. `postgresql://user:pw@host/db`. */
suspend fun connect(url: String, transports: TransportFactory, codecs: CodecRegistry = CodecRegistry.Global): SqlConnection {
    val config = ConnectionConfig.parse(url).copy(codecs = codecs)
    return Drivers.require(config.scheme).connectVia(config, transports)
}
