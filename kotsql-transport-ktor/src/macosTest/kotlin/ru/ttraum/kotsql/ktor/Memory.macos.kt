@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/** The peak resident size, which macOS reports in bytes. */
internal actual fun memoryInUse(): Long = memScoped {
    val usage = alloc<rusage>()
    getrusage(RUSAGE_SELF, usage.ptr)
    usage.ru_maxrss
}
