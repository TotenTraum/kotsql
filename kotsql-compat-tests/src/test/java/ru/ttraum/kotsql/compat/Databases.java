package ru.ttraum.kotsql.compat;

import java.time.Duration;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** One server of each kind per test JVM, with no official driver anywhere on the classpath. */
public final class Databases {
    private static GenericContainer<?> postgres;
    private static GenericContainer<?> mysql;
    private static String sqlite;

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

    /** A file rather than memory, as each connection of the pool would otherwise have a database of its own. */
    public static synchronized String sqliteUrl() {
        if (sqlite == null) {
            try {
                java.nio.file.Path file = java.nio.file.Files.createTempFile("kotsql-compat", ".db");
                file.toFile().deleteOnExit();
                sqlite = "jdbc:kotsql:sqlite:///" + file + "?journal_mode=WAL&foreign_keys=on";
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return sqlite;
    }
}
