@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ru.ttraum.kotsql.sqlite

import kotlinx.cinterop.toKString
import platform.posix.getenv

/** TMPDIR on Unix, TEMP on Windows, which has no /tmp. */
internal actual val temporaryDirectory: String
    get() = getenv("TMPDIR")?.toKString()?.trimEnd('/')
        ?: getenv("TEMP")?.toKString()?.trimEnd('\\')
        ?: "/tmp"

internal actual fun deleteFile(path: String) { platform.posix.remove(path) }
