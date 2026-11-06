package dev.ksql.ktor

internal actual fun testEnv(name: String): String? = System.getenv(name)
