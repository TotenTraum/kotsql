@file:OptIn(ExperimentalForeignApi::class)

package dev.ksql.ktor

import dev.ksql.ktor.openssl.OpenSSL_version
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

internal actual fun testEnv(name: String): String? = getenv(name)?.toKString()

class OpenSslVersionTest {
    /** Guards against a transitive dependency bringing in an older build than the one in the version catalog. */
    @Test fun linksTheExpectedOpenSsl() {
        val version = OpenSSL_version(0)!!.toKString()
        println("Linked with $version")
        assertTrue(version.startsWith("OpenSSL 3.6.5"), version)
    }
}
