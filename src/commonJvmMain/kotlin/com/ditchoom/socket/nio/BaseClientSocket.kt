package com.ditchoom.socket.nio

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aRemoteAddress
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.write
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.time.Duration

abstract class BaseClientSocket(
    private val bufferFactory: () -> PlatformBuffer,
    protected val blocking: Boolean = false,
) : ByteBufferClientSocket<SocketChannel>() {

    val selector = if (!blocking) Selector.open()!! else null

    override suspend fun remotePort() = (socket.aRemoteAddress() as? InetSocketAddress)?.port ?: -1

    override suspend fun read(timeout: Duration): ReadBuffer {
        val buffer = bufferFactory() as JvmBuffer
        read(buffer, timeout)
        return buffer
    }

    override suspend fun read(buffer: JvmBuffer, timeout: Duration): Int {
        val bytesRead = socket.read(buffer.byteBuffer, selector, timeout)
        if (bytesRead < 0) {
            throw SocketClosedException("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        val bytesWritten = socket.write((buffer as JvmBuffer).byteBuffer, selector, timeout)
        if (bytesWritten < 0) {
            throw SocketClosedException("Received $bytesWritten from server indicating a socket close.")
        }
        return bytesWritten
    }

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }
}
