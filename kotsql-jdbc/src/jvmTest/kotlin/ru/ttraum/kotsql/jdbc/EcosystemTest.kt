package ru.ttraum.kotsql.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real tools are the closest thing JDBC has to a compatibility kit. */
class EcosystemTest {
    private val dataSource = KotsqlDataSource(Pg.url, Pg.user, Pg.password)

    @Test fun hikariPoolUnderLoad() {
        val config = HikariConfig().apply {
            jdbcUrl = Pg.url; username = Pg.user; password = Pg.container.password
            driverClassName = KotsqlJdbcDriver::class.java.name
            maximumPoolSize = 4
        }
        HikariDataSource(config).use { pool ->
            pool.connection.use { c -> c.createStatement().use { it.execute("DROP TABLE IF EXISTS h; CREATE TABLE h(n int)") } }
            val threads = Executors.newFixedThreadPool(16)
            val tasks = List(400) { i ->
                threads.submit {
                    pool.connection.use { c ->
                        c.autoCommit = false
                        c.prepareStatement("INSERT INTO h VALUES (?)").use { s -> s.setInt(1, i); s.executeUpdate() }
                        if (i % 2 == 0) c.commit() else c.rollback()
                    }
                }
            }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            threads.shutdown()
            pool.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM h").use { r -> r.next(); assertEquals(200, r.getInt(1)) } } }
        }
    }

    @Test fun flywayMigrations() {
        val flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").schemas("fly").load()
        val result = flyway.migrate()
        assertEquals(2, result.migrationsExecuted)
        assertEquals(0, flyway.migrate().migrationsExecuted) // the history table is read back
        dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT fly.inc(41), (SELECT count(*) FROM fly.author)").use { r ->
                r.next(); assertEquals(42, r.getInt(1)); assertEquals(1, r.getInt(2))
            } }
        }
    }

    @Test fun jooqQueries() {
        dataSource.connection.use { c ->
            val db = DSL.using(c, SQLDialect.POSTGRES)
            db.execute("DROP TABLE IF EXISTS jq")
            db.createTable("jq").column("id", org.jooq.impl.SQLDataType.BIGINT.identity(true))
                .column("name", org.jooq.impl.SQLDataType.VARCHAR(50)).column("at", org.jooq.impl.SQLDataType.LOCALDATETIME)
                .primaryKey("id").execute()
            val name = DSL.field("name", String::class.java)
            val id = DSL.field("id", Long::class.javaObjectType)
            db.insertInto(DSL.table("jq")).columns(name).values("a").values("b").execute()
            assertEquals(listOf("a", "b"), db.select(name).from("jq").orderBy(id).fetch(name))
            val returned = db.insertInto(DSL.table("jq")).columns(name).values("c").returningResult(id).fetchOne()
            assertEquals(3L, returned!!.value1())
            db.transaction { tx -> tx.dsl().deleteFrom(DSL.table("jq")).where(name.eq("a")).execute() }
            assertEquals(2, db.fetchCount(DSL.table("jq")))
            assertTrue(db.meta().tables.any { it.name == "jq" })
        }
    }
}
