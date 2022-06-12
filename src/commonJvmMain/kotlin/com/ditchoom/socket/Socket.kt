package com.ditchoom.socket

import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun asyncClientSocket(): ClientToServerSocket = AsyncClientSocket()

actual fun clientSocket(blocking: Boolean): ClientToServerSocket =
    NioClientSocket(blocking)

actual fun asyncServerSocket(): ServerSocket = AsyncServerSocket()
