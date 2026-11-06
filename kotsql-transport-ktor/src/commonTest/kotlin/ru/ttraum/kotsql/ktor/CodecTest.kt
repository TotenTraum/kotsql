package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.CodecRegistry
import ru.ttraum.kotsql.ExecuteOptions
import ru.ttraum.kotsql.SqlCodec
import ru.ttraum.kotsql.SqlConnection
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.SqlResult
import ru.ttraum.kotsql.SqlType
import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.postgres.HstoreCodec
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

enum class Mood { SAD, HAPPY }

data class Email(val address: String)

data class Point(val x: Int, val y: Int)

private val EmailCodec = SqlCodec.of<Email>(encode = { it.address }, decode = { Email(it.toString()) })

/** A class that a JSON column decodes to by itself, with parsing far too simple for anything but this test. */
private val PointCodec = SqlCodec.of<Point>(
    "json", "jsonb",
    encode = { """{"x": ${it.x}, "y": ${it.y}}""" },
    decode = { text ->
        val numbers = Regex("-?\\d+").findAll(text.toString()).map { it.value.toInt() }.toList()
        Point(numbers[0], numbers[1])
    },
)

class CodecTest {
    private val postgres = testEnv("KOTSQL_TEST_URL")
    private val mysql = testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")?.let { "mysql://root:kotsql@$it/kotsql?sslmode=disable&allowPublicKeyRetrieval=true" }

    private fun onPostgres(codecs: CodecRegistry = CodecRegistry(), block: suspend SqlConnection.() -> Unit) = runTest(timeout = 60.seconds) {
        val url = postgres ?: return@runTest println("KOTSQL_TEST_URL is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            val connection = connect(url, codecs)
            try { connection.block() } finally { connection.close() }
        }
    }

    private fun onMysql(codecs: CodecRegistry = CodecRegistry(), block: suspend SqlConnection.() -> Unit) = runTest(timeout = 60.seconds) {
        val url = mysql ?: return@runTest println("KOTSQL_TEST_MYSQL_HOST_PORT is not set; skipping")
        withContext(Dispatchers.Default) {
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            val connection = connect(url, codecs)
            try { connection.block() } finally { connection.close() }
        }
    }

    private suspend fun SqlConnection.createMood() {
        update("DROP TYPE IF EXISTS pg_temp.mood CASCADE")
        update("DROP TYPE IF EXISTS mood CASCADE")
        update("CREATE TYPE mood AS ENUM ('SAD', 'HAPPY')")
    }

    @Test fun typesAreKnownByNameEvenWithoutCodecs() = onPostgres {
        createMood()
        val row = query("SELECT 'HAPPY'::mood, ARRAY['SAD','HAPPY']::mood[], '127.0.0.1'::inet, NULL::mood").toList().single()
        assertEquals("mood", row.columns[0].nativeTypeName); assertEquals(SqlType.OTHER, row.columns[0].type)
        assertEquals("_mood", row.columns[1].nativeTypeName); assertEquals(SqlType.ARRAY, row.columns[1].type)
        assertEquals("inet", row.columns[2].nativeTypeName)
        assertEquals("HAPPY", row[0]); assertEquals(listOf("SAD", "HAPPY"), row[1]); assertEquals("127.0.0.1", row[2]); assertEquals(null, row[3])
    }

    @Test fun enumsDecodeByThemselvesAndBind() = onPostgres(CodecRegistry(SqlCodec.enum<Mood>("mood"))) {
        createMood()
        update("CREATE TEMP TABLE people(name text, mood mood, past mood[])")
        update("INSERT INTO people VALUES ($1, $2, $3)", listOf("a", Mood.HAPPY, listOf(Mood.SAD, null, Mood.HAPPY)))
        update("INSERT INTO people VALUES ('b', NULL, NULL)")
        val rows = query("SELECT * FROM people ORDER BY name").toList()
        assertEquals(Mood.HAPPY, rows[0][1]); assertEquals(listOf(Mood.SAD, null, Mood.HAPPY), rows[0][2])
        assertEquals("HAPPY", rows[0].getText(1)) // the text is the server's, always
        assertEquals(null, rows[1][1]); assertEquals(null, rows[1][2])
        assertEquals("a", query("SELECT name FROM people WHERE mood = $1", listOf(Mood.HAPPY)).toList().single()[0])
        assertEquals(Mood.HAPPY, rows[0].get(1, Mood::class))
    }

    @Test fun aTypeCreatedOnThisConnectionWorksAtOnce() = onPostgres(CodecRegistry(SqlCodec.enum<Mood>("mood"))) {
        createMood()
        // Also in the middle of paged results, of a transaction, and of another open cursor.
        update("START TRANSACTION")
        val other = execute("SELECT generate_series(1, 3)")
        val otherRows = other.nextResult() as SqlResult.Rows
        assertEquals(1, otherRows.next()!![0])
        val cursor = execute("SELECT 'SAD'::mood FROM generate_series(1, 10)", options = ExecuteOptions(fetchSize = 3))
        val rows = cursor.nextResult() as SqlResult.Rows
        assertEquals(List(10) { Mood.SAD }, List(10) { rows.next()!![0] })
        assertEquals(null, rows.next())
        assertEquals(null, cursor.nextResult())
        assertEquals(listOf(2, 3), List(2) { otherRows.next()!![0] })
        update("COMMIT")
    }

    @Test fun aValueTheCodecRejectsIsAnError() = onPostgres(CodecRegistry(SqlCodec.enum<Mood>("mood"))) {
        update("DROP TYPE IF EXISTS mood CASCADE")
        update("CREATE TYPE mood AS ENUM ('SAD', 'HAPPY', 'NEW')")
        val row = query("SELECT 'NEW'::mood").toList().single()
        assertEquals("22023", assertFailsWith<SqlException> { row[0] }.sqlState)
        assertEquals("NEW", row.getText(0))
    }

    @Test fun hstore() = onPostgres(CodecRegistry(HstoreCodec)) {
        update("CREATE EXTENSION IF NOT EXISTS hstore")
        update("CREATE TEMP TABLE h(v hstore)")
        val value = mapOf("a" to "1", "with \"quote\"" to "back\\slash, =>", "empty" to "", "none" to null, "юникод" to "😀")
        update("INSERT INTO h VALUES ($1::hstore)", listOf(value))
        assertEquals(value, query("SELECT v FROM h").toList().single()[0])
        assertEquals<Any?>(emptyMap<String, String?>(), query("SELECT ''::hstore").toList().single()[0])
    }

    @Test fun domainsAreTheirBaseTypes() = onPostgres(CodecRegistry(EmailCodec)) {
        update("DROP DOMAIN IF EXISTS email CASCADE")
        update("CREATE DOMAIN email AS text CHECK (VALUE LIKE '%@%')")
        update("CREATE TEMP TABLE d(e email)")
        update("INSERT INTO d VALUES ($1)", listOf(Email("a@b")))
        val row = query("SELECT e FROM d").toList().single()
        assertEquals("a@b", row[0]) // a domain is sent as its base type, so it doesn't decode by itself
        assertEquals(Email("a@b"), row.get(0, Email::class))
        assertEquals(Email("a@b"), row.get("e", Email::class))
    }

    @Test fun jsonDecodesToAClassAndNoCodecMeansAnError() = onPostgres(CodecRegistry(PointCodec)) {
        val row = query("SELECT $1::jsonb, 'text', NULL::jsonb", listOf(Point(1, -2))).toList().single()
        assertEquals(Point(1, -2), row[0]); assertEquals(null, row[2])
        val e = assertFailsWith<SqlException> { row.get(1, Email::class) }
        assertEquals("22023", e.sqlState); assertTrue("Email" in e.message!!, e.message)
    }

    @Test fun parametersOfUnknownClassesAreRefused() = onPostgres {
        assertEquals("22023", assertFailsWith<SqlException> { query("SELECT $1", listOf(Email("x"))).toList() }.sqlState)
        assertEquals(1, query("SELECT 1").toList().single()[0]) // nothing was sent
    }

    @Test fun enumsNeedNoCodecToBind() = onPostgres {
        assertEquals("HAPPY", query("SELECT $1", listOf(Mood.HAPPY)).toList().single()[0])
    }

    @Test fun registriesBelongToTheirConnections() = onPostgres(CodecRegistry(EmailCodec)) {
        val plain = connect(postgres!!, CodecRegistry())
        try {
            assertEquals("22023", assertFailsWith<SqlException> { plain.query("SELECT $1", listOf(Email("x"))).toList() }.sqlState)
        } finally {
            plain.close()
        }
        assertEquals("x", query("SELECT $1", listOf(Email("x"))).toList().single()[0])
    }

    @Test fun laterRegistrationsApply() = onPostgres(CodecRegistry()) {
        codecs.register(EmailCodec)
        assertEquals("y", query("SELECT $1", listOf(Email("y"))).toList().single()[0])
    }

    @Test fun mysqlEnumsAndCodecsByClass() = onMysql(CodecRegistry(SqlCodec.enum<Mood>(), EmailCodec)) {
        update("CREATE TEMPORARY TABLE people(name VARCHAR(20), mood ENUM('SAD', 'HAPPY'), email VARCHAR(50))")
        update("INSERT INTO people VALUES (?, ?, ?)", listOf("a", Mood.HAPPY, Email("a@b")))
        // Through the binary protocol and the text one alike.
        val cursor = executeScript("SELECT * FROM people")
        val text = (cursor.nextResult() as SqlResult.Rows).next()!!
        cursor.close()
        val binary = query("SELECT * FROM people WHERE mood = ?", listOf(Mood.HAPPY)).toList().single()
        for (row in listOf(binary, text)) {
            assertEquals("ENUM", row.columns[1].nativeTypeName)
            assertEquals("HAPPY", row[1]) // MySQL's enums have no names of their own, so it takes asking
            assertEquals(Mood.HAPPY, row.get(1, Mood::class)); assertEquals(Email("a@b"), row.get(2, Email::class))
        }
    }

    @Test fun mysqlJsonDecodesByItself() = onMysql(CodecRegistry(PointCodec)) {
        update("CREATE TEMPORARY TABLE j(v JSON)")
        update("INSERT INTO j VALUES (?)", listOf(Point(3, 4)))
        assertEquals(Point(3, 4), query("SELECT v FROM j").toList().single()[0])
    }
}
