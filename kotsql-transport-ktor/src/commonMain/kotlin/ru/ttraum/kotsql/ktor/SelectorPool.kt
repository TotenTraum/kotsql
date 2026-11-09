package ru.ttraum.kotsql.ktor

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How many sockets one of ktor's selectors can watch. On Windows it waits for all of them in one WSAWaitForMultipleEvents,
 * which takes at most 64 events, its own wakeup among them; past that its loop fails, and the selector with it.
 */
internal expect val socketsPerSelector: Int

/**
 * ktor's selectors, each with at most [limit] sockets, and as many as that takes. One that has died is replaced rather than
 * failing every connection after it.
 */
internal class SelectorPool(private val limit: Int, private val create: () -> SelectorManager = { SelectorManager(Dispatchers.IO) }) {
    inner class Lease internal constructor(internal val selector: Entry) {
        private var released = false

        suspend fun release() = withContext(NonCancellable) {
            mutex.withLock {
                if (released) return@withLock
                released = true
                selector.sockets--
                // An emptied selector stays if it's the only one, to spare a thread for every new connection.
                if (selector.sockets == 0 && (entries.size > 1 || selector.dead)) {
                    entries.remove(selector)
                    selector.manager.close()
                }
            }
        }

        /** Its selector no longer works: no new connection goes to it. */
        suspend fun selectorDied() = mutex.withLock { selector.dead = true }
    }

    internal class Entry(val manager: SelectorManager) {
        var sockets = 0
        var dead = false
    }

    private val mutex = Mutex()
    private val entries = ArrayList<Entry>()

    /** How many selectors there are, for tests. */
    internal suspend fun size() = mutex.withLock { entries.size }

    suspend fun acquire(): Lease = mutex.withLock {
        val entry = entries.firstOrNull { !it.dead && it.sockets < limit } ?: Entry(create()).also { entries.add(it) }
        entry.sockets++
        Lease(entry)
    }
}

/** Whether [e] says that ktor's selector has stopped, which no retry on the same one can help. */
internal fun isSelectorClosed(e: Throwable): Boolean = e.message?.startsWith("Selector closed") == true

/**
 * Whether [e] is ktor's failure to connect with error 0, which no real failure has. On Windows its native connect reads
 * WSAGetLastError() some calls after connect() itself, and can find it cleared: a connection still in progress, which it
 * should have waited for, then fails as `POSIX error 0`. A new attempt gets through.
 */
internal fun isSpuriousConnectFailure(e: Throwable): Boolean =
    generateSequence(e) { it.cause }.take(8).any { it.message?.startsWith("POSIX error 0:") == true }
