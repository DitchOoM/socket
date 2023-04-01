package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone
): ClientToServerSocket {
    val clientSocket = if (USE_ASYNC_CHANNELS) {
        try {
            AsyncClientSocket(allocationZone)
        } catch (t: Throwable) {
            // It's possible Android OS version is too old to support AsyncSocketChannel
            NioClientSocket(allocationZone, USE_NIO_BLOCKING)
        }
    } else {
        NioClientSocket(allocationZone, USE_NIO_BLOCKING)
    }
    return if (tls) {
        SSLClientSocket(clientSocket)
    } else {
        clientSocket
    }
}

var USE_ASYNC_CHANNELS = true
var USE_NIO_BLOCKING = false

actual fun ServerSocket.Companion.allocate(
    allocationZone: AllocationZone
): ServerSocket =
    AsyncServerSocket(allocationZone)
