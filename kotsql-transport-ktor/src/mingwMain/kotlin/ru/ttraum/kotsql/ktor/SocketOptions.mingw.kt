@file:OptIn(ExperimentalForeignApi::class)

package ru.ttraum.kotsql.ktor

import io.ktor.network.selector.Selectable
import io.ktor.network.sockets.Socket
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.windows.getsockopt
import platform.windows.setsockopt

// Winsock's values, which differ from POSIX for the socket level.
private const val IPPROTO_TCP = 6
private const val TCP_NODELAY = 1
private const val SOL_SOCKET = 0xFFFF
private const val SO_KEEPALIVE = 8

private fun descriptorOf(socket: Socket) = (socket as Selectable).descriptor.convert<ULong>()

/** As on the other native targets: ktor's sockets ignore `noDelay` and `keepAlive`. */
internal actual fun applySocketOptions(socket: Socket) {
    val descriptor = descriptorOf(socket)
    setFlag(descriptor, IPPROTO_TCP, TCP_NODELAY)
    setFlag(descriptor, SOL_SOCKET, SO_KEEPALIVE)
}

private fun setFlag(descriptor: ULong, level: Int, option: Int) = memScoped {
    val on = alloc<IntVar>()
    on.value = 1
    // Failing to tune the socket is no reason to give up on the connection.
    setsockopt(descriptor, level, option, on.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().convert())
}

internal fun socketFlag(socket: Socket, keepAlive: Boolean): Boolean = memScoped {
    val value = alloc<IntVar>()
    val size = alloc<IntVar>()
    size.value = sizeOf<IntVar>().convert()
    val level = if (keepAlive) SOL_SOCKET else IPPROTO_TCP
    val option = if (keepAlive) SO_KEEPALIVE else TCP_NODELAY
    check(getsockopt(descriptorOf(socket), level, option, value.ptr.reinterpret<ByteVar>(), size.ptr) == 0)
    value.value != 0
}
