@file:OptIn(ExperimentalForeignApi::class)

package dev.ksql.ktor

import io.ktor.network.selector.Selectable
import io.ktor.network.sockets.Socket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.SOL_SOCKET
import platform.posix.SO_KEEPALIVE
import platform.posix.getsockopt
import platform.posix.setsockopt
import platform.posix.socklen_tVar

// The same on Linux and macOS, and not all targets' bindings have them.
private const val IPPROTO_TCP = 6
private const val TCP_NODELAY = 1

private fun descriptorOf(socket: Socket): Int = (socket as Selectable).descriptor

/**
 * ktor's native sockets ignore the `noDelay` and `keepAlive` options, so they are set here. Without TCP_NODELAY any request
 * that takes more than one packet, such as a batch, waits about 40 ms for the server's delayed acknowledgement.
 */
internal actual fun applySocketOptions(socket: Socket) {
    val descriptor = descriptorOf(socket)
    setFlag(descriptor, IPPROTO_TCP, TCP_NODELAY)
    setFlag(descriptor, SOL_SOCKET, SO_KEEPALIVE)
}

private fun setFlag(descriptor: Int, level: Int, option: Int) = memScoped {
    val on = alloc<IntVar>()
    on.value = 1
    // Failing to tune the socket is no reason to give up on the connection.
    setsockopt(descriptor, level, option, on.ptr, sizeOf<IntVar>().convert())
}

internal fun socketFlag(socket: Socket, keepAlive: Boolean): Boolean = memScoped {
    val value = alloc<IntVar>()
    val size = alloc<socklen_tVar>()
    size.value = sizeOf<IntVar>().convert()
    val level = if (keepAlive) SOL_SOCKET else IPPROTO_TCP
    val option = if (keepAlive) SO_KEEPALIVE else TCP_NODELAY
    check(getsockopt(descriptorOf(socket), level, option, value.ptr, size.ptr) == 0)
    value.value != 0
}
