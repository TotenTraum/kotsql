package dev.ksql.benchmark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** The server under test and the shared data. Both drivers read exactly the same rows. */
final class Db {
    static final int ROWS = 100_000;

    static final String HOST = env("BENCH_HOST", "localhost");
    static final String PORT = env("BENCH_PORT", "55432");
    static final String USER = "ksql";
    static final String PASSWORD = "ksql";
    static final String DATABASE = "ksql";

    private Db() {}

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    static String jdbcUrl(String driver, String sslmode) {
        String prefix = switch (driver) {
            case "pgjdbc" -> "jdbc:postgresql://";
            case "ksql" -> "jdbc:ksql:postgresql://";
            default -> throw new IllegalArgumentException(driver);
        };
        return prefix + HOST + ":" + PORT + "/" + DATABASE + "?sslmode=" + sslmode;
    }

    /** @param driver {@code ksql} or {@code postgresql}, for r2dbc-postgresql */
    static String r2dbcUrl(String driver, String sslmode) {
        String credentials = USER + ":" + PASSWORD + "@" + HOST + ":" + PORT + "/" + DATABASE;
        return switch (driver) {
            case "ksql" -> "r2dbc:ksql:postgresql://" + credentials + "?sslmode=" + sslmode;
            case "postgresql" -> "r2dbc:postgresql://" + credentials + "?sslMode=" + sslmode;
            default -> throw new IllegalArgumentException(driver);
        };
    }

    static Connection connect(String driver, String sslmode) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        return DriverManager.getConnection(jdbcUrl(driver, sslmode), props);
    }

    /** Always through pgjdbc, so that neither driver's own code decides what the other one is measured on. */
    static synchronized void seed() throws SQLException {
        try (Connection c = connect("pgjdbc", "disable"); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS bench_rows (
                    id bigint PRIMARY KEY,
                    name text NOT NULL,
                    amount numeric(12, 2) NOT NULL,
                    created timestamp NOT NULL,
                    active boolean NOT NULL,
                    tag uuid NOT NULL
                )""");
            s.execute("""
                INSERT INTO bench_rows
                SELECT i, 'name-' || i, i * 1.25, TIMESTAMP '2024-01-01' + i * INTERVAL '1 minute', i %% 2 = 0, md5(i::text)::uuid
                FROM generate_series(1, %d) i
                ON CONFLICT DO NOTHING""".formatted(ROWS));
            s.execute("ANALYZE bench_rows");
            s.execute("CREATE TABLE IF NOT EXISTS bench_counter (id int PRIMARY KEY, n bigint NOT NULL)");
            s.execute("INSERT INTO bench_counter SELECT i, 0 FROM generate_series(1, 64) i ON CONFLICT DO NOTHING");
        }
    }
}
