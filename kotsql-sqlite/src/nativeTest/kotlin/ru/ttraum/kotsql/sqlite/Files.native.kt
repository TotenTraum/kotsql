package ru.ttraum.kotsql.sqlite

internal actual val temporaryDirectory: String get() = "/tmp"
internal actual fun deleteFile(path: String) { platform.posix.remove(path) }
