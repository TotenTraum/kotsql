package ru.ttraum.kotsql.ktor

/** Well under the 63 sockets that one of ktor's selectors can watch on Windows. */
internal actual val socketsPerSelector: Int = 48
