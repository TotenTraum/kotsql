package ru.ttraum.kotsql.benchmark.client

import ru.ttraum.kotsql.ktor.KtorTransport
import ru.ttraum.kotsql.transport.AsyncSocketTransport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.coroutines.runBlocking
import java.io.File

actual fun transports(): Map<String, TransportFactory> = mapOf("ktor" to KtorTransport, "async" to AsyncSocketTransport)
actual val platformName = "jvm"
actual fun environment(name: String): String? = System.getenv(name)
actual fun readTextFile(path: String): String? = File(path).takeIf { it.exists() }?.readText()
actual fun runMain(block: suspend () -> Unit) = runBlocking { block() }
