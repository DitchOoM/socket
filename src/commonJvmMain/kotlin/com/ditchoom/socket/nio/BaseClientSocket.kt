package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.remoteAddressOrNull
import com.ditchoom.socket.nio.util.write
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.time.Duration

abstract class BaseClientSocket(
    protected val blocking: Boolean = false,
) : ByteBufferClientSocket<SocketChannel>() {
    val selector = if (!blocking) Selector.open()!! else null

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override suspend fun remotePort() = (socket.remoteAddressOrNull() as? InetSocketAddress)?.port ?: -1

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!isOpen()) throw SocketClosedException("Socket is closed.")
        tlsHandler?.let { return it.unwrap(timeout) }
        val buffer = bufferFactory.allocate(socket.socket().receiveBufferSize) as BaseJvmBuffer
        read(buffer, timeout)
        return buffer
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            try {
                readMutex.withLock { socket.read(buffer.byteBuffer, selector, timeout) }
            } catch (e: ClosedChannelException) {
                throw SocketClosedException("Socket is closed.", e)
            }
        if (bytesRead < 0) {
            throw SocketClosedException("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (!isOpen()) throw SocketClosedException("Socket is closed.")
        tlsHandler?.let { return it.wrap((buffer as PlatformBuffer).unwrap() as BaseJvmBuffer, timeout) }
        return rawSocketWrite(buffer, timeout)
    }

    internal override suspend fun rawSocketWrite(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val byteBuffer = ((buffer as PlatformBuffer).unwrap() as BaseJvmBuffer).byteBuffer
        var totalWritten = 0
        try {
            writeMutex.withLock {
                while (byteBuffer.hasRemaining()) {
                    val bytesWritten = socket.write(byteBuffer, selector, timeout)
                    if (bytesWritten < 0) {
                        throw SocketClosedException("Received $bytesWritten from server indicating a socket close.")
                    }
                    totalWritten += bytesWritten
                }
            }
        } catch (e: ClosedChannelException) {
            throw SocketClosedException("Socket is closed.", e)
        }
        return totalWritten
    }

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }
}
