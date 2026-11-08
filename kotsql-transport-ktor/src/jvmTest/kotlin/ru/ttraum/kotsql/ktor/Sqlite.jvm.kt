package ru.ttraum.kotsql.ktor

internal actual fun sqliteDriver(): ru.ttraum.kotsql.spi.SqlDriver? = ru.ttraum.kotsql.sqlite.SqliteDriver()
