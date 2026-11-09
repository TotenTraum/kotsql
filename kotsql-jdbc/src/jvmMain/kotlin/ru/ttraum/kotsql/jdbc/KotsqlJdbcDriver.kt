package ru.ttraum.kotsql.jdbc

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.SqlBatchException
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transport.SocketTransport
import ru.ttraum.kotsql.transport.connectVia
import ru.ttraum.kotsql.spi.runUnsuspended
import java.io.PrintWriter
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransactionRollbackException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

internal const val URL_PREFIX = "jdbc:kotsql:"
internal const val DRIVER_NAME = "kotsql JDBC"
internal const val DRIVER_VERSION = "0.1.0"

/** JDBC entry point for every kotsql driver. URLs look like `jdbc:kotsql:postgresql://host:5432/db?sslmode=require`. */
public class KotsqlJdbcDriver : Driver {
    override fun acceptsURL(url: String?): Boolean = url != null && url.startsWith(URL_PREFIX)

    override fun connect(url: String?, info: Properties?): Connection? = connect(url, info, CodecRegistry.Global)

    internal fun connect(url: String?, info: Properties?, codecs: CodecRegistry): Connection? {
        if (!acceptsURL(url)) return null // required by the Driver contract
        return translating {
            val props = info?.stringPropertyNames()?.associateWith { info.getProperty(it) }.orEmpty()
            val config = ConnectionConfig.parse(url!!.removePrefix(URL_PREFIX)).withProperties(props).copy(codecs = codecs)
            val core = runUnsuspended { Drivers.require(config.scheme).connectVia(config, SocketTransport) }
            JdbcConnection(core, url.substringBefore('?'), config.user)
        }
    }

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()
    override fun getMajorVersion(): Int = 0
    override fun getMinorVersion(): Int = 1
    override fun jdbcCompliant(): Boolean = false
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("getParentLogger")

    public companion object {
        init { DriverManager.registerDriver(KotsqlJdbcDriver()) }
    }
}

/** A plain, unpooled `DataSource`. Wrap it in a pool such as HikariCP for production use. */
public class KotsqlDataSource(
    public var url: String = "",
    public var user: String? = null,
    public var password: String? = null,
    /** The codecs for this data source's connections. `DriverManager` can only use the global ones. */
    public var codecs: CodecRegistry = CodecRegistry.Global,
) : DataSource {
    private var logWriter: PrintWriter? = null
    private var loginTimeout = 0

    override fun getConnection(): Connection = getConnection(user, password)
    override fun getConnection(username: String?, password: String?): Connection {
        val props = Properties()
        username?.let { props["user"] = it }
        password?.let { props["password"] = it }
        if (loginTimeout > 0) props["connectTimeout"] = (loginTimeout * 1000L).toString()
        return KotsqlJdbcDriver().connect(url, props, codecs) ?: throw SQLException("Not a kotsql URL: $url", "08001")
    }
    override fun getLogWriter(): java.io.PrintWriter? = logWriter
    override fun setLogWriter(out: PrintWriter?) { logWriter = out }
    override fun setLoginTimeout(seconds: Int) { loginTimeout = seconds }
    override fun getLoginTimeout(): Int = loginTimeout
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("getParentLogger")
    override fun <T : Any?> unwrap(iface: Class<T>): T = if (iface.isInstance(this)) iface.cast(this) else throw SQLException("Not a wrapper of $iface")
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

internal fun unsupported(what: String): Nothing = throw SQLFeatureNotSupportedException("$what is not supported")

internal fun emptyMetadata(@Suppress("UNUSED_PARAMETER") what: String): java.sql.ResultSet =
    JdbcResultSet(null, listRows(emptyList(), emptyList()))

internal inline fun <T> translating(block: () -> T): T = try {
    block()
} catch (e: SqlBatchException) {
    throw BatchUpdateException(e.message, e.sqlState, e.vendorCode, e.updateCounts, e)
} catch (e: SqlException) {
    throw e.toJdbc()
}

internal fun SqlException.toJdbc(): SQLException {
    val m = message
    val s = sqlState
    val v = vendorCode
    return when (kind) {
        SqlErrorKind.CONNECTION -> SQLNonTransientConnectionException(m, s, v, this)
        SqlErrorKind.AUTHENTICATION -> SQLInvalidAuthorizationSpecException(m, s, v, this)
        SqlErrorKind.SYNTAX, SqlErrorKind.PERMISSION -> SQLSyntaxErrorException(m, s, v, this)
        SqlErrorKind.INTEGRITY -> SQLIntegrityConstraintViolationException(m, s, v, this)
        SqlErrorKind.DATA -> SQLDataException(m, s, v, this)
        SqlErrorKind.ROLLBACK -> SQLTransactionRollbackException(m, s, v, this)
        SqlErrorKind.TIMEOUT -> SQLTimeoutException(m, s, v, this)
        SqlErrorKind.UNSUPPORTED -> SQLFeatureNotSupportedException(m, s, v, this)
        SqlErrorKind.OTHER -> SQLException(m, s, v, this)
    }
}
