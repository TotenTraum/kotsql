package ru.ttraum.kotsql.compat;

import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** One server of each kind per test JVM, with no official driver anywhere on the classpath. */
public final class Databases {
    private static GenericContainer<?> postgres;
    private static GenericContainer<?> mysql;

    private Databases() {}

    public static synchronized String postgresUrl() {
        if (postgres == null) {
            postgres = new GenericContainer<>("postgres:17")
                .withEnv(Map.of("POSTGRES_USER", "app", "POSTGRES_PASSWORD", "app", "POSTGRES_DB", "app"))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2).withStartupTimeout(Duration.ofMinutes(2)));
            postgres.start();
        }
        return "jdbc:kotsql:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/app";
    }

    public static synchronized String mysqlUrl() {
        if (mysql == null) {
            mysql = new GenericContainer<>("mysql:8.4")
                .withEnv(Map.of("MYSQL_ROOT_PASSWORD", "app", "MYSQL_USER", "app", "MYSQL_PASSWORD", "app", "MYSQL_DATABASE", "app"))
                .withExposedPorts(3306)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
            mysql.start();
        }
        return "jdbc:kotsql:mysql://" + mysql.getHost() + ":" + mysql.getFirstMappedPort() + "/app";
    }
}
