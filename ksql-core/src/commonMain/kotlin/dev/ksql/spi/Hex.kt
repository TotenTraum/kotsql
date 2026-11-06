package dev.ksql.spi

internal const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        append(HEX[(b.toInt() ushr 4) and 0xF])
        append(HEX[b.toInt() and 0xF])
    }
}
