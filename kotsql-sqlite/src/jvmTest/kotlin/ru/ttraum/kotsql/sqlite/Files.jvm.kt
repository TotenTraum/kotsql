package ru.ttraum.kotsql.sqlite

import java.io.File

internal actual val temporaryDirectory: String get() = System.getProperty("java.io.tmpdir").trimEnd('/')
internal actual fun deleteFile(path: String) { File(path).delete() }
