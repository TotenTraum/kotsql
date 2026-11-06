package ru.ttraum.kotsql.compat;

import static org.junit.jupiter.api.Assertions.*;

import ru.ttraum.kotsql.compat.CompatApp.Authors;
import ru.ttraum.kotsql.compat.CompatApp.Books;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One application, with the same code for each database. */
abstract class AbstractCompatTest {
    @Autowired Authors authors;
    @Autowired Books books;
    @Autowired JdbcTemplate jdbc;
    @Autowired NamedParameterJdbcTemplate named;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;

    TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    @BeforeEach void clean() {
        books.deleteAllInBatch();
        authors.deleteAllInBatch();
    }

    private Book fullBook(String title) {
        Book b = new Book(title);
        b.price = new BigDecimal("19.99"); b.pages = 300; b.sold = 5_000_000_000L; b.rating = 4.5; b.weight = 0.25f; b.edition = 2;
        b.inPrint = true; b.published = LocalDate.of(2024, 2, 29); b.openingAt = LocalTime.of(9, 30, 15);
        b.updatedLocal = LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_000_000);
        b.updatedInstant = Instant.parse("2024-06-01T12:00:00.5Z");
        b.updatedOffset = OffsetDateTime.of(2024, 6, 1, 15, 0, 0, 0, ZoneOffset.ofHours(3));
        b.ref = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"); b.genre = Book.Genre.ESSAY; b.genreOrdinal = Book.Genre.ESSAY;
        b.cover = new byte[] {0, 1, -1, 127}; b.text = "дата 😀 ' \" \\ ?".repeat(100); b.attributes = Map.of("a", 1, "b", "two");
        return b;
    }

    @Test void schemaValidatesAgainstMappings() {
        // ddl-auto=validate on a second context goes through DatabaseMetaData for all tables and columns.
        assertTrue(new org.springframework.boot.builder.SpringApplicationBuilder(CompatApp.class)
            .web(org.springframework.boot.WebApplicationType.NONE)
            .properties(validationProperties())
            .run().isRunning());
    }

    abstract java.util.Map<String, Object> validationProperties();

    @Test void roundTripsEveryType() {
        Long id = tx().execute(s -> {
            Author a = new Author("Leskov");
            Book b = a.add(fullBook("Lefty"));
            authors.save(a);
            return null;
        }) == null ? books.findAll().get(0).id : null;
        Book r = books.findById(id).orElseThrow();
        Book e = fullBook("Lefty");
        assertEquals(0, e.price.compareTo(r.price)); assertEquals(e.pages, r.pages); assertEquals(e.sold, r.sold); assertEquals(e.rating, r.rating);
        assertEquals(e.weight, r.weight); assertEquals(e.edition, r.edition); assertEquals(e.inPrint, r.inPrint); assertEquals(e.published, r.published);
        assertEquals(e.openingAt, r.openingAt);
        assertEquals(e.updatedLocal.truncatedTo(ChronoUnit.SECONDS), r.updatedLocal.truncatedTo(ChronoUnit.SECONDS));
        assertEquals(e.updatedInstant.truncatedTo(ChronoUnit.SECONDS), r.updatedInstant.truncatedTo(ChronoUnit.SECONDS));
        assertEquals(e.updatedOffset.toInstant(), r.updatedOffset.toInstant());
        assertEquals(e.ref, r.ref); assertEquals(e.genre, r.genre); assertEquals(e.genreOrdinal, r.genreOrdinal);
        assertArrayEquals(e.cover, r.cover); assertEquals(e.text, r.text); assertEquals(e.attributes, r.attributes);
    }

    /** SQL giving a column's value as UTC text, whatever the zones of the session and of the column. */
    abstract String utcText(String column);

    /**
     * Writing and reading back in one JVM would hide a shift that cancels out, so this asks the server itself what was stored.
     * It matters when the suite runs with another default zone: `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Tokyo`.
     */
    @Test void storedInstantsAreRightWhateverTheJvmZone() {
        String asked = System.getenv("JAVA_TOOL_OPTIONS");
        if (asked != null && asked.contains("user.timezone=")) {
            assertTrue(asked.endsWith(java.util.TimeZone.getDefault().getID()), java.util.TimeZone.getDefault().getID());
        }
        tx().executeWithoutResult(s -> {
            Author a = new Author("Zoned");
            a.add(fullBook("Zoned"));
            authors.save(a);
        });
        assertTrue(jdbc.queryForObject("select " + utcText("updated_instant") + " from books", String.class).startsWith("2024-06-01 12:00:00"));
        assertTrue(jdbc.queryForObject("select " + utcText("updated_offset") + " from books", String.class).startsWith("2024-06-01 12:00:00"));
        // A local date and time has no zone: the wall clock is kept as it is.
        assertTrue(jdbc.queryForObject("select cast(updated_local as char(19)) from books", String.class).startsWith("2024-01-02 03:04:05"));
        assertEquals("2024-02-29", jdbc.queryForObject("select cast(published as char(10)) from books", String.class));
    }

    @Test void nullsInEveryColumn() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Nobody");
            a.add(new Book("Empty"));
            authors.save(a);
        });
        Book r = books.findAll().get(0);
        assertNull(r.price); assertNull(r.pages); assertNull(r.sold); assertNull(r.rating); assertNull(r.inPrint); assertNull(r.published);
        assertNull(r.updatedInstant); assertNull(r.ref); assertNull(r.genre); assertNull(r.cover); assertNull(r.text); assertNull(r.attributes);
    }

    @Test void relationsLazyLoadingAndFetchJoins() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Leskov");
            a.add(new Book("One")); a.add(new Book("Two")); a.add(new Book("Three"));
            authors.save(a);
        });
        Author plain = authors.findByName("Leskov").orElseThrow();
        assertFalse(Hibernate.isInitialized(plain.books));
        Author joined = authors.withBooks("Leskov").orElseThrow();
        assertTrue(Hibernate.isInitialized(joined.books));
        assertEquals(3, joined.books.size());
        tx().executeWithoutResult(s -> {
            Author again = authors.findByName("Leskov").orElseThrow();
            assertEquals(3, again.books.size());
            again.books.remove(0); // orphan removal
        });
        assertEquals(2, books.count());
    }

    @Test void derivedQueriesProjectionsPagingSorting() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Leskov");
            for (int i = 1; i <= 25; i++) {
                Book b = a.add(new Book("Title " + i));
                b.price = BigDecimal.valueOf(i); b.pages = i * 10; b.inPrint = i % 2 == 0; b.genre = i % 5 == 0 ? Book.Genre.ESSAY : Book.Genre.NOVEL;
            }
            authors.save(a);
        });
        assertEquals(5, books.findByPriceGreaterThanOrderByPriceDesc(BigDecimal.valueOf(20)).size());
        assertEquals(1, books.findByTitleContainingIgnoreCase("TITLE 25").size());
        assertEquals(12, books.findByInPrint(true).size());
        assertEquals(5, books.countByGenre(Book.Genre.ESSAY));
        var page = books.findByAuthorName("Leskov", PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "price")));
        assertEquals(25, page.getTotalElements()); assertEquals(3, page.getTotalPages()); assertEquals(10, page.getContent().size());
        assertEquals(0, BigDecimal.valueOf(15).compareTo(page.getContent().get(0).price));
        assertEquals(List.of("Title 24", "Title 25"), books.titlesWithMorePagesThan(230));
        List<Object[]> statistics = books.statistics();
        assertEquals(2, statistics.size());
        assertEquals(0, BigDecimal.valueOf(75).compareTo(new BigDecimal(statistics.stream().filter(r -> r[0] == Book.Genre.ESSAY).findFirst().orElseThrow()[2].toString())));
        int updated = tx().execute(s -> books.addPage("Title%"));
        assertEquals(25, updated);
    }

    @Test void optimisticLocking() {
        Long id = authors.save(new Author("Versioned")).id;
        Author first = authors.findById(id).orElseThrow();
        Author second = authors.findById(id).orElseThrow();
        first.name = "A"; authors.save(first);
        second.name = "B";
        assertThrows(OptimisticLockingFailureException.class, () -> authors.save(second));
    }

    @Test void pessimisticLockingAndRollback() {
        Long id = authors.save(new Author("Locked")).id;
        tx().executeWithoutResult(s -> {
            Author a = authors.lockById(id).orElseThrow();
            a.name = "Changed";
            authors.saveAndFlush(a);
            s.setRollbackOnly();
        });
        assertEquals("Locked", authors.findById(id).orElseThrow().name);
    }

    @Test void translatesErrors() {
        authors.save(new Author("Unique"));
        var violation = assertThrows(DataIntegrityViolationException.class, () -> authors.saveAndFlush(new Author("Unique")));
        // What an application wants for "this email is taken", through Spring's and Hibernate's wrapping, and from Java.
        assertEquals(ru.ttraum.kotsql.ConstraintViolation.UNIQUE, ru.ttraum.kotsql.SqlErrors.sqlErrorDetails(violation).getViolation());
        assertNotNull(ru.ttraum.kotsql.SqlErrors.sqlErrorDetails(violation).getConstraint());
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("insert into authors(name, version) values ('Unique', 0)"));
        assertThrows(BadSqlGrammarException.class, () -> jdbc.queryForList("select * from no_such_table"));
        // A failure leaves the connection fit for the pool.
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("select count(*) from authors", Integer.class));
    }

    @Test void hibernateBatchInsertsAndUpdates() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Batcher");
            authors.save(a);
            for (int i = 0; i < 500; i++) a.add(new Book("B" + i));
        });
        assertEquals(500, books.count());
        tx().executeWithoutResult(s -> books.findAll().forEach(b -> b.pages = 1));
        assertEquals(Integer.valueOf(500), jdbc.queryForObject("select count(*) from books where pages = 1", Integer.class));
    }

    @Test void readOnlyAndIsolationTransactions() {
        TransactionTemplate readOnly = tx();
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        long count = readOnly.execute(s -> authors.count());
        assertEquals(0, count);
        TransactionTemplate serializable = tx();
        serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        serializable.executeWithoutResult(s -> authors.save(new Author("Serial")));
        assertEquals(1, authors.count());
    }

    @Test void nestedTransactionsWithSavepoints() {
        var jdbcTx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        TransactionTemplate outer = new TransactionTemplate(jdbcTx);
        TransactionTemplate nested = new TransactionTemplate(jdbcTx);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        outer.executeWithoutResult(s -> {
            jdbc.update("insert into authors(name, version) values ('Outer', 0)");
            nested.executeWithoutResult(n -> {
                jdbc.update("insert into authors(name, version) values ('Inner', 0)");
                n.setRollbackOnly();
            });
        });
        assertEquals(List.of("Outer"), jdbc.queryForList("select name from authors", String.class));
    }

    @Test void jdbcTemplateEverything() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement("insert into authors(name, version) values (?, 0)", Statement.RETURN_GENERATED_KEYS);
            p.setString(1, "Keyed");
            return p;
        }, keys);
        assertTrue(generatedId(keys) > 0);
        keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement("insert into authors(name, version) values (?, 0)", new String[] {"id"});
            p.setString(1, "Keyed by name");
            return p;
        }, keys);
        assertTrue(generatedId(keys) > 0);
        assertEquals("Keyed", jdbc.queryForObject("select name from authors where name = ?", String.class, "Keyed"));
        Map<String, Object> row = jdbc.queryForMap("select name, version from authors where name = ?", "Keyed");
        assertEquals("Keyed", row.get("name"));
        assertEquals(2, jdbc.queryForList("select * from authors").size());
        int[] batch = jdbc.batchUpdate("insert into authors(name, version) values (?, 0)", List.of(new Object[] {"X"}, new Object[] {"Y"}));
        assertEquals(2, batch.length);
        assertEquals(1, named.update("update authors set version = :v where name = :n", new MapSqlParameterSource("v", 5).addValue("n", "X")));
        assertEquals(Integer.valueOf(5), named.queryForObject("select version from authors where name = :n", Map.of("n", "X"), Integer.class));
        assertEquals(1, named.queryForList("select name from authors where name in (:names) and version = 5", Map.of("names", List.of("X", "Y")), String.class).size());
        // RowSet and result set metadata, as tools use them.
        var rowSet = jdbc.queryForRowSet("select name as label, version from authors order by name");
        assertEquals("label", rowSet.getMetaData().getColumnLabel(1).toLowerCase());
        assertTrue(rowSet.next());
        jdbc.setFetchSize(10); jdbc.setMaxRows(3); jdbc.setQueryTimeout(5);
        assertEquals(3, jdbc.queryForList("select name from authors").size());
        jdbc.setFetchSize(-1); jdbc.setMaxRows(-1); jdbc.setQueryTimeout(-1);
    }

    private long generatedId(GeneratedKeyHolder keys) {
        // With PostgreSQL's RETURNING * these are all the columns, in the table's order; MySQL has just the generated value.
        Map<String, Object> m = keys.getKeyList().get(0);
        Object id = m.containsKey("id") ? m.get("id") : m.values().iterator().next();
        return ((Number) id).longValue();
    }

    @Test void entityManagerNativeAndCriteria() {
        authors.save(new Author("Crit"));
        assertEquals(1L, ((Number) entities.createNativeQuery("select count(*) from authors").getSingleResult()).longValue());
        var cb = entities.getCriteriaBuilder();
        var q = cb.createQuery(Author.class);
        var root = q.from(Author.class);
        q.select(root).where(cb.like(cb.lower(root.get("name")), "cr%"));
        assertEquals(1, entities.createQuery(q).getResultList().size());
        assertNotNull(entities.unwrap(Session.class));
    }

    private void fiveBooks() {
        tx().executeWithoutResult(s -> {
            Author a = new Author("Scroller");
            for (int i = 1; i <= 5; i++) { Book b = a.add(new Book("S" + i)); b.pages = i; }
            authors.save(a);
        });
    }

    @Test void streamingResults() {
        fiveBooks();
        long sum = tx().execute(s -> {
            try (var stream = entities.createQuery("select b from Book b", Book.class).setHint("org.hibernate.fetchSize", 2).getResultStream()) {
                return stream.mapToLong(b -> b.pages).sum();
            }
        });
        assertEquals(15, sum);
    }

    @Test void forwardOnlyScroll() {
        fiveBooks();
        int rows = tx().execute(s -> {
            try (var scroll = entities.unwrap(Session.class).createQuery("select b.title from Book b order by b.title", String.class).scroll(org.hibernate.ScrollMode.FORWARD_ONLY)) {
                int n = 0;
                while (scroll.next()) n++;
                return n;
            }
        });
        assertEquals(5, rows);
    }

    /** Hibernate's scroll() with no arguments asks for a scrollable result set. */
    @Test void defaultScroll() {
        fiveBooks();
        String last = tx().execute(s -> {
            try (var scroll = entities.unwrap(Session.class).createQuery("select b.title from Book b order by b.title", String.class).scroll()) {
                assertTrue(scroll.last());
                String title = scroll.get();
                assertTrue(scroll.first());
                assertEquals("S1", scroll.get());
                return title;
            }
        });
        assertEquals("S5", last);
    }

    @Test void bigIntegerAndCharacterColumns() {
        jdbc.execute("drop table if exists odd");
        jdbc.execute("create table odd (a char(3), b decimal(30,0), c real, d timestamp null)");
        jdbc.update("insert into odd values (?, ?, ?, ?)", "abc", new java.math.BigInteger("123456789012345678901234567890"), 1.5f, java.sql.Timestamp.valueOf("2024-01-02 03:04:05"));
        Map<String, Object> row = jdbc.queryForMap("select * from odd");
        assertEquals("abc", row.get("a")); assertEquals(new BigDecimal("123456789012345678901234567890"), row.get("b"));
        assertEquals(java.sql.Timestamp.valueOf("2024-01-02 03:04:05"), row.get("d"));
    }

    @Test void concurrentRequestsThroughThePool() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            tasks.add(pool.submit(() -> tx().executeWithoutResult(s -> {
                Author a = new Author("Concurrent " + n);
                a.add(new Book("Book " + n));
                authors.save(a);
            })));
        }
        for (var t : tasks) t.get();
        pool.shutdown();
        assertEquals(200, books.count());
    }
}
