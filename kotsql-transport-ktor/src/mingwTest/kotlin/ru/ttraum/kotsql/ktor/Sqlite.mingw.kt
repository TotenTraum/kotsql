package ru.ttraum.kotsql.ktor

/** SQLite has no build for Windows. */
internal actual fun sqliteDriver(): ru.ttraum.kotsql.spi.SqlDriver? = null
