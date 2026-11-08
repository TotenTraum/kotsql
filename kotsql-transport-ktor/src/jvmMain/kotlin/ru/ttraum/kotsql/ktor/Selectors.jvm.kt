package ru.ttraum.kotsql.ktor

/** No limit here: one selector for all the sockets. */
internal actual val socketsPerSelector: Int = Int.MAX_VALUE
