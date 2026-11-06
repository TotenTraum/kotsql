package ru.ttraum.kotsql.benchmark;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What a primary key lookup costs a client, in terms that a busy machine disturbs little, unlike throughput: median
 * latency, CPU time per operation by thread pool, and context switches per operation.
 *
 * <p>Arguments: {@code pgjdbc|kotsql|r2dbc|reactive threads seconds [sslmode] [r2dbcDriver]}. {@code r2dbc} waits for each query
 * with block(), as the JMH benchmarks do; {@code reactive} runs that many connections with no thread waiting at all, which is
 * how R2DBC is meant to be used. The R2DBC driver is {@code kotsql} by default, or {@code postgresql} for r2dbc-postgresql. Linux only, for the context switch counts.
 */
public final class CostProbe {
    private static String sslmode = "disable";
    private static String r2dbcDriver = "kotsql";
    private static final String SQL = "SELECT id, name, amount, created, active, tag FROM bench_rows WHERE id = ";

    private interface Op {
        void run() throws Exception;
    }

    private static Op jdbc(String driver) throws Exception {
        java.sql.Connection c = Db.connect(driver, sslmode);
        return () -> {
            try (PreparedStatement p = c.prepareStatement(SQL + "?")) {
                p.setLong(1, ThreadLocalRandom.current().nextLong(1, Db.ROWS));
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) r.getLong(1);
                }
            }
        };
    }

    private static Op r2dbc(ConnectionFactory factory) {
        Connection c = Mono.from(factory.create()).block();
        return () -> Flux.from(c.createStatement(SQL + "$1").bind(0, ThreadLocalRandom.current().nextLong(1, Db.ROWS)).execute())
            .concatMap(result -> result.map(row -> row.get(0, Long.class)))
            .blockLast();
    }

    private static long contextSwitches() throws Exception {
        long total = 0;
        try (Stream<Path> tasks = Files.list(Path.of("/proc/self/task"))) {
            for (Path task : (Iterable<Path>) tasks::iterator) {
                try {
                    for (String line : Files.readAllLines(task.resolve("status"))) {
                        if (line.contains("voluntary_ctxt_switches")) total += Long.parseLong(line.replaceAll("\\D", ""));
                    }
                } catch (java.io.IOException gone) {
                    // The thread has ended.
                }
            }
        }
        return total;
    }

    private static String pool(String threadName) {
        if (threadName.startsWith("probe")) return "client";
        if (threadName.startsWith("DefaultDispatcher")) return "coroutines";
        if (threadName.startsWith("kotsql-io") || threadName.startsWith("reactor-")) return "io";
        return "other";
    }

    private static Map<String, Long> cpuByPool(ThreadMXBean threads) {
        Map<String, Long> cpu = new TreeMap<>();
        for (long id : threads.getAllThreadIds()) {
            var info = threads.getThreadInfo(id);
            long time = threads.getThreadCpuTime(id);
            if (info != null && time > 0) cpu.merge(pool(info.getThreadName()), time, Long::sum);
        }
        return cpu;
    }

    /** Queries in a loop on each connection, each one started by the completion of the one before. */
    private static void reactive(int connections, int seconds) throws Exception {
        ConnectionFactory factory = ConnectionFactories.get(Db.r2dbcUrl(r2dbcDriver, sslmode));
        java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong();
        for (int i = 0; i < connections; i++) {
            Connection c = Mono.from(factory.create()).block();
            Mono.defer(() -> Flux.from(c.createStatement(SQL + "$1").bind(0, ThreadLocalRandom.current().nextLong(1, Db.ROWS)).execute())
                    .concatMap(result -> result.map(row -> row.get(0, Long.class)))
                    .doOnNext(v -> done.incrementAndGet())
                    .then())
                .repeat()
                .subscribe();
        }
        Thread.sleep(15_000);
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, Long> cpuBefore = cpuByPool(threads);
        long switchesBefore = contextSwitches();
        long opsBefore = done.get();
        Thread.sleep(seconds * 1000L);
        long total = done.get() - opsBefore;
        Map<String, Long> cpuAfter = cpuByPool(threads);
        long switches = contextSwitches() - switchesBefore;
        long cpuTotal = 0;
        StringBuilder cpu = new StringBuilder();
        for (var e : cpuAfter.entrySet()) {
            long used = e.getValue() - cpuBefore.getOrDefault(e.getKey(), 0L);
            cpuTotal += used;
            cpu.append(String.format(" %s=%.0f", e.getKey(), (double) used / total / 1000));
        }
        System.out.printf("RESULT %-20s connections=%d ops/s=%,d cpu/op=%.0fus (%s ) ctxsw/op=%.1f%n",
            "reactive-" + r2dbcDriver, connections, total / seconds, (double) cpuTotal / total / 1000, cpu.toString().trim(), (double) switches / total);
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        String kind = args[0];
        int threadCount = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        sslmode = args.length > 3 ? args[3] : "disable";
        r2dbcDriver = args.length > 4 ? args[4] : "kotsql";
        Db.seed();
        if (kind.equals("reactive")) {
            reactive(threadCount, seconds);
            return;
        }
        ConnectionFactory factory = kind.equals("r2dbc") ? ConnectionFactories.get(Db.r2dbcUrl(r2dbcDriver, sslmode)) : null;
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) ops.add(factory != null ? r2dbc(factory) : jdbc(kind));

        // Warm up, then measure.
        long[][] samples = new long[threadCount][];
        int[] counts = new int[threadCount];
        long warmupEnd = System.nanoTime() + 15_000_000_000L;
        long[] phaseEnd = {0};
        CountDownLatch warmedUp = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threadCount);
        CountDownLatch measured = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            int index = t;
            Thread worker = new Thread(() -> {
                try {
                    Op op = ops.get(index);
                    while (System.nanoTime() < warmupEnd) op.run();
                    warmedUp.countDown();
                    start.await();
                    long[] latencies = new long[4_000_000];
                    int n = 0;
                    while (System.nanoTime() < phaseEnd[0] && n < latencies.length) {
                        long begin = System.nanoTime();
                        op.run();
                        latencies[n++] = System.nanoTime() - begin;
                    }
                    samples[index] = latencies;
                    counts[index] = n;
                    // Stay alive until the counters are read: a finished thread's CPU time and switches are gone.
                    finished.countDown();
                    measured.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "probe-" + t);
            worker.start();
            workers.add(worker);
        }
        warmedUp.await();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, Long> cpuBefore = cpuByPool(threads);
        long switchesBefore = contextSwitches();
        phaseEnd[0] = System.nanoTime() + seconds * 1_000_000_000L;
        start.countDown();
        finished.await();
        Map<String, Long> cpuAfter = cpuByPool(threads);
        long switches = contextSwitches() - switchesBefore;
        measured.countDown();

        int total = Arrays.stream(counts).sum();
        long[] all = new long[total];
        int at = 0;
        for (int t = 0; t < threadCount; t++) {
            System.arraycopy(samples[t], 0, all, at, counts[t]);
            at += counts[t];
        }
        Arrays.sort(all);
        StringBuilder cpu = new StringBuilder();
        long cpuTotal = 0;
        for (var e : cpuAfter.entrySet()) {
            long used = e.getValue() - cpuBefore.getOrDefault(e.getKey(), 0L);
            cpuTotal += used;
            cpu.append(String.format(" %s=%.0f", e.getKey(), (double) used / total / 1000));
        }
        System.out.printf("RESULT %-6s t=%d ops/s=%,d p50=%.0fus p90=%.0fus p99=%.0fus cpu/op=%.0fus (%s ) ctxsw/op=%.1f%n",
            kind, threadCount, total / seconds, all[total / 2] / 1e3, all[(int) (total * 0.9)] / 1e3, all[(int) (total * 0.99)] / 1e3,
            (double) cpuTotal / total / 1000, cpu.toString().trim(), (double) switches / total);
        System.exit(0);
    }
}
