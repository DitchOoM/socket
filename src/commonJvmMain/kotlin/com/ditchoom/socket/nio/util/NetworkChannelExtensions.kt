package com.ditchoom.socket.nio.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketAddress
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.NetworkChannel
import java.nio.channels.SocketChannel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun NetworkChannel.localAddressOrNull(): SocketAddress? =
    try {
        localAddress
    } catch (e: Exception) {
        null
    }

suspend fun NetworkChannel.aClose() =
    withContext(Dispatchers.IO) {
        suspendCoroutine<Unit> { cont ->
            blockingClose()
            cont.resume(Unit)
        }
    }

internal fun NetworkChannel.blockingClose() {
    try {
        if (this is SocketChannel) {
            shutdownInput()
        } else if (this is AsynchronousSocketChannel) {
            shutdownInput()
        }
    } catch (_: Throwable) {
    }
    try {
        if (this is SocketChannel) {
            shutdownOutput()
        } else if (this is AsynchronousSocketChannel) {
            shutdownOutput()
        }
    } catch (_: Throwable) {
    }
    try {
        close()
    } catch (_: Throwable) {
        // Specification says that it is Ok to call it any time, but reality is different,
        // so we have just to ignore exception
    }
}
