package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    allocationZone: AllocationZone,
): ClientToServerSocket {
    val clientSocket =
        if (useAsyncChannels) {
            try {
                AsyncClientSocket(allocationZone)
            } catch (t: Throwable) {
                // It's possible Android OS version is too old to support AsyncSocketChannel
                NioClientSocket(allocationZone, useNioBlocking)
            }
        } else {
            NioClientSocket(allocationZone, useNioBlocking)
        }
    return if (tls) {
        SSLClientSocket(clientSocket)
    } else {
        clientSocket
    }
}

var useAsyncChannels = true
var useNioBlocking = false

actual fun ServerSocket.Companion.allocate(allocationZone: AllocationZone): ServerSocket = AsyncServerSocket(allocationZone)
