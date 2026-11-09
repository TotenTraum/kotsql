package ru.ttraum.kotsql.postgres

import ru.ttraum.kotsql.SqlErrorKind
import ru.ttraum.kotsql.SqlException
import ru.ttraum.kotsql.spi.toHex
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.MD5
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

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

/** `md5(md5(password + user) + salt)`, as the legacy `md5` method prescribes. */
@OptIn(DelicateCryptographyApi::class)
internal fun md5Password(user: String, password: String, salt: ByteArray): String = computing {
    val md5 = provider.get(MD5).hasher()
    val inner = md5.hashBlocking((password + user).encodeToByteArray()).toHex()
    "md5" + md5.hashBlocking(inner.encodeToByteArray() + salt).toHex()
}

internal const val SCRAM_SHA_256 = "SCRAM-SHA-256"
internal const val SCRAM_SHA_256_PLUS = "SCRAM-SHA-256-PLUS"

/**
 * Client side of SCRAM-SHA-256 (RFC 5802, RFC 7677), with channel binding if there's [bindingData] to put in.
 *
 * @param gs2Header what the client says about channel binding, which the server's proof covers along with [bindingData]
 */
internal class ScramClient(
    private val password: String,
    private val gs2Header: String = "n,,",
    private val bindingData: ByteArray? = null,
    private val clientNonce: String = newNonce(),
) {
    private val clientFirstBare = "n=,r=$clientNonce"
    private lateinit var serverSignature: ByteArray

    fun clientFirst(): ByteArray = "$gs2Header$clientFirstBare".encodeToByteArray()

    fun clientFinal(serverFirstBytes: ByteArray): ByteArray {
        val serverFirst = serverFirstBytes.decodeToString()
        val attrs = attributes(serverFirst)
        val nonce = attrs['r'] ?: fail("no nonce")
        val saltText = attrs['s'] ?: fail("no salt")
        val iterations = attrs['i']?.toIntOrNull()?.takeIf { it > 0 } ?: fail("invalid iteration count")
        if (!nonce.startsWith(clientNonce) || nonce.length == clientNonce.length) fail("server nonce does not extend the client's")

        val salt = Base64.decode(saltText)
        val salted = computing {
            provider.get(PBKDF2)
                .secretDerivation(SHA256, iterations, 32.bytes, salt)
                .deriveSecretBlocking(saslPrep(password).encodeToByteArray())
                .let { it.toByteArray() }
        }
        val clientKey = hmac(salted, "Client Key".encodeToByteArray())
        val storedKey = computing { provider.get(SHA256).hasher().hashBlocking(clientKey) }
        val withoutProof = "c=${Base64.encode(gs2Header.encodeToByteArray() + (bindingData ?: ByteArray(0)))},r=$nonce"
        val authMessage = "$clientFirstBare,$serverFirst,$withoutProof".encodeToByteArray()
        val clientSignature = hmac(storedKey, authMessage)
        val proof = ByteArray(clientKey.size) { (clientKey[it].toInt() xor clientSignature[it].toInt()).toByte() }
        serverSignature = hmac(hmac(salted, "Server Key".encodeToByteArray()), authMessage)
        return "$withoutProof,p=${Base64.encode(proof)}".encodeToByteArray()
    }

    /** Authenticates the server. Without this an impostor could accept any client. */
    fun verifyServerFinal(serverFinalBytes: ByteArray) {
        val attrs = attributes(serverFinalBytes.decodeToString())
        attrs['e']?.let { fail("server reported '$it'") }
        val signature = attrs['v']?.let { Base64.decode(it) } ?: fail("no server signature")
        if (!signature.contentEquals(serverSignature)) fail("wrong server signature")
    }

    private fun attributes(message: String): Map<Char, String> =
        message.split(',').filter { it.length >= 2 && it[1] == '=' }.associate { it[0] to it.substring(2) }

    private fun fail(reason: String): Nothing =
        throw SqlException("SCRAM authentication failed: $reason", "28000", kind = SqlErrorKind.AUTHENTICATION)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = computing {
        provider.get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(data)
    }

    private companion object {
        fun newNonce(): String = computing { Base64.encode(CryptographyRandom.nextBytes(18)) }
    }
}
