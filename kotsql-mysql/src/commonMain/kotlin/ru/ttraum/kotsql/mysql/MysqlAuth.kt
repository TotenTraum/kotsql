@file:OptIn(DelicateCryptographyApi::class)

package ru.ttraum.kotsql.mysql

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import ru.ttraum.kotsql.SqlException

private val provider get() = CryptographyProvider.Default

/**
 * Runs the cryptography of the authentication. Its failure is this client's, as when the platform's crypto library won't
 * work, and must not pass for a malformed message from the server, which is what the reader would otherwise make of it.
 */
private inline fun <T> computing(block: () -> T): T = try {
    block()
} catch (e: SqlException) {
    throw e
} catch (e: Exception) {
    throw SqlException("Cannot compute the authentication response: ${e.message}", "08001", cause = e)
}

private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it % b.size].toInt()).toByte() }

/** `SHA1(password) XOR SHA1(nonce + SHA1(SHA1(password)))` */
internal fun nativePasswordToken(password: String, nonce: ByteArray): ByteArray {
    if (password.isEmpty()) return ByteArray(0)
    return computing {
        val sha1 = provider.get(SHA1).hasher()
        val first = sha1.hashBlocking(password.encodeToByteArray())
        xor(first, sha1.hashBlocking(nonce + sha1.hashBlocking(first)))
    }
}

/** `SHA256(password) XOR SHA256(SHA256(SHA256(password)) + nonce)` */
internal fun cachingSha2Token(password: String, nonce: ByteArray): ByteArray {
    if (password.isEmpty()) return ByteArray(0)
    return computing {
        val sha256 = provider.get(SHA256).hasher()
        val first = sha256.hashBlocking(password.encodeToByteArray())
        xor(first, sha256.hashBlocking(sha256.hashBlocking(first) + nonce))
    }
}

/** The password for `caching_sha2_password`'s full authentication without TLS: masked with the nonce and encrypted. */
internal fun encryptPassword(password: String, nonce: ByteArray, publicKeyPem: ByteArray): ByteArray = computing {
    val key = provider.get(RSA.OAEP).publicKeyDecoder(SHA1).decodeFromByteArrayBlocking(RSA.PublicKey.Format.PEM, publicKeyPem)
    key.encryptor().encryptBlocking(xor(password.encodeToByteArray() + 0, nonce))
}
