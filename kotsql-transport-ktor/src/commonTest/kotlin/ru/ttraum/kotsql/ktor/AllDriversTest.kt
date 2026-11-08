package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.mysql.MysqlDriver
import ru.ttraum.kotsql.postgres.PostgresDriver
import ru.ttraum.kotsql.query
import ru.ttraum.kotsql.spi.Drivers
import ru.ttraum.kotsql.sqlite.SqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** All of the drivers in one binary, whose native libraries have to get along, and each one working in it. */
class AllDriversTest {
    @Test fun inOneBinary() = runTest {
        withContext(Dispatchers.Default) {
            if (Drivers.find("postgresql") == null) Drivers.register(PostgresDriver())
            if (Drivers.find("mysql") == null) Drivers.register(MysqlDriver())
            if (Drivers.find("sqlite") == null) Drivers.register(SqliteDriver())
            val urls = listOfNotNull(
                testEnv("KOTSQL_TEST_URL"),
                testEnv("KOTSQL_TEST_MYSQL_HOST_PORT")?.let { "mysql://root:kotsql@$it/kotsql?sslmode=disable&allowPublicKeyRetrieval=true" },
                "sqlite:///:memory:",
            )
            for (url in urls) {
                val connection = connect(url)
                try {
                    assertEquals(1L, (connection.query("SELECT 1").first()[0] as Number).toLong(), url)
                } finally {
                    connection.close()
                }
            }
        }
    }
}
