package dev.ksql.jdbc

import org.testcontainers.postgresql.PostgreSQLContainer

/** One container per test JVM. */
object Pg {
    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:17")
            .withCommand(
                "postgres", "-c", "ssl=on",
                "-c", "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                "-c", "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key",
            )
            .also { it.start() }
    }
    val hostPort get() = "${container.host}:${container.firstMappedPort}"
    val url get() = "jdbc:ksql:postgresql://$hostPort/${container.databaseName}"
    val user get() = container.username
    val password get() = container.password
}
