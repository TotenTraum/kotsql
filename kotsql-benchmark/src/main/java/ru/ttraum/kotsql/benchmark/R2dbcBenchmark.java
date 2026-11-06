package ru.ttraum.kotsql.benchmark;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.sql.SQLException;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** kotsql-r2dbc and r2dbc-postgresql in the scenarios of {@link JdbcBenchmark}, with default settings, waiting with block(). */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class R2dbcBenchmark {
    private static final String COLUMNS = "SELECT id, name, amount, created, active, tag FROM bench_rows";

    @State(Scope.Benchmark)
    public static class Server {
        ConnectionFactory factory;

        @Param({"kotsql", "postgresql"})
        public String driver;

        @Param({"disable"})
        public String sslmode;

        @Setup(Level.Trial)
        public void seed() throws SQLException {
            Db.seed();
            factory = ConnectionFactories.get(Db.r2dbcUrl(driver, sslmode));
        }
    }

    @State(Scope.Thread)
    public static class Session {
        Connection connection;

        @Setup(Level.Trial)
        public void open(Server server) {
            connection = Mono.from(server.factory.create()).block();
        }

        @TearDown(Level.Trial)
        public void close() {
            Mono.from(connection.close()).block();
        }
    }

    private static Object[] read(Readable r) {
        return new Object[] {
            r.get(0, Long.class), r.get(1, String.class), r.get(2, BigDecimal.class),
            r.get(3, LocalDateTime.class), r.get(4, Boolean.class), r.get(5, UUID.class),
        };
    }

    private static void run(io.r2dbc.spi.Statement statement, Blackhole bh) {
        Flux.from(statement.execute()).concatMap(result -> result.map(R2dbcBenchmark::read)).doOnNext(bh::consume).blockLast();
    }

    @Benchmark
    public void connect(Server server, Blackhole bh) {
        Connection c = Mono.from(server.factory.create()).block();
        bh.consume(c);
        Mono.from(c.close()).block();
    }

    @Benchmark
    public void selectOne(Session s, Blackhole bh) {
        run(s.connection.createStatement(COLUMNS + " WHERE id = $1").bind(0, ThreadLocalRandom.current().nextLong(1, Db.ROWS)), bh);
    }

    @Benchmark
    @Threads(8)
    public void selectOneConcurrent(Session s, Blackhole bh) {
        selectOne(s, bh);
    }

    @Benchmark
    public void select1000Rows(Session s, Blackhole bh) {
        long from = ThreadLocalRandom.current().nextLong(1, Db.ROWS - 1000);
        run(s.connection.createStatement(COLUMNS + " WHERE id > $1 ORDER BY id LIMIT 1000").bind(0, from), bh);
    }

    @Benchmark
    public void streamAllRows(Session s, Blackhole bh) {
        run(s.connection.createStatement(COLUMNS).fetchSize(1000), bh);
    }
}
