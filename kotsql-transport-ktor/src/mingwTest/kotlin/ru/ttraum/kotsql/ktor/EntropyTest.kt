@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import ru.ttraum.kotsql.ktor.bcrypt.BCryptGenRandom
import ru.ttraum.kotsql.ktor.openssl.RAND_bytes
import ru.ttraum.kotsql.ktor.openssl.RAND_status
import kotlin.test.Test
import kotlin.test.assertEquals

/** BCRYPT_USE_SYSTEM_PREFERRED_RNG */
private const val SYSTEM_PREFERRED_RNG = 2u

/**
 * TLS and SCRAM need OpenSSL's random number generator, which seeds itself from BCryptGenRandom on Windows. If either fails
 * here, they all fail, with "entropy source strength too weak": this says which of the two it is.
 */
class EntropyTest {
    @Test fun windowsAndOpenSslGiveRandomBytes() {
        val bytes = ByteArray(32)
        val status = bytes.usePinned { BCryptGenRandom(null, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.toUInt(), SYSTEM_PREFERRED_RNG) }
        val seeded = RAND_status()
        val generated = ByteArray(32).usePinned { RAND_bytes(it.addressOf(0).reinterpret(), 32) }
        println("BCryptGenRandom: 0x${status.toUInt().toString(16)}, OpenSSL RAND_status: $seeded, RAND_bytes: $generated")
        assertEquals(0, status, "BCryptGenRandom failed: the system gives no entropy")
        assertEquals(1, seeded, "OpenSSL's generator could not seed itself")
        assertEquals(1, generated, "OpenSSL's generator gave no bytes")
    }
}
