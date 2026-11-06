package ru.ttraum.kotsql.r2dbc

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.TargetServerType
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TlsMode
import ru.ttraum.kotsql.transport.AsyncSocketTransport
import ru.ttraum.kotsql.transport.connectVia
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.spi.SqlDriver
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryProvider
import io.r2dbc.spi.Option
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcPermissionDeniedException
import io.r2dbc.spi.R2dbcRollbackException
import io.r2dbc.spi.R2dbcTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.publish
import org.reactivestreams.Publisher
import java.time.Duration

private const val DRIVER = "kotsql"

/** Discovered for URLs like `r2dbc:kotsql:postgresql://user:password@host:5432/db?sslmode=require`. */
class KotsqlConnectionFactoryProvider : ConnectionFactoryProvider {
    private val sslMode = Option.valueOf<String>("sslmode")
    private val sslRootCert = Option.valueOf<String>("sslrootcert")
    private val sslCert = Option.valueOf<String>("sslcert")
    private val sslKey = Option.valueOf<String>("sslkey")
    private val channelBinding = Option.valueOf<String>("channel_binding")
    private val servers = Option.valueOf<String>("servers")
    private val targetServerType = Option.valueOf<String>("targetServerType")
    private val loadBalanceHosts = Option.valueOf<String>("loadBalanceHosts")
    private val applicationName = Option.valueOf<String>("applicationName")
    private val codecs = Option.valueOf<CodecRegistry>("codecs")

    override fun supports(options: ConnectionFactoryOptions): Boolean =
        options.getValue(ConnectionFactoryOptions.DRIVER) == DRIVER

    override fun getDriver() = DRIVER

    override fun create(options: ConnectionFactoryOptions): ConnectionFactory {
        val scheme = options.getRequiredValue(ConnectionFactoryOptions.PROTOCOL)?.toString()
            ?: throw IllegalArgumentException("Specify the database as the protocol, as in r2dbc:kotsql:postgresql://...")
        val driver = Drivers.find(scheme) ?: throw IllegalArgumentException("No kotsql driver registered for '$scheme'")
        fun number(value: Any?): Int? = when (value) { is Number -> value.toInt(); null -> null; else -> value.toString().toInt() }
        fun duration(value: Any?): Long? = when (value) {
            is Duration -> value.toMillis()
            null -> null
            else -> Duration.parse(value.toString()).toMillis()
        }
        val ssl = options.getValue(ConnectionFactoryOptions.SSL)
        val mode = options.getValue(sslMode)?.toString()?.let(TlsMode::of)
            ?: when (ssl?.toString()?.toBoolean()) { true -> TlsMode.REQUIRE; false -> TlsMode.DISABLE; null -> TlsMode.PREFER }
        val config = ConnectionConfig(
            scheme = scheme,
            host = options.getValue(ConnectionFactoryOptions.HOST)?.toString() ?: "localhost",
            port = number(options.getValue(ConnectionFactoryOptions.PORT)),
            database = options.getValue(ConnectionFactoryOptions.DATABASE)?.toString(),
            user = options.getValue(ConnectionFactoryOptions.USER)?.toString(),
            password = options.getValue(ConnectionFactoryOptions.PASSWORD)?.toString(),
            tlsMode = mode,
            tlsRootCertPath = options.getValue(sslRootCert)?.toString(),
            tlsCertPath = options.getValue(sslCert)?.toString(),
            tlsKeyPath = options.getValue(sslKey)?.toString(),
            // The others, after the host and the port. Or all of them, without those.
            alternateServers = options.getValue(servers)?.toString()?.let(ConnectionConfig::parseServers).orEmpty(),
            targetServerType = options.getValue(targetServerType)?.toString()?.let(TargetServerType::of) ?: TargetServerType.ANY,
            loadBalanceHosts = options.getValue(loadBalanceHosts)?.toString()?.toBoolean() ?: false,
            properties = listOfNotNull(options.getValue(channelBinding)?.let { "channel_binding" to it.toString() }).toMap(),
            connectTimeoutMillis = duration(options.getValue(ConnectionFactoryOptions.CONNECT_TIMEOUT)) ?: 10_000,
            applicationName = options.getValue(applicationName)?.toString() ?: "kotsql",
            codecs = options.getValue(codecs) as CodecRegistry? ?: CodecRegistry.Global,
        )
        return KotsqlConnectionFactory(driver, config)
    }
}

class KotsqlConnectionFactory internal constructor(private val driver: SqlDriver, private val config: ConnectionConfig) : ConnectionFactory {
    override fun create(): Publisher<out Connection> = one {
        R2dbcConnection(translating { driver.connectVia(config, AsyncSocketTransport) })
    }
    override fun getMetadata() = ConnectionFactoryMetadata { driver.dialect.productName }
}

/**
 * Publishers run on the subscribing thread until they first wait, then on the thread that the awaited data arrives on,
 * as in other reactive drivers. A dispatcher in between would cost two thread switches per statement and a large part of
 * the throughput. So, as usual in reactive code, subscribers must not block on those threads.
 */
internal val PublisherContext = Dispatchers.Unconfined

/** A cold publisher of one value. */
internal fun <T : Any> one(block: suspend () -> T): Publisher<T> = publish(PublisherContext) { send(block()) }

/** A cold publisher that only completes. */
internal fun none(block: suspend () -> Unit): Publisher<Void> = publish(PublisherContext) { block() }

internal inline fun <T> translating(block: () -> T): T = try {
    block()
} catch (e: SqlException) {
    throw e.toR2dbc()
}

internal fun SqlException.toR2dbc(): R2dbcException {
    val m = message
    val s = sqlState
    val v = vendorCode
    return when (kind) {
        SqlErrorKind.SYNTAX -> R2dbcBadGrammarException(m, s, v, this)
        SqlErrorKind.INTEGRITY -> R2dbcDataIntegrityViolationException(m, s, v, this)
        SqlErrorKind.PERMISSION, SqlErrorKind.AUTHENTICATION -> R2dbcPermissionDeniedException(m, s, v, this)
        SqlErrorKind.ROLLBACK -> R2dbcRollbackException(m, s, v, this)
        SqlErrorKind.TIMEOUT -> R2dbcTimeoutException(m, s, v, this)
        else -> R2dbcNonTransientResourceException(m, s, v, this)
    }
}
