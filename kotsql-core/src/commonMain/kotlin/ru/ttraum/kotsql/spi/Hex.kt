package ru.ttraum.kotsql.spi

import ru.ttraum.kotsql.InternalKotsqlApi

internal const val HEX = "0123456789abcdef"

@InternalKotsqlApi
public fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        append(HEX[(b.toInt() ushr 4) and 0xF])
        append(HEX[b.toInt() and 0xF])
    }
}
