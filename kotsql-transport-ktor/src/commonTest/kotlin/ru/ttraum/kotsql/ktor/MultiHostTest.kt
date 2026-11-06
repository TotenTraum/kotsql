package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.ConnectionConfig
import ru.ttraum.kotsql.ServerAddress
import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.TargetServerType
import ru.ttraum.kotsql.transport.TlsOptions
import ru.ttraum.kotsql.transport.connect
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.pool.ConnectionPool
import ru.ttraum.kotsql.pool.PoolConfig
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.transport.Transport
import ru.ttraum.kotsql.transport.TransportFactory
import ru.ttraum.kotsql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/** The real transport, except for a server that has gone down as far as this client can tell. */
private class PartitionableFactory : TransportFactory {
    var down: String? = null
    val attempts = ArrayList<String>()

    override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Long): Transport {
        val address = "$host:$port"
        attempts += address
        if (address == down) throw SqlException("Cannot connect to $address: the test says it's down", "08001")
        val inner = DefaultTransport.connect(host, port, connectTimeoutMillis)
        return object : Transport by inner {
            private fun check() { if (address == down) throw RuntimeException("Connection to $address lost") }
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(); return inner.read(buffer, offset, length) }
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) { check(); inner.write(buffer, offset, length) }
            override suspend fun startTls(options: TlsOptions) = inner.startTls(options)
        }
    }
}

class MultiHostTest {
    private val primary = testEnv("KOTSQL_TEST_PRIMARY")
    private val standby = testEnv("KOTSQL_TEST_STANDBY")

    @Test fun parsing() {
        val config = ConnectionConfig.parse("postgresql://u:p@one:1,[::1]:2,three/db?targetServerType=preferSecondary&loadBalanceHosts=true&sslmode=disable")
        assertEquals(listOf(ServerAddress("one", 1), ServerAddress("::1", 2), ServerAddress("three", null)), config.servers)
        assertEquals("one", config.host)
        assertEquals(1, config.port)
        assertEquals("db", config.database)
        assertEquals(TargetServerType.PREFER_SECONDARY, config.targetServerType)
        assertTrue(config.loadBalanceHosts)
        assertEquals(mapOf(), config.properties)
        assertEquals(listOf(ServerAddress("one")), ConnectionConfig.parse("postgresql://one/db").servers)
        assertFailsWith<SqlException> { ConnectionConfig.parse("postgresql://one:x,two/db") }
        assertFailsWith<SqlException> { ConnectionConfig.parse("postgresql://one/db?targetServerType=leader") }
    }

    private fun replicaTest(block: suspend (String, String) -> Unit) = runTest(timeout = 120.seconds) {
        val first = primary
        val second = standby
        if (first == null || second == null) return@runTest println("KOTSQL_TEST_PRIMARY and KOTSQL_TEST_STANDBY are not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            block(first, second)
        }
    }

    private fun url(servers: String, target: String) = "postgresql://kotsql:kotsql@$servers/kotsql?sslmode=disable&connectTimeout=3000&targetServerType=$target"

    private suspend fun isStandby(url: String, transports: TransportFactory = DefaultTransport): Boolean {
        val connection = connect(url, transports)
        try {
            return connection.query("SELECT pg_is_in_recovery()").first()[0] as Boolean
        } finally {
            connection.close()
        }
    }

    /** One test, as its last part promotes the standby, which the others need to be one. */
    @Test fun primaryAndStandby() = replicaTest { primary, standby ->
        assertEquals(false, isStandby(url("$standby,$primary", "primary")))
        assertEquals(false, isStandby(url("$primary,$standby", "primary")))
        assertEquals(true, isStandby(url("$primary,$standby", "secondary")))
        assertEquals(true, isStandby(url("$primary,$standby", "preferSecondary")))
        assertEquals(false, isStandby(url(primary, "preferSecondary")))
        assertEquals(false, isStandby(url("$primary,$standby", "any")))
        assertEquals(true, isStandby(url("$standby,$primary", "any")))
        assertEquals(setOf(true, false), List(40) { isStandby(url("$primary,$standby", "any") + "&loadBalanceHosts=true") }.toSet())

        assertEquals("08001", assertFailsWith<SqlException> { isStandby(url(primary, "secondary")) }.sqlState)
        assertEquals("08001", assertFailsWith<SqlException> { isStandby(url(standby, "primary")) }.sqlState)

        // A password refused is the end of it: the next server would only be another attempt at guessing it.
        val attempts = PartitionableFactory()
        val refused = assertFailsWith<SqlException> { isStandby(url("$standby,$primary", "any").replace(":kotsql@", ":wrong@"), attempts) }
        assertEquals(SqlErrorKind.AUTHENTICATION, refused.kind)
        assertEquals(1, attempts.attempts.size)

        failover(primary, standby)
    }

    private suspend fun failover(primary: String, standby: String) {
        val factory = PartitionableFactory()
        val pool = ConnectionPool({ connect(url("$primary,$standby", "primary"), factory) }, PoolConfig(maxSize = 2, acquireTimeout = 10.seconds, validateAfterIdle = 0.seconds))
        val table = "failover_${Random.nextInt(1_000_000)}"
        try {
            pool.update("CREATE TABLE $table (a int)")
            pool.update("INSERT INTO $table VALUES (1)")
            // It has to have reached the standby to survive this.
            val replica = connect(url(standby, "secondary"))
            try {
                repeat(100) {
                    if (replica.query("SELECT count(*) FROM pg_class WHERE relname = '$table'").first()[0] == 1L) {
                        if (replica.query("SELECT count(*) FROM $table").first()[0] == 1L) return@repeat
                    }
                    delay(50)
                }
            } finally {
                replica.close()
            }
            factory.down = primary
            // The primary's gone, and until a new one is there nothing can be done, whichever way that shows.
            assertFailsWith<SqlException> { pool.update("INSERT INTO $table VALUES (2)") }
            assertFailsWith<SqlException> { pool.update("INSERT INTO $table VALUES (2)") }

            val promoting = connect(url(standby, "any"))
            try {
                promoting.query("SELECT pg_promote()").toList()
            } finally {
                promoting.close()
            }

            // No change to the application or the pool: a new connection finds the new primary.
            var inserted = false
            repeat(100) {
                if (!inserted) {
                    inserted = runCatching { pool.update("INSERT INTO $table VALUES (3)") }.isSuccess
                    if (!inserted) delay(100)
                }
            }
            if (!inserted) fail("Not back after the promotion")
            assertEquals(listOf(1, 3), pool.query("SELECT a FROM $table ORDER BY a").toList().map { it[0] })
            assertEquals(false, pool.query("SELECT pg_is_in_recovery()").first()[0])
            assertEquals(0, pool.stats().inUse)
        } finally {
            pool.close()
        }
    }

    /** MySQL goes through the same search, up to its own question about the server's role. */
    @Test fun mysql() = runTest(timeout = 60.seconds) {
        val address = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT") ?: return@runTest println("KOTSQL_TEST_MYSQL_HOST_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            val base = "mysql://root:kotsql@localhost:1,$address/kotsql?sslmode=disable&allowPublicKeyRetrieval=true"
            val connection = connect(base)
            try {
                assertEquals(1, (connection.query("SELECT 1").first()[0] as Number).toInt())
            } finally {
                connection.close()
            }
            val primary = connect("$base&targetServerType=primary")
            primary.close()
            assertEquals("08001", assertFailsWith<SqlException> { connect("$base&targetServerType=secondary") }.sqlState)
        }
    }
}
