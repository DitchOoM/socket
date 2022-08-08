package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket {
    val clientSocket = try {
        AsyncClientSocket(bufferFactory)
    } catch (t: Throwable) {
        NioClientSocket(bufferFactory, false)
    }
    return if (tls) {
        SSLClientSocket(clientSocket)
    } else {
        clientSocket
    }
}

actual fun ServerSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ServerSocket =
    AsyncServerSocket(bufferFactory)
