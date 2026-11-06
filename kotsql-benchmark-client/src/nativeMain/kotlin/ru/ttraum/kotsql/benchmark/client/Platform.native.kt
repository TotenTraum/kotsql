@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.benchmark.client

import ru.ttraum.kotsql.ktor.DefaultTransport
import ru.ttraum.kotsql.ktor.KtorTransport
import ru.ttraum.kotsql.transport.TransportFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

actual fun transports(): Map<String, TransportFactory> = mapOf("ktor" to KtorTransport, "epoll" to DefaultTransport)
actual val platformName = "native"
actual fun environment(name: String): String? = getenv(name)?.toKString()
actual fun runMain(block: suspend () -> Unit) = runBlocking { block() }

actual fun readTextFile(path: String): String? {
    val file = fopen(path, "r") ?: return null
    try {
        return memScoped {
            val line = allocArray<ByteVar>(1024)
            buildString {
                while (fgets(line, 1024, file) != null) append(line.toKString())
            }
        }
    } finally {
        fclose(file)
    }
}
