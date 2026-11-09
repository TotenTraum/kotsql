package ru.ttraum.kotsql.ktor

/**
 * Half of the 63 sockets that one of ktor's selectors can watch on Windows. A closed socket leaves the pool's count at once
 * but ktor's wait only on its selector's next round, so with connections coming and going the selector holds more than this.
 */
internal actual val socketsPerSelector: Int = 32
