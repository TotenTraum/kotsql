<p align="center"><img src="docs/mascot.svg" alt="The kotsql cat, sitting on a database" width="220"></p>

# kotsql

*Kot* is Russian for a cat, and this one sits on your database.

Kotlin Multiplatform SQL drivers with one common API, for PostgreSQL, MySQL and SQLite. The JDBC and R2DBC adapters know nothing
about any of them and work with any driver that implements the SPI.

```
kotsql-core             API (SqlConnection, SqlCursor, SqlRow) and SPI (SqlDriver, SqlDialect), nothing else  JVM, Native
kotsql-transport        For databases behind a socket: Transport, NetworkSqlDriver, wire primitives, failover JVM, Native
kotsql-postgres         PostgreSQL wire protocol                                                             JVM, Native
kotsql-mysql            MySQL wire protocol                                                                  JVM, Native
kotsql-sqlite           SQLite, embedded: androidx.sqlite's bundled build, on Windows its own of the same    JVM, Native
kotsql-transport-ktor   Non-blocking sockets                                                                 JVM, Native
kotsql-jvm-values       What the JVM adapters share: conversions to and from java.sql and java.time           JVM
kotsql-jdbc             java.sql adapter, blocking                                                           JVM
kotsql-r2dbc            R2DBC SPI adapter, reactive                                                          JVM
```

Native means `linuxX64`, `linuxArm64`, `macosArm64` and `mingwX64` (Windows).

## Usage

JDBC, including through pools and tools such as HikariCP, Flyway and jOOQ:

```kotlin
DriverManager.getConnection("jdbc:kotsql:postgresql://localhost:5432/app?sslmode=verify-full", "user", "secret")
```

R2DBC:

```kotlin
ConnectionFactories.get("r2dbc:kotsql:postgresql://user:secret@localhost:5432/app?sslmode=verify-full")
```

Kotlin, on any target:

```kotlin
Drivers.register(PostgresDriver()) // automatic on the JVM
val db = ru.ttraum.kotsql.ktor.connect("postgresql://user:secret@localhost/app?sslmode=disable")
db.query("SELECT id, name FROM users WHERE age > $1", listOf(18), fetchSize = 500).collect { println(it["name"]) }
db.transaction { update("UPDATE users SET age = age + 1") }
db.close()
```

URL settings: `user`, `password`, `sslmode` (`disable`, `prefer`, `require`, `verify-ca`, `verify-full`), `sslrootcert`,
`connectTimeout` and `socketTimeout` (milliseconds), `applicationName`, `prepareThreshold`, `binaryTransfer`, `targetServerType`,
`loadBalanceHosts`. Other keys are sent to PostgreSQL as session settings.

`sslcert` and `sslkey` name PEM files with a client certificate and its key, for both databases. On the JVM the key has to be
in PKCS#8, or PKCS#1 for RSA, and `KtorTransport`, the JVM's default for the Kotlin client, takes only RSA keys (the JDBC and
R2DBC adapters use transports that take EC keys too). `channel_binding` (PostgreSQL) is `prefer` by default, `disable` or `require`: over TLS, SCRAM then
covers the server's certificate, so that even a server authenticated by nothing else can't be impersonated by someone who has
got hold of a hash of the password. With `require` no password is sent to a server that won't do that.

A URL can name several servers, `postgresql://user:pw@one:5432,two:5432/db?targetServerType=primary`, which are tried in order
(or in a random one with `loadBalanceHosts=true`). `targetServerType` is `any` (the default), `primary`, `secondary` or
`preferSecondary`. A server that's down or full is skipped, but a refused password ends the attempt. Failover comes from
replacing a lost connection: a connection in use when its server fails reports that, and `ConnectionPool` makes the next one
through the same search. Each server gets its own `connectTimeout`.

A statement executed `prepareThreshold` times (5 by default) on a connection is prepared on the server, with up to 256 kept
per connection. If the server then rejects it, say after `ALTER TABLE` or `DEALLOCATE ALL`, it is parsed again and retried
transparently, except in a transaction, which that error has aborted. Set `prepareThreshold=0` with a connection pooler that
doesn't keep a client on one server connection, such as PgBouncer in transaction mode before version 1.21.

## Adding a database

Implement three interfaces in a new module; no adapter code changes.

1. `SqlDriver`: URL schemes and `connect`. On the JVM, list it in `META-INF/services/ru.ttraum.kotsql.spi.SqlDriver`. A database behind a
   socket implements `NetworkSqlDriver` of `kotsql-transport` instead, which is handed a transport to connect with. One in this
   process, like SQLite, needs nothing but `kotsql-core`.
2. `SqlConnection` (with `SqlCursor` and `SqlRow`): as ordinary suspending code. Over a network it does all I/O through
   `Transport`, and must not suspend on anything else (no dispatchers, locks or delays).
3. `SqlDialect`: what differs from standard SQL, such as parameter markers, `SqlSyntax` (the quotes and comments that a statement
   has to be read by), `CallSyntax` and `DatabaseMetaData` queries.

`checkModuleBoundaries`, part of `check`, keeps modules from reaching where they shouldn't, and `FakeDriverTest` shows that a
driver of nothing but `kotsql-core` works through the adapters.

The same code serves both worlds thanks to that rule. JDBC plugs in the blocking `SocketTransport`, so the coroutine never
actually suspends and `runUnsuspended` completes it on the calling thread, with no `runBlocking` and no extra threads.
R2DBC plugs in `AsyncSocketTransport`, where it suspends for real. The Kotlin client's `connect(url)` takes `DefaultTransport`:
`EpollTransport` on Linux, which is several times faster than ktor's native sockets under load, and `KtorTransport` elsewhere.

## SQLite

URLs are `sqlite:///relative.db`, `sqlite:////absolute/path.db` and `sqlite:///:memory:`, also after `jdbc:kotsql:`, for `ru.ttraum.kotsql.connect(url)`
with no transport, or just `connectSqlite(path)`. For R2DBC, set the `database` option. `mode` is `rwc` by default, `rw` or `ro`, and any other key becomes a
`PRAGMA`, as in `?foreign_keys=ON&journal_mode=WAL`. `busy_timeout` is 5000 milliseconds unless set. Each connection keeps its 64 most
recently used prepared statements, by their text: `statement_cache_size` changes that, and `0` turns the cache off.

SQLite is 3.50.1 everywhere. On the JVM, Linux and macOS it is androidx.sqlite's bundled build. androidx.sqlite has none for
Windows, so there the build downloads the same version's amalgamation from sqlite.org, checks its SHA-256 and compiles it into
the library with the options that androidx.sqlite's build reports; `SqliteTest.sameSqliteEverywhere` checks that they agree.
Upgrading SQLite means changing the version and checksum in `kotsql-sqlite/build.gradle.kts` along with androidx.sqlite.

It differs from the others by being in this process:

- Calls block the thread they run on, including with R2DBC, which has no other thread to run them on here. In coroutines, use
  `withContext(Dispatchers.IO)`. So a `withTimeout` can't interrupt a statement, and neither can `cancel()` or a JDBC query timeout, for
  which this build of SQLite has no API.
- Values come as what the database holds: `Long`, `Double`, `String`, `ByteArray` or null, also for a column declared otherwise. A
  column's type in `ColumnInfo` comes from its value in the first row: `INTEGER` if that integer fits in 32 bits, `BIGINT` if not.
  JDBC's `getObject` and R2DBC's `get` give such an `INTEGER` value as an `Integer`, as other SQLite drivers do, and a larger one as a `Long`.
  The getters convert values, reading dates and times from text in ISO format, which is how parameters are stored too
  (`2024-06-01 12:00:00`, with the seconds always), but not from Unix or Julian day numbers.
- Every transaction is serializable. `:memory:` is private to each connection, so a pool of them needs a file.
- SQLite doesn't enforce foreign keys unless told to, and the driver leaves that as it is. `CallableStatement` only works for
  functions, as `{? = call f(?)}`, as it has no procedures. `describe` doesn't know types.

## MySQL

URLs are `jdbc:kotsql:mysql://...`, `r2dbc:kotsql:mysql://...` and `mysql://...`, with the same settings, for MySQL 5.7.5 and later.
Authentication is `caching_sha2_password` or `mysql_native_password`. The former needs the full password the first time it sees
a user: that takes TLS, or `allowPublicKeyRetrieval=true`, which trusts a public key received over an unprotected connection.

Parameters always go through server-side prepared statements, never into the text of the SQL. These differ from PostgreSQL:

- Generated keys come from the server's `last_insert_id`, as a column named `GENERATED_KEY`: the first value and those after
  it, which assumes the default `innodb_autoinc_lock_mode` behaviour of consecutive values within a statement.
- DDL commits an open transaction, as it always does in MySQL.
- `TIME` values outside of a day and zero dates have no equivalent in the canonical types: reading them fails with `22008`,
  but `getText` gives the server's text.
- A paged query (`fetchSize`) is materialized on the server, as MySQL's cursors are.
- `DATETIME` and `TIMESTAMP` come without a zone, as `LocalDateTime`. A `java.sql.Timestamp` parameter is sent as its wall clock.
- Warnings aren't collected, and arrays aren't supported, as MySQL has none. MariaDB is accepted but untested.

R2DBC named binding uses `?1`, `?2` and so on, since MySQL's markers have no names.

## TLS

`sslmode` works the same on every platform. The JVM uses JSSE (ktor for R2DBC), native targets OpenSSL, linked statically
from `com.ensody.nativebuilds`. With `verify-ca` and `verify-full`, trust comes from `sslrootcert` if set. Otherwise it is
the JVM's trust store, or on native targets the `SSL_CERT_FILE` and `SSL_CERT_DIR` variables and the operating system's
usual CA bundle locations; if none is found, the connection fails. The macOS keychain and the Windows certificate store are
not consulted, so on Windows, where there is no such bundle, verifying needs `sslrootcert` or `SSL_CERT_FILE`.

Building the `macosArm64` target needs a full Xcode, because of the OpenSSL bindings. JVM and Linux tasks do not.

## Limitations

- Parameters travel in the text format, and results too until a statement has been prepared on the server. From then on the
  columns of the types that convert to text without loss come in binary: `bool`, the integers, `oid`, `bytea`, `uuid`, `date`,
  `time` and `timestamp`. `binaryTransfer=false` turns that off.
- On macOS and Windows the Kotlin client uses ktor's sockets, which don't scale with connections the way `EpollTransport` does
  on Linux. On macOS they are built on one `pselect` thread and can't use descriptors above 1024; a `kqueue` counterpart of
  `EpollTransport` is yet to be written. On Windows one of ktor's selectors watches at most 63 sockets, so `KtorTransport`
  spreads them over as many selectors as it takes, 48 to each, with a thread for each selector.
- Not implemented: password-protected client keys,
  a `CallableStatement`'s parameters by name, batches of calls.
- An updatable JDBC result set takes a plain query of one table with a primary key, whose columns it has to select, and holds
  its rows in memory. Inserted rows don't show up in it. Any other query yields a read-only result set.
- With MySQL, a `CallableStatement` with out parameters drops any result sets of the procedure.
- SASLprep for SCRAM leaves out the bidirectional check. On native Linux it also leaves out normalization, for lack of a
  platform API, so a password that isn't in normal form, such as a decomposed `é`, doesn't match there. macOS and Windows
  normalize with their own APIs.
- A JDBC scrollable result set (`TYPE_SCROLL_INSENSITIVE`) and `Clob`/`Blob` values are held in memory in full.
- A JDBC query timeout covers the wait for the first row, not the iteration over the rest.
- R2DBC streams a result's rows only if its `Result` is subscribed to while the statement publisher is emitting it, as
  `concatMap`, `flatMap` and `Mono.from` do. Reading results later, or through a `publishOn`, still works, but the rows of
  all results but the last are held in memory until then.
- R2DBC callbacks such as `Result.map` run on the driver's event threads, as in other reactive drivers. They must not block,
  which would hold up the other connections on that thread. Use `publishOn` for blocking work.
- Without a fetch size, abandoning a result early reads and discards its remaining rows. For results you may not read to
  the end, set a fetch size (`setFetchSize`, `Statement.fetchSize`), which closes the query on the server instead.

## Connection pool

`ConnectionPool` works with any driver and on every platform, over a transport that doesn't block. On the JVM with JDBC or R2DBC,
HikariCP and `r2dbc-pool` remain the natural choice.

```kotlin
val pool = connectionPool(url, PoolConfig(maxSize = 20, minSize = 2))

pool.transaction { update("UPDATE accounts SET balance = balance - 1") }
pool.query("SELECT id FROM users").collect { println(it[0]) }
pool.withConnection { /* several statements on one connection */ }
pool.close()
```

Callers queue fairly for `maxSize` connections, for up to `acquireTimeout`. A connection returns with its cursors closed and any
transaction rolled back, or it's closed instead if that fails or takes over `resetTimeout`. Whatever else a holder changes in the session,
such as a `SET` or a temporary table, stays for the next one, so use `onConnect` for settings that all should share. Connections are
checked with a round trip after `validateAfterIdle` of idleness, and replaced after `maxLifetime`. After being returned, a
`PooledConnection` refuses to work, and a `cancel()` on it does nothing rather than hitting its next holder's query. A cursor must
not be read after its connection is returned.

Cancelling a coroutine in the middle of a statement loses that connection, as it would anywhere: the pool replaces it.

## PostgreSQL: LISTEN, NOTIFY and COPY

These are on `SqlConnection.postgres`, also for a connection from a pool. They're for the Kotlin client: JDBC and R2DBC don't offer them.

```kotlin
// Notifications arrive only on a connection being read, so these take one of their own, and survive losing it.
postgresNotifications({ connect(url) }, "orders", onConnected = { reloadOrders() }).collect { println(it.payload) }
connection.postgres.notify("orders", "42")

// Loading and reading data, faster than statements and batches.
connection.postgres.copyInRows("users", listOf("id", "name"), rows)        // rows: Flow<List<Any?>>
connection.postgres.copyOutRows("users").collect { println(it) }          // List<String?>
connection.postgres.copyIn("COPY users FROM STDIN (FORMAT csv)", bytes)    // bytes: Flow<ByteArray>
connection.postgres.copyOut("COPY users TO STDOUT (FORMAT csv)").collect { /* ByteArray */ }
```

**PostgreSQL doesn't keep notifications for anyone disconnected**, so treat one as a hint, with `onConnected` to catch up on what may have
been missed. A connection used for statements can do `LISTEN` by itself and call `drainNotifications()`, which holds up to 10,000.

If the data for `copyIn` fails, nothing is stored and the failure is rethrown. The server's complaints about the data arrive only after
all of it has been sent, and they're no surprise to `copyInRows`. Stopping the collection of `copyOut` cancels the statement on the server.

## Custom types

A `SqlCodec` converts between a class and a value the driver knows, usually a `String`. The same codec works with every database,
on every platform and through JDBC and R2DBC.

```kotlin
enum class Mood { SAD, HAPPY }

// Columns of the PostgreSQL enum type `mood` decode by themselves, in arrays too.
CodecRegistry.Global.register(SqlCodec.enum<Mood>("mood"))

// With no type names, a column becomes the class when it's asked for one.
CodecRegistry.Global.register(SqlCodec.of<Email>(encode = { it.address }, decode = { Email(it.toString()) }))

row[0]                 // Mood.HAPPY
row.get(1, Email::class)
rs.getObject(1, Email.class)     // JDBC
row.get(1, Email.class)          // R2DBC
```

The registry is `CodecRegistry.Global` unless you give another: `connect(url, codecs)`, `KotsqlDataSource(url, user, password, codecs)`,
or the `codecs` option of R2DBC's `ConnectionFactoryOptions`. `HstoreCodec` for PostgreSQL's `hstore` comes with the driver. Enums
need no codec to be sent, as they go by name, and an unmatched value of the column fails with `22023`.

- **PostgreSQL:** names are matched without their schema, so two types of one name in different schemas look the same. A domain is
  sent as its base type, so it takes asking for the class. Parameters are sent untyped, for the server to infer, so a bare `SELECT $1`
  takes a cast.
- **MySQL:** `ENUM` and `SET` columns have no type name of their own, so they're read by asking for the class. `JSON` does have one.

## Errors

A `SqlException` has `sqlState`, `vendorCode`, `kind` and, in `details`, what the database said apart from the message. JDBC's
`SQLException`, R2DBC's `R2dbcException` and framework exceptions have it as a cause, and `sqlErrorDetails()` finds it from any of
them:

```kotlin
catch (e: DataIntegrityViolationException) {
    val details = e.sqlErrorDetails()
    if (details?.violation == ConstraintViolation.UNIQUE && details.constraint == "users_email_key") { /* email taken */ }
}
```

From Java it's `SqlErrors.sqlErrorDetails(e)`. PostgreSQL provides all the fields, from its protocol. MySQL's come from the error
code and, for the names, the text of the message, so they're null if the server's `lc_messages` isn't English. For a unique
violation MySQL names the index, and whether the table comes with it depends on the server's version. `detail` can include values from the data, such as the key
that already exists, so take care where it gets logged.

## Compatibility

`kotsql-compat-tests` runs one application against each database, with no official driver on the classpath: Spring Boot 3.5,
Spring Data JPA and Hibernate 6, `JdbcTemplate`, and Spring Data R2DBC with `r2dbc-pool`. On SQLite, with Hibernate's community
dialect, three of its tests are changed for what SQLite or that dialect can't do: Hibernate's schema validation, numbers of more
than 15 digits in a NUMERIC column, and a scrollable result by default.

## Benchmarks

`tools/run-benchmarks.sh` compares `kotsql-jdbc` with pgjdbc and `kotsql-r2dbc` with r2dbc-postgresql using JMH, and SQLite through
`kotsql-jdbc` with xerial's sqlite-jdbc, and writes `benchmarks/RESULTS.md`.
Docker must be running. Pass JMH options to change what runs, e.g. a name filter and `-f 1 -wi 1 -i 1 -r 1s -w 1s`.

JMH waits for each R2DBC query with `block()`, which adds thread switches that a reactive application doesn't have, so those
numbers understate R2DBC. Throughput also suffers on a busy machine. `ru.ttraum.kotsql.benchmark.CostProbe` reports what a busy machine
disturbs less: median latency, client CPU time and context switches per query, and has a `reactive` mode without `block()`.

`tools/run-client-benchmarks.sh` measures the Kotlin client with the same code on the JVM and as a native release binary, and
writes `benchmarks/RESULTS-native.md`.

## Tests

```bash
./gradlew jvmTest
```

The JDBC and R2DBC suites start PostgreSQL through Testcontainers, so Docker must be running. The R2DBC suite runs the
official `r2dbc-spi-test` TCK on PostgreSQL, MySQL and SQLite.

The client and TLS tests in `kotsql-transport-ktor` run on every target. Where Docker is available, they start what they need
themselves through [testcontainers-kotlin](https://github.com/TotenTraum/testcontainers-kotlin): PostgreSQL with TLS, the
roles they log in as and a CA for client certificates, a streaming standby, and MySQL. So these need nothing else:

```bash
./gradlew :kotsql-transport-ktor:jvmTest
./gradlew :kotsql-transport-ktor:macosArm64Test
```

`KOTSQL_TEST_URL` and the other `KOTSQL_TEST_*` variables name servers of your own instead, and the tests that need one skip
themselves without either. Tests that run inside a container can't start containers, so this script runs them against the
servers of `compose.yaml`, set up the same way: Linux on a Mac, and with `KOTSQL_TEST_WINE=1` the `mingwX64` tests under Wine
in an x86_64 container. It runs the JVM and this machine's native tests too:

```bash
tools/run-client-tests.sh
```

`ProtocolFuzzTest` needs no server, and feeds both drivers mutated server responses. `KOTSQL_FUZZ_ITERATIONS` and
`KOTSQL_FUZZ_SEED` take it further than its default. `NetworkFaultTest` makes the network fail at each position of an exchange
with a real server. To look for what only shows over time, such as leaks, `tools/run-soak.sh` runs a mixed load for the given
number of seconds per database:

```bash
tools/run-soak.sh 3600
```

`kotsql-jdbc/src/jvmMain/kotlin/ru/ttraum/kotsql/jdbc/JdbcBases.kt` is generated by `tools/GenerateJdbcBases.java`. After changing
the OpenSSL bindings or upgrading OpenSSL, run `tools/check-openssl-decls.sh`.

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:

- the JVM suites, the compatibility application and the module boundaries, on Linux;
- `tools/run-client-tests.sh` on Linux x86_64, which covers the JVM, `linuxX64` and `mingwX64` under Wine;
- `mingwX64Test` on Windows, against the PostgreSQL that the runner has, without TLS;
- `macosArm64Test` on macOS, where there is no Docker, so the tests that need a server skip themselves;
- `tools/check-openssl-decls.sh`, and a two-minute `tools/run-soak.sh`.

`linuxArm64` is covered by running the scripts on an ARM machine, such as a Mac with Apple silicon. On a machine that can run
x86_64 containers, `KOTSQL_LINUX_ARCH=x86_64` makes the scripts test `linuxX64` instead.

## License

[Apache License 2.0](LICENSE).
