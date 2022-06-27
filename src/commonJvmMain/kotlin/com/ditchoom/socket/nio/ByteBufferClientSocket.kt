package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aLocalAddress
import java.net.InetSocketAddress
import java.nio.channels.NetworkChannel

abstract class ByteBufferClientSocket<T : NetworkChannel> : ClientSocket {
    protected lateinit var socket: T

    override fun isOpen() = try {
        socket.isOpen
    } catch (e: Throwable) {
        false
    }

    override suspend fun localPort(): Int = (socket.aLocalAddress() as? InetSocketAddress)?.port ?: -1

    override suspend fun close() {
        socket.aClose()
    }
}
