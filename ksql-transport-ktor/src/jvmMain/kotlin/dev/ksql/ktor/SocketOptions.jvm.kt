package dev.ksql.ktor

import io.ktor.network.sockets.Socket

/** ktor applies the options from the socket builder on the JVM. */
internal actual fun applySocketOptions(socket: Socket) = Unit
