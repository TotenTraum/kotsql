@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package ru.ttraum.kotsql.postgres

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping

internal actual fun normalizeNfkc(text: String): String = NSString.create(string = text).precomposedStringWithCompatibilityMapping
