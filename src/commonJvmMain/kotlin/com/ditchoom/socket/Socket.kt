package com.ditchoom.socket

import com.ditchoom.socket.nio.NioClientSocket
import com.ditchoom.socket.nio2.AsyncClientSocket
import com.ditchoom.socket.nio2.AsyncServerSocket

actual fun ClientSocket.Companion.allocate(): ClientToServerSocket =
    if (useAsyncChannels) {
        try {
            AsyncClientSocket()
        } catch (t: Throwable) {
            // It's possible Android OS version is too old to support AsyncSocketChannel
            NioClientSocket(useNioBlocking)
        }
    } else {
        NioClientSocket(useNioBlocking)
    }

var useAsyncChannels = true
var useNioBlocking = false

actual fun ServerSocket.Companion.allocate(): ServerSocket = AsyncServerSocket()
