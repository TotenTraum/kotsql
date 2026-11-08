@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.postgres

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.UShortVar
import platform.windows.NormalizeString

/** NormalizationKC of winnls.h. */
private const val NORMALIZATION_KC = 5u

/** Windows' own normalization, which Linux lacks without ICU. */
internal actual fun normalizeNfkc(text: String): String {
    if (text.isEmpty()) return text
    return memScoped {
        // A first call says how large the result can be; a second can still find it larger.
        var capacity: Int = NormalizeString(NORMALIZATION_KC, text, -1, null, 0)
        repeat(3) {
            if (capacity <= 0) error("NormalizeString failed")
            val target = allocArray<UShortVar>(capacity)
            val written: Int = NormalizeString(NORMALIZATION_KC, text, -1, target, capacity)
            if (written > 0) return@memScoped target.toKStringFromUtf16()
            capacity *= 2
        }
        error("NormalizeString failed")
    }
}
