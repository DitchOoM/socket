package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket
import kotlinx.coroutines.CoroutineScope

actual fun ClientSocket.Companion.allocate(
    tls: Boolean,
    bufferFactory: () -> PlatformBuffer
): ClientToServerSocket {
    val clientSocket = try {
        AsyncClientSocket(bufferFactory)
    } catch (t: Throwable) {
        // It's possible Android OS version is too old to support AsyncSocketChannel
        NioClientSocket(bufferFactory, false)
    }
    return if (tls) {
        SSLClientSocket(clientSocket)
    } else {
        clientSocket
    }
}

actual fun ServerSocket.Companion.allocate(
    scope: CoroutineScope,
    bufferFactory: () -> PlatformBuffer
): ServerSocket =
    AsyncServerSocket(scope, bufferFactory)
