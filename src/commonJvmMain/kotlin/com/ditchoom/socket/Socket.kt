package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ClientToServerSocket = try {
    AsyncClientSocket(bufferFactory)
} catch (t: Throwable) {
    NioClientSocket(bufferFactory, false)
}

actual fun ServerSocket.Companion.allocate(bufferFactory: () -> PlatformBuffer): ServerSocket =
    AsyncServerSocket(bufferFactory)
