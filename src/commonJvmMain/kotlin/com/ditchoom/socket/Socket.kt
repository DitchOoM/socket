package com.ditchoom.socket

import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket
import kotlin.time.ExperimentalTime

@ExperimentalTime
@ExperimentalUnsignedTypes
actual fun asyncClientSocket(): ClientToServerSocket = AsyncClientSocket()

@ExperimentalTime
@ExperimentalUnsignedTypes
actual fun clientSocket(blocking: Boolean): ClientToServerSocket =
    NioClientSocket(blocking)

@ExperimentalUnsignedTypes
@ExperimentalTime
actual fun asyncServerSocket(): ServerSocket = AsyncServerSocket()
