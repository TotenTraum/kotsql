@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import ru.ttraum.kotsql.ktor.openssl.OpenSSL_version
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

internal actual fun testEnv(name: String): String? = getenv(name)?.toKString()

internal actual val extraTransports: List<ru.ttraum.kotsql.transport.TransportFactory> get() = listOf(KtorTransport)

internal actual val ecCertificateTransports: List<ru.ttraum.kotsql.transport.TransportFactory> get() = tlsTransports

class OpenSslVersionTest {
    /** Guards against a transitive dependency bringing in an older build than the one in the version catalog. */
    @Test fun linksTheExpectedOpenSsl() {
        val version = OpenSSL_version(0)!!.toKString()
        println("Linked with $version")
        assertTrue(version.startsWith("OpenSSL 3.6.5"), version)
    }
}
