@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.ktor.openssl.OpenSSL_version
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

internal actual fun systemEnv(name: String): String? = getenv(name)?.toKString()

internal actual fun <T> blocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

internal actual val extraTransports: List<ru.ttraum.kotsql.transport.TransportFactory> get() = listOf(KtorTransport)

internal actual val ecCertificateTransports: List<ru.ttraum.kotsql.transport.TransportFactory> get() = tlsTransports

class OpenSslVersionTest {
    /** Guards against a transitive dependency bringing in an older build than the one in the version catalog. */
    @Test fun linksTheExpectedOpenSsl() {
        val version = OpenSSL_version(0)!!.toKString()
        // OPENSSL_CPU_INFO: the CPU features OpenSSL found, which decide the code it runs. For when it fails on one machine only.
        println("Linked with $version; ${OpenSSL_version(9)?.toKString()}")
        assertTrue(version.startsWith("OpenSSL 3.6.5"), version)
    }
}
