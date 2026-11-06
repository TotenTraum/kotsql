@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

/** The peak resident size, from the kernel's report on this process. */
internal actual fun memoryInUse(): Long = memScoped {
    val file = fopen("/proc/self/status", "r") ?: error("No /proc/self/status")
    try {
        val line = allocArray<ByteVar>(256)
        while (fgets(line, 256, file) != null) {
            val text = line.toKString()
            if (text.startsWith("VmHWM:")) return text.filter { it.isDigit() }.toLong() * 1024
        }
        error("No VmHWM in /proc/self/status")
    } finally {
        fclose(file)
    }
}
