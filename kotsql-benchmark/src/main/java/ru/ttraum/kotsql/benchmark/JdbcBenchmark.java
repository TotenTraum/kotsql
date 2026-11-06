package ru.ttraum.kotsql.benchmark;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
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
 * The same JDBC code against each driver, with default settings, written the way applications usually write it: a
 * statement is prepared for each execution, leaving any reuse to the driver.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class JdbcBenchmark {
    private static final String SELECT_ONE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id = ?";
    private static final String SELECT_RANGE = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id > ? ORDER BY id LIMIT 1000";
    private static final String SELECT_ALL = "SELECT id, name, amount, created, active, tag FROM bench_rows";
    private static final String INSERT = "INSERT INTO bench_insert (id, name, amount, created) VALUES (?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE bench_counter SET n = n + 1 WHERE id = ?";
    private static final int BATCH = 1000;

    @State(Scope.Benchmark)
    public static class Server {
        @Setup(Level.Trial)
        public void seed() throws SQLException {
            Db.seed();
        }
    }

    @State(Scope.Thread)
    public static class Session {
        @Param({"pgjdbc", "kotsql"})
        public String driver;

        @Param({"disable"})
        public String sslmode;

        Connection connection;
        long nextId;

        @Setup(Level.Trial)
        public void open(Server seeded) throws SQLException {
            connection = Db.connect(driver, sslmode);
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TEMP TABLE bench_insert (id bigint, name text, amount numeric(12, 2), created timestamp)");
            }
        }

        /** Keeps the temporary table small, however many rows the previous iteration inserted. */
        @Setup(Level.Iteration)
        public void reset() throws SQLException {
            try (Statement s = connection.createStatement()) {
                s.execute("TRUNCATE bench_insert");
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
        bh.consume(r.getObject(4, LocalDateTime.class));
        bh.consume(r.getBoolean(5));
        bh.consume(r.getObject(6, UUID.class));
    }

    private static void selectById(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(SELECT_ONE)) {
            p.setLong(1, s.randomId(Db.ROWS));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
    }

    /** Establishing a connection, with SCRAM authentication. */
    @Benchmark
    public void connect(Session s, Blackhole bh) throws SQLException {
        try (Connection c = Db.connect(s.driver, s.sslmode)) {
            bh.consume(c);
        }
    }

    /** A primary key lookup: one row, one round trip. */
    @Benchmark
    public void selectOne(Session s, Blackhole bh) throws SQLException {
        selectById(s, bh);
    }

    /** The same, on 8 connections at once. */
    @Benchmark
    @Threads(8)
    public void selectOneConcurrent(Session s, Blackhole bh) throws SQLException {
        selectById(s, bh);
    }

    /** Decoding speed: 1000 rows in one request. Score is requests, not rows. */
    @Benchmark
    public void select1000Rows(Session s, Blackhole bh) throws SQLException {
        try (PreparedStatement p = s.connection.prepareStatement(SELECT_RANGE)) {
            p.setLong(1, s.randomId(Db.ROWS - 1000));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
    }

    /** All 100k rows in pages of 1000. pgjdbc pages only in a transaction, so both run in one. */
    @Benchmark
    public void streamAllRows(Session s, Blackhole bh) throws SQLException {
        Connection c = s.connection;
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement(SELECT_ALL)) {
            p.setFetchSize(1000);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) consume(r, bh);
            }
        }
        c.commit();
        c.setAutoCommit(true);
    }

    private static void bindInsert(PreparedStatement p, long id) throws SQLException {
        p.setLong(1, id);
        p.setString(2, "inserted");
        p.setBigDecimal(3, BigDecimal.TEN);
        p.setObject(4, LocalDateTime.of(2024, 1, 1, 12, 0));
    }

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
        try (PreparedStatement p = s.connection.prepareStatement(INSERT)) {
            for (int i = 0; i < BATCH; i++) {
                bindInsert(p, ++s.nextId);
                p.addBatch();
            }
            bh.consume(p.executeBatch());
        }
    }

    /** Read, write, commit: the driver's transaction handling on top of three statements. */
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
