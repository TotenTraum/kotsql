@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GetCurrentProcess
import ru.ttraum.kotsql.ktor.psapi.K32GetProcessMemoryInfo
import ru.ttraum.kotsql.ktor.psapi.PROCESS_MEMORY_COUNTERS

/** The peak working set, Windows' nearest to a peak resident size. */
internal actual fun memoryInUse(): Long = memScoped {
    val counters = alloc<PROCESS_MEMORY_COUNTERS>()
    check(K32GetProcessMemoryInfo(GetCurrentProcess(), counters.ptr, sizeOf<PROCESS_MEMORY_COUNTERS>().convert()) != 0)
    counters.PeakWorkingSetSize.convert()
}
