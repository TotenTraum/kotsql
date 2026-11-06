# ksql

Kotlin Multiplatform SQL drivers with one common API, for PostgreSQL and MySQL. The JDBC and R2DBC adapters know nothing
about either and work with any driver that implements the SPI.

```
ksql-core             API (SqlConnection, SqlCursor, SqlRow) and SPI (SqlDriver, SqlDialect, Transport)    JVM, Native
ksql-postgres         PostgreSQL wire protocol                                                             JVM, Native
ksql-mysql            MySQL wire protocol                                                                  JVM, Native
ksql-transport-ktor   Non-blocking sockets                                                                 JVM, Native
ksql-jdbc             java.sql adapter, blocking                                                           JVM
ksql-r2dbc            R2DBC SPI adapter, reactive                                                          JVM
```

## Usage

JDBC, including through pools and tools such as HikariCP, Flyway and jOOQ:

```kotlin
DriverManager.getConnection("jdbc:ksql:postgresql://localhost:5432/app?sslmode=verify-full", "user", "secret")
```

R2DBC:

```kotlin
ConnectionFactories.get("r2dbc:ksql:postgresql://user:secret@localhost:5432/app?sslmode=verify-full")
```

Kotlin, on any target:

```kotlin
Drivers.register(PostgresDriver()) // automatic on the JVM
val db = dev.ksql.ktor.connect("postgresql://user:secret@localhost/app?sslmode=disable")
db.query("SELECT id, name FROM users WHERE age > $1", listOf(18), fetchSize = 500).collect { println(it["name"]) }
db.transaction { update("UPDATE users SET age = age + 1") }
db.close()
```

URL settings: `user`, `password`, `sslmode` (`disable`, `prefer`, `require`, `verify-ca`, `verify-full`), `sslrootcert`,
`connectTimeout` and `socketTimeout` (milliseconds), `applicationName`, `prepareThreshold`. Other keys are sent to
PostgreSQL as session settings.

A statement executed `prepareThreshold` times (5 by default) on a connection is prepared on the server, with up to 256 kept
per connection. If the server then rejects it, say after `ALTER TABLE` or `DEALLOCATE ALL`, it is parsed again and retried
transparently, except in a transaction, which that error has aborted. Set `prepareThreshold=0` with a connection pooler that
doesn't keep a client on one server connection, such as PgBouncer in transaction mode before version 1.21.

## Adding a database

Implement three interfaces in a new module; no adapter code changes.

1. `SqlDriver`: URL schemes and `connect`. On the JVM, list it in `META-INF/services/dev.ksql.spi.SqlDriver`.
2. `SqlConnection` (with `SqlCursor` and `SqlRow`): the protocol, as ordinary suspending code that does all I/O through
   `Transport`. It must not suspend on anything else (no dispatchers, locks or delays).
3. `SqlDialect`: what differs from standard SQL, such as parameter markers and `DatabaseMetaData` queries.

The same code serves both worlds thanks to that rule. JDBC plugs in the blocking `SocketTransport`, so the coroutine never
actually suspends and `runUnsuspended` completes it on the calling thread, with no `runBlocking` and no extra threads.
R2DBC plugs in `AsyncSocketTransport`, where it suspends for real. The Kotlin client's `connect(url)` takes `DefaultTransport`:
`EpollTransport` on Linux, which is several times faster than ktor's native sockets under load, and `KtorTransport` elsewhere.

## MySQL

URLs are `jdbc:ksql:mysql://...`, `r2dbc:ksql:mysql://...` and `mysql://...`, with the same settings, for MySQL 5.7.5 and later.
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
usual CA bundle locations; if none is found, the connection fails. The macOS keychain is not consulted.

Building the `macosArm64` target needs a full Xcode, because of the OpenSSL bindings. JVM and Linux tasks do not.

## Limitations

- Values travel in the text format.
- On macOS the Kotlin client uses ktor's sockets, which are built on one `pselect` thread, so they don't scale with connections
  and can't use descriptors above 1024. A `kqueue` counterpart of `EpollTransport` is yet to be written.
- Not implemented: `COPY`, `LISTEN/NOTIFY`, multi-host URLs, client certificates, SCRAM channel binding, SASLprep,
  JDBC updatable result sets, `CallableStatement` (and so JPA's `@Procedure`), JDBC `{fn}` escapes.
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

The registry is `CodecRegistry.Global` unless you give another: `connect(url, codecs)`, `KsqlDataSource(url, user, password, codecs)`,
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

`ksql-compat-tests` runs one application against both databases, with no official driver on the classpath: Spring Boot 3.5,
Spring Data JPA and Hibernate 6, `JdbcTemplate`, and Spring Data R2DBC with `r2dbc-pool`.

## Benchmarks

`tools/run-benchmarks.sh` compares `ksql-jdbc` with pgjdbc and `ksql-r2dbc` with r2dbc-postgresql using JMH, and writes `benchmarks/RESULTS.md`.
Docker must be running. Pass JMH options to change what runs, e.g. a name filter and `-f 1 -wi 1 -i 1 -r 1s -w 1s`.

JMH waits for each R2DBC query with `block()`, which adds thread switches that a reactive application doesn't have, so those
numbers understate R2DBC. Throughput also suffers on a busy machine. `dev.ksql.benchmark.CostProbe` reports what a busy machine
disturbs less: median latency, client CPU time and context switches per query, and has a `reactive` mode without `block()`.

`tools/run-client-benchmarks.sh` measures the Kotlin client with the same code on the JVM and as a native release binary, and
writes `benchmarks/RESULTS-native.md`.

## Tests

```bash
./gradlew jvmTest
```

The JDBC and R2DBC suites start PostgreSQL through Testcontainers, so Docker must be running. The R2DBC suite includes the
official `r2dbc-spi-test` TCK.

The client and TLS tests in `ksql-transport-ktor` run on every target against the database of `compose.yaml`, and skip
themselves without it. This script runs them on the JVM and in a Linux container, on the architecture of this machine:

```bash
tools/run-client-tests.sh
```

On a Mac with a full Xcode it runs them natively on macOS as well.

`ksql-jdbc/src/jvmMain/kotlin/dev/ksql/jdbc/JdbcBases.kt` is generated by `tools/GenerateJdbcBases.java`. After changing
the OpenSSL bindings or upgrading OpenSSL, run `tools/check-openssl-decls.sh`.
