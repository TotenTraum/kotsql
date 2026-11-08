package ru.ttraum.kotsql.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The same JDBC code on one SQLite file through each driver, with the same settings: WAL and synchronous=NORMAL, which an
 * application writing to SQLite from several connections would have. {@code kotsql-nocache} is kotsql without its cache of
 * prepared statements, to show what that does.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class SqliteBenchmark {
    static final int ROWS = 100_000;
    private static final String SELECT_ONE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id = ?";
    private static final String SELECT_RANGE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id > ? ORDER BY id LIMIT 1000";
    private static final String SELECT_ALL = "SELECT id, name, amount, created, active, tag FROM bench_rows";
    private static final String INSERT = "INSERT INTO bench_insert (id, name, amount, created) VALUES (?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE bench_counter SET n = n + 1 WHERE id = ?";
    private static final int BATCH = 1000;
    private static final Path FILE = Path.of(System.getProperty("java.io.tmpdir"), "kotsql-bench.db");

    static Connection connect(String driver) throws SQLException {
        Properties props = new Properties();
        return switch (driver) {
            case "xerial" -> {
                props.setProperty("journal_mode", "WAL");
                props.setProperty("synchronous", "NORMAL");
                props.setProperty("busy_timeout", "5000");
                yield DriverManager.getConnection("jdbc:sqlite:" + FILE, props);
            }
            case "kotsql" -> DriverManager.getConnection("jdbc:kotsql:sqlite:///" + FILE + "?journal_mode=WAL&synchronous=NORMAL", props);
            case "kotsql-nocache" -> DriverManager.getConnection("jdbc:kotsql:sqlite:///" + FILE + "?journal_mode=WAL&synchronous=NORMAL&statement_cache_size=0", props);
            default -> throw new IllegalArgumentException(driver);
        };
    }

    @State(Scope.Benchmark)
    public static class Database {
        /** Always through xerial, so that neither driver's own code decides what the other one is measured on. */
        @Setup(Level.Trial)
        public void seed() throws Exception {
            Files.deleteIfExists(FILE);
            try (Connection c = connect("xerial"); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE bench_rows (id INTEGER PRIMARY KEY, name TEXT NOT NULL, amount NUMERIC NOT NULL, created TEXT NOT NULL, active INTEGER NOT NULL, tag TEXT NOT NULL)");
                s.execute("""
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < %d)
                    INSERT INTO bench_rows SELECT i, 'name-' || i, i * 1.25, datetime('2024-01-01', '+' || i || ' minutes'), i %% 2 = 0,
                        lower(hex(randomblob(16))) FROM n""".formatted(ROWS));
                s.execute("CREATE TABLE bench_counter (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)");
                s.execute("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 64) INSERT INTO bench_counter SELECT i, 0 FROM n");
                s.execute("CREATE TABLE bench_insert (id INTEGER, name TEXT, amount NUMERIC, created TEXT)");
                s.execute("ANALYZE");
            }
        }
    }

    @State(Scope.Thread)
    public static class Session {
        @Param({"xerial", "kotsql", "kotsql-nocache"})
        public String driver;

        Connection connection;
        long nextId;

        @Setup(Level.Trial)
        public void open(Database seeded) throws SQLException {
            connection = connect(driver);
        }

        /** Keeps the table small, however many rows the previous iteration inserted. */
        @Setup(Level.Iteration)
        public void reset() throws SQLException {
            try (Statement s = connection.createStatement()) {
                s.execute("DELETE FROM bench_insert");
            }
        }

        @TearDown(Level.Trial)
        public void close() throws SQLException {
            connection.close();
        }

        long randomId(int bound) {
            return ThreadLocalRandom.current().nextLong(1, bound);
        }
    }

    private static void consume(ResultSet r, Blackhole bh) throws SQLException {
        bh.consume(r.getLong(1));
        bh.consume(r.getString(2));
        bh.consume(r.getBigDecimal(3));
        bh.consume(r.getString(4));
        bh.consume(r.getBoolean(5));
        bh.consume(r.getString(6));
    }

    private static void selectById(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(SELECT_ONE)) {
            p.setLong(1, s.randomId(ROWS));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
    }

    /** Opening the file and setting it up. */
    @Benchmark
    public void connect(Session s, Blackhole bh) throws SQLException {
        try (Connection c = connect(s.driver)) {
            bh.consume(c);
        }
    }

    /** A primary key lookup. With no network, this is mostly the driver's own cost. */
    @Benchmark
    public void selectOne(Session s, Blackhole bh) throws SQLException {
        selectById(s, bh);
    }

    /** The same, on 8 connections at once, which WAL lets read together. */
    @Benchmark
    @Threads(8)
    public void selectOneConcurrent(Session s, Blackhole bh) throws SQLException {
        selectById(s, bh);
    }

    /** Decoding speed: 1000 rows. Score is queries, not rows. */
    @Benchmark
    public void select1000Rows(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(SELECT_RANGE)) {
            p.setLong(1, s.randomId(ROWS - 1000));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
    }

    /** All 100k rows. */
    @Benchmark
    public void streamAllRows(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(SELECT_ALL)) {
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
    }

    private static void bindInsert(PreparedStatement p, long id) throws SQLException {
        p.setLong(1, id);
        p.setString(2, "inserted");
        p.setBigDecimal(3, java.math.BigDecimal.TEN);
        p.setString(4, "2024-01-01 12:00:00");
    }

    /** One row in its own transaction, as autocommit has it. */
    @Benchmark
    public void insertOne(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(INSERT)) {
            bindInsert(p, ++s.nextId);
            bh.consume(p.executeUpdate());
        }
    }

    /** 1000 rows with addBatch. Score is batches, not rows. */
    @Benchmark
    public void insertBatch1000(Session s, Blackhole bh) throws SQLException {
        Connection c = s.connection;
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement(INSERT)) {
            for (int i = 0; i < BATCH; i++) {
                bindInsert(p, ++s.nextId);
                p.addBatch();
            }
            bh.consume(p.executeBatch());
        }
        c.commit();
        c.setAutoCommit(true);
    }

    /** Read, write, commit. */
    @Benchmark
    public void transaction(Session s, Blackhole bh) throws SQLException {
        Connection c = s.connection;
        c.setAutoCommit(false);
        selectById(s, bh);
        try (PreparedStatement p = c.prepareStatement(UPDATE)) {
            p.setInt(1, (int) s.randomId(64));
            bh.consume(p.executeUpdate());
        }
        c.commit();
        c.setAutoCommit(true);
    }
}
