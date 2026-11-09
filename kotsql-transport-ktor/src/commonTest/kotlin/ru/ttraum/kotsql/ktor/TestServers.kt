package ru.ttraum.kotsql.ktor

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import ru.ttraum.testcontainers.Docker
import ru.ttraum.testcontainers.GenericContainer
import ru.ttraum.testcontainers.Network
import ru.ttraum.testcontainers.wait.Wait
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** The process's environment variable [name], if set. */
internal expect fun systemEnv(name: String): String?

/** Runs [block] to completion on this thread, for the test setup that runs outside any coroutine. */
internal expect fun <T> blocking(block: suspend () -> T): T

/** Whether this platform normalizes SCRAM passwords (SASLprep's NFKC); native Linux has no API for it. */
internal expect val platformNormalizes: Boolean

/**
 * A setting of the client tests: the environment variable if set, otherwise what [TestServers] started. With none of the
 * `KOTSQL_TEST_*` variables set and Docker available, the tests start their own servers, so `./gradlew jvmTest` or
 * `macosArm64Test` needs nothing else.
 */
internal fun testEnv(name: String): String? = systemEnv(name) ?: TestServers.setting(name)

/**
 * PostgreSQL with TLS and the roles the tests log in as, a streaming standby of it, and MySQL, all in containers that
 * [ru.ttraum.testcontainers] removes after the process. Started once, by the first test that asks.
 *
 * Not used when the environment names the servers (`KOTSQL_TEST_URL` set), as `tools/run-client-tests.sh` does for the
 * runs in Linux containers and under Wine, or with `KOTSQL_TEST_CONTAINERS=false`.
 */
internal object TestServers {
    private const val POSTGRES = "postgres:17"
    private const val MYSQL = "mysql:8.4"

    private val settings: Map<String, String>? by lazy {
        if (systemEnv("KOTSQL_TEST_URL") != null || systemEnv("KOTSQL_TEST_CONTAINERS") == "false") return@lazy null
        blocking {
            if (!Docker.isAvailable()) {
                println("No Docker and no KOTSQL_TEST_URL: the tests that need a server skip themselves")
                null
            } else {
                start()
            }
        }
    }

    fun setting(name: String): String? = if (name.startsWith("KOTSQL_TEST_")) settings?.get(name) else null

    private suspend fun start(): Map<String, String> {
        val network = Network()
        val postgres = GenericContainer(POSTGRES) {
            withNetwork(network)
            withNetworkAliases("postgres")
            withEnv(mapOf("POSTGRES_USER" to "kotsql", "POSTGRES_PASSWORD" to "kotsql", "POSTGRES_DB" to "kotsql"))
            // The image's certificate names localhost, which is where the tests reach the server.
            withCommand(
                "postgres", "-c", "ssl=on",
                "-c", "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                "-c", "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key",
            )
            withExposedPorts(5432)
            // Once for the initialization, once for the server that stays.
            waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", times = 2))
            withStartupTimeout(3.minutes)
        }
        val mysql = GenericContainer(MYSQL) {
            withCommand("--mysql-native-password=ON")
            withEnv(mapOf("MYSQL_ROOT_PASSWORD" to "kotsql", "MYSQL_DATABASE" to "kotsql"))
            withExposedPorts(3306)
            waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*"))
            withStartupTimeout(3.minutes)
        }
        postgres.start()
        mysql.start()
        configurePostgres(postgres)
        // A standby only once the primary lets it replicate.
        val standby = GenericContainer(POSTGRES) {
            withNetwork(network)
            withUser("postgres")
            withEnv("PGPASSWORD", "kotsql")
            withEntrypoint(
                "sh", "-c",
                """
                until pg_basebackup -h postgres -U kotsql -D /tmp/standby -R -X stream; do rm -rf /tmp/standby; sleep 1; done
                chmod 700 /tmp/standby
                exec postgres -D /tmp/standby -c ssl=off
                """.trimIndent(),
            )
            withExposedPorts(5432)
            waitingFor(Wait.forLogMessage(".*ready to accept read-only connections.*"))
            withStartupTimeout(3.minutes)
        }
        standby.start()
        mysql.run("mysql", "-uroot", "-pkotsql", "-e", "CREATE USER kotsql_x509 IDENTIFIED BY 'kotsql' REQUIRE X509; GRANT ALL ON kotsql.* TO kotsql_x509")

        val certs = Path(SystemTemporaryDirectory, "kotsql-certs-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(certs)
        fun save(name: String, content: ByteArray) {
            SystemFileSystem.sink(Path(certs, name)).buffered().use { it.write(content) }
        }
        save("root.pem", postgres.copyFileFromContainer("/etc/ssl/certs/ssl-cert-snakeoil.pem"))
        save("other.pem", postgres.run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "/dev/null", "-subj", "/CN=unrelated", "-days", "1").encodeToByteArray())
        for (file in listOf("rsa.crt", "rsa.key", "ec.crt", "ec.key")) save(file, postgres.copyFileFromContainer("/tmp/pki/$file"))
        save("mysql.crt", mysql.copyFileFromContainer("/var/lib/mysql/client-cert.pem"))
        save("mysql.key", mysql.copyFileFromContainer("/var/lib/mysql/client-key.pem"))

        val host = postgres.host
        val port = postgres.getMappedPort(5432)
        return buildMap {
            put("KOTSQL_TEST_URL", "postgresql://kotsql:kotsql@$host:$port/kotsql?sslmode=disable")
            put("KOTSQL_TEST_SCRAM_URL", "postgresql://kotsql:kotsql@$host:$port/kotsql")
            put("KOTSQL_TEST_MD5_ADDRESS", "$host:$port")
            put("KOTSQL_TEST_MYSQL_HOST_PORT", "${mysql.host}:${mysql.getMappedPort(3306)}")
            put("KOTSQL_TEST_PRIMARY", "$host:$port")
            put("KOTSQL_TEST_STANDBY", "${standby.host}:${standby.getMappedPort(5432)}")
            put("KOTSQL_TEST_CERTS", certs.toString())
            // The TLS tests reach the server as localhost, the name in its certificate.
            put("KOTSQL_TEST_TLS_PORT", "$port")
            put("KOTSQL_TEST_TLS_ROOT", Path(certs, "root.pem").toString())
            put("KOTSQL_TEST_TLS_OTHER_ROOT", Path(certs, "other.pem").toString())
            // Reaches the same server under a name its certificate doesn't have.
            put("KOTSQL_TEST_TLS_OTHER_HOST", "127.0.0.1")
            if (platformNormalizes) put("KOTSQL_TEST_NFKC", "1")
        }
    }

    /** The roles, authentication methods and client certificate authority that the tests expect. */
    private suspend fun configurePostgres(postgres: GenericContainer) {
        // md5 for these roles from anywhere, ahead of the image's rules, and replication for the standby.
        postgres.run(
            "sh", "-c",
            "sed -i '1i host all kotsql_md5,kotsql_мд5 all md5' \"\$PGDATA/pg_hba.conf\" && " +
                "sed -i '1i host replication all all scram-sha-256' \"\$PGDATA/pg_hba.conf\"",
            user = "postgres",
        )
        postgres.run(
            "psql", "-q", "-U", "kotsql", "-d", "kotsql", "-v", "ON_ERROR_STOP=1",
            env = mapOf("PGCLIENTENCODING" to "UTF8"),
            stdin = """
                -- As typed on something else than what the tests use: a no-break space and a soft hyphen, and a decomposed letter.
                CREATE ROLE kotsql_prep LOGIN PASSWORD U&'pa\00ADss\00A0word';
                CREATE ROLE kotsql_nfkc LOGIN PASSWORD U&'caf\00E9';
                SET password_encryption = 'md5';
                CREATE ROLE kotsql_md5 LOGIN PASSWORD 'kotsql';
                CREATE ROLE "kotsql_мд5" LOGIN PASSWORD 'пароль';
                CREATE ROLE kotsql_cert LOGIN;
                ALTER SYSTEM SET ssl_ca_file = '/tmp/pki/ca.crt';
            """.trimIndent(),
        )
        // A CA of its own for client certificates, one with an RSA key and one with an EC key.
        postgres.run(
            "sh", "-euc",
            """
            mkdir -p /tmp/pki && cd /tmp/pki
            openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -subj /CN=kotsql-test-ca -days 2 2>/dev/null
            openssl req -newkey rsa:2048 -nodes -keyout rsa.key -out rsa.csr -subj /CN=kotsql_cert 2>/dev/null
            openssl x509 -req -in rsa.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out rsa.crt -days 2 2>/dev/null
            openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout ec.key -out ec.csr -subj /CN=kotsql_cert 2>/dev/null
            openssl x509 -req -in ec.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out ec.crt -days 2 2>/dev/null
            sed -i "1i hostssl all kotsql_cert all cert" "${'$'}PGDATA/pg_hba.conf"
            """.trimIndent(),
            user = "postgres",
        )
        postgres.run("psql", "-q", "-U", "kotsql", "-d", "kotsql", "-c", "SELECT pg_reload_conf()")
    }

    /** Runs a command that must succeed, and returns its output. */
    private suspend fun GenericContainer.run(
        vararg command: String,
        user: String? = null,
        env: Map<String, String> = emptyMap(),
        stdin: String? = null,
    ): String {
        val result = if (stdin != null) {
            execInContainer(command.toList(), stdin = stdin, user = user, env = env)
        } else {
            execInContainer(command.toList(), user = user, env = env)
        }
        check(result.exitCode == 0) { "${command.joinToString(" ")} failed with ${result.exitCode}: ${result.stderr}" }
        return result.stdout
    }
}
