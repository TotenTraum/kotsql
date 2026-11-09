package ru.ttraum.kotsql.ktor

internal actual fun systemEnv(name: String): String? = System.getenv(name)

internal actual fun <T> blocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

internal actual val platformNormalizes: Boolean = true

internal actual fun memoryInUse(): Long {
    System.gc()
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

internal actual val extraTransports: List<ru.ttraum.kotsql.transport.TransportFactory>
    get() = listOf(ru.ttraum.kotsql.transport.SocketTransport.Factory, ru.ttraum.kotsql.transport.AsyncSocketTransport.Factory)

internal actual val ecCertificateTransports: List<ru.ttraum.kotsql.transport.TransportFactory> get() = extraTransports
