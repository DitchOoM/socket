package com.ditchoom.socket.nio

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.localAddressOrNull
import java.net.InetSocketAddress
import java.nio.channels.NetworkChannel
import kotlin.time.Duration

abstract class ByteBufferClientSocket<T : NetworkChannel> : ClientSocket {
    protected lateinit var socket: T

    override fun isOpen() =
        try {
            socket.isOpen
        } catch (e: Throwable) {
            false
        }

    override suspend fun localPort(): Int = (socket.localAddressOrNull() as? InetSocketAddress)?.port ?: -1

    abstract suspend fun read(
        buffer: JvmBuffer,
        timeout: Duration,
    ): Int

    override suspend fun close() {
        socket.aClose()
    }
}
