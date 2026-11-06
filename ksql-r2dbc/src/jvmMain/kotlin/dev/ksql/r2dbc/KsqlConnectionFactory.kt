package dev.ksql.r2dbc

import dev.ksql.CodecRegistry
import dev.ksql.ConnectionConfig
import dev.ksql.SqlErrorKind
import dev.ksql.SqlException
import dev.ksql.TlsMode
import dev.ksql.spi.AsyncSocketTransport
import dev.ksql.spi.Drivers
import dev.ksql.spi.SqlDriver
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

private const val DRIVER = "ksql"

/** Discovered for URLs like `r2dbc:ksql:postgresql://user:password@host:5432/db?sslmode=require`. */
class KsqlConnectionFactoryProvider : ConnectionFactoryProvider {
    private val sslMode = Option.valueOf<String>("sslmode")
    private val sslRootCert = Option.valueOf<String>("sslrootcert")
    private val applicationName = Option.valueOf<String>("applicationName")
    private val codecs = Option.valueOf<CodecRegistry>("codecs")

    override fun supports(options: ConnectionFactoryOptions): Boolean =
        options.getValue(ConnectionFactoryOptions.DRIVER) == DRIVER

    override fun getDriver() = DRIVER

    override fun create(options: ConnectionFactoryOptions): ConnectionFactory {
        val scheme = options.getRequiredValue(ConnectionFactoryOptions.PROTOCOL)?.toString()
            ?: throw IllegalArgumentException("Specify the database as the protocol, as in r2dbc:ksql:postgresql://...")
        val driver = Drivers.find(scheme) ?: throw IllegalArgumentException("No ksql driver registered for '$scheme'")
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
            connectTimeoutMillis = duration(options.getValue(ConnectionFactoryOptions.CONNECT_TIMEOUT)) ?: 10_000,
            applicationName = options.getValue(applicationName)?.toString() ?: "ksql",
            codecs = options.getValue(codecs) as CodecRegistry? ?: CodecRegistry.Global,
        )
        return KsqlConnectionFactory(driver, config)
    }
}

class KsqlConnectionFactory internal constructor(private val driver: SqlDriver, private val config: ConnectionConfig) : ConnectionFactory {
    override fun create(): Publisher<out Connection> = one {
        R2dbcConnection(translating { driver.connect(config, AsyncSocketTransport) })
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
