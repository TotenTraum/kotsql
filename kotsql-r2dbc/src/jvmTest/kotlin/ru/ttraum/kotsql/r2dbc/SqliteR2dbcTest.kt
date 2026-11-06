package ru.ttraum.kotsql.r2dbc

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class SqliteR2dbcTest {
    private val factory = ConnectionFactories.get(
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "kotsql")
            .option(ConnectionFactoryOptions.PROTOCOL, "sqlite")
            .option(ConnectionFactoryOptions.DATABASE, ":memory:")
            .build(),
    )

    @Test fun statementsAndATransaction() {
        val connection = Mono.from(factory.create()).block()!!
        try {
            assertEquals("SQLite", connection.metadata.databaseProductName)
            fun update(sql: String) = Flux.from(connection.createStatement(sql).execute()).concatMap { it.rowsUpdated }.blockLast()
            update("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            assertEquals(1, Flux.from(connection.createStatement("INSERT INTO t (v) VALUES (?1)").bind(0, "a").execute()).concatMap { it.rowsUpdated }.blockLast())
            Mono.from(connection.beginTransaction()).block()
            update("INSERT INTO t (v) VALUES ('rolled back')")
            Mono.from(connection.rollbackTransaction()).block()
            val values = Flux.from(connection.createStatement("SELECT v FROM t WHERE id > ?1").bind(0, 0).execute())
                .concatMap { result -> result.map { row, _ -> row.get(0, String::class.java)!! } }
                .collectList().block()
            assertEquals(listOf("a"), values)
        } finally {
            Mono.from(connection.close()).block()
        }
    }
}
