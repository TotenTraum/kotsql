package ru.ttraum.kotsql.compat;

import ru.ttraum.kotsql.jdbc.KotsqlJdbcDriver;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.hibernate.ScrollMode;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same application on a file, with Hibernate's community dialect for SQLite. */
@SpringBootTest(classes = CompatApp.class, properties = {"spring.jpa.hibernate.ddl-auto=create", "spring.jpa.properties.hibernate.jdbc.batch_size=50",
    "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect"})
class SqliteCompatTest extends AbstractCompatTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Databases::sqliteUrl);
        r.add("spring.datasource.driver-class-name", KotsqlJdbcDriver.class::getName);
    }

    /** Hibernate keeps these as text in UTC. */
    @Override String utcText(String column) { return column; }

    @Override Map<String, Object> validationProperties() {
        return Map.of("spring.datasource.url", Databases.sqliteUrl(), "spring.datasource.driver-class-name", KotsqlJdbcDriver.class.getName(),
            "spring.jpa.database-platform", "org.hibernate.community.dialect.SQLiteDialect", "spring.jpa.hibernate.ddl-auto", "validate");
    }

    /**
     * Hibernate's own DDL makes an identity column `integer`, as SQLite requires of one, and then expects `bigint` of it when it
     * validates, whatever the driver says. So this only checks that the metadata describes the tables Hibernate made.
     */
    @Test @Override void schemaValidatesAgainstMappings() {
        try (var c = jdbc.getDataSource().getConnection(); var r = c.getMetaData().getColumns(null, null, "books", "%")) {
            int n = 0;
            while (r.next()) n++;
            assertEquals(21, n);
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }

    /** A NUMERIC column of SQLite keeps a value of more than 15 digits as REAL, losing the rest. */
    @Test @Override void bigIntegerAndCharacterColumns() {
        jdbc.execute("drop table if exists odd");
        jdbc.execute("create table odd (a char(3), b decimal(30,0), c real, d timestamp null)");
        jdbc.update("insert into odd values (?, ?, ?, ?)", "abc", new java.math.BigInteger("123456789012345"), 1.5f, java.sql.Timestamp.valueOf("2024-01-02 03:04:05"));
        Map<String, Object> row = jdbc.queryForMap("select * from odd");
        assertEquals("abc", row.get("a"));
        assertEquals(123456789012345L, ((Number) row.get("b")).longValue());
        assertEquals(1.5, ((Number) row.get("c")).doubleValue());
        assertEquals(java.sql.Timestamp.valueOf("2024-01-02 03:04:05"), jdbc.queryForObject("select d from odd", java.sql.Timestamp.class));
    }

    /** Hibernate's dialect for SQLite scrolls forward by default, so this asks for a scrollable result set itself. */
    @Test @Override void defaultScroll() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Scroller");
            for (int i = 1; i <= 5; i++) a.add(new Book("S" + i));
            authors.save(a);
        });
        String last = tx().execute(s -> {
            try (var scroll = entities.unwrap(Session.class).createQuery("select b.title from Book b order by b.title", String.class).scroll(ScrollMode.SCROLL_INSENSITIVE)) {
                assertTrue(scroll.last());
                String title = scroll.get();
                assertTrue(scroll.first());
                assertEquals("S1", scroll.get());
                return title;
            }
        });
        assertEquals("S5", last);
    }
}
