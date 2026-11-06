package ru.ttraum.kotsql.postgres

/** Unicode's compatibility composition, as far as this platform has it. */
internal expect fun normalizeNfkc(text: String): String

private fun isMappedToSpace(c: Int) = c == 0xA0 || c == 0x1680 || c in 0x2000..0x200B || c == 0x202F || c == 0x205F || c == 0x3000

private fun isMappedToNothing(c: Int) = c == 0xAD || c == 0x34F || c == 0x1806 || c in 0x180B..0x180D || c in 0x200C..0x200D || c == 0x2060 ||
    c in 0xFE00..0xFE0F || c == 0xFEFF

/** RFC 3454's tables C.1.2 to C.9, which RFC 4013 prohibits. */
private fun isProhibited(c: Int) = isMappedToSpace(c) || c in 0..0x1F || c == 0x7F || c in 0x80..0x9F || c == 0x6DD || c == 0x70F || c == 0x180E ||
    c in 0x200C..0x200F || c in 0x2028..0x202E || c in 0x2060..0x2063 || c in 0x206A..0x206F || c == 0xFEFF || c in 0xFFF9..0xFFFD ||
    c in 0x1D173..0x1D17A || c in 0xE000..0xF8FF || c in 0xF0000..0xFFFFD || c in 0x100000..0x10FFFD || c in 0xFDD0..0xFDEF ||
    (c and 0xFFFE) == 0xFFFE || c in 0xD800..0xDFFF || c in 0x2FF0..0x2FFB || c == 0x340 || c == 0x341 || c == 0xE0001 || c in 0xE0020..0xE007F

private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val c = this[i++]
        if (c.isHighSurrogate() && i < length && this[i].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (this[i++].code - 0xDC00))
        } else {
            action(c.code)
        }
    }
}

private fun StringBuilder.appendCodePoint(c: Int) {
    if (c < 0x10000) {
        append(c.toChar())
    } else {
        append((0xD800 + ((c - 0x10000) shr 10)).toChar())
        append((0xDC00 + ((c - 0x10000) and 0x3FF)).toChar())
    }
}

/**
 * The form of a password that SCRAM hashes, by RFC 4013, which is what the server has applied to it when it was set: a
 * password that looks the same can be different characters, depending on what it was typed on. A password that this doesn't
 * allow is used as it is, as the server does when setting one. The bidirectional check of the RFC is left out: it can only
 * reject a password, and then only make a difference to one that normalization changes too.
 */
internal fun saslPrep(password: String): String {
    if (password.all { it.code in 0x20..0x7E }) return password
    val mapped = StringBuilder(password.length)
    password.forEachCodePoint { c ->
        when {
            isMappedToNothing(c) -> Unit
            isMappedToSpace(c) -> mapped.append(' ')
            else -> mapped.appendCodePoint(c)
        }
    }
    val normalized = normalizeNfkc(mapped.toString())
    normalized.forEachCodePoint { c -> if (isProhibited(c)) return password }
    return normalized
}
