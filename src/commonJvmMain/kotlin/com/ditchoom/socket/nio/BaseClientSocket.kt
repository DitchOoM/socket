package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.remoteAddressOrNull
import com.ditchoom.socket.nio.util.write
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
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
        if (!isOpen()) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(timeout) }
        val buffer = bufferFactory.allocate(socket.socket().receiveBufferSize)
        try {
            read(buffer.unwrapFully() as BaseJvmBuffer, timeout)
            buffer.resetForRead()
            return buffer
        } catch (e: Exception) {
            buffer.freeIfNeeded()
            throw e
        }
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            try {
                readMutex.withLock { socket.read(buffer.byteBuffer, selector, timeout) }
            } catch (e: IOException) {
                // Route every platform IOException (ClosedChannelException, Connection reset,
                // etc.) through the single mapper. Already-wrapped SocketException is passed
                // through unchanged. CancellationException is not an IOException → propagates.
                throw wrapJvmException(e)
            }
        if (bytesRead < 0) {
            throw SocketClosedException.EndOfStream("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (!isOpen()) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.wrap(buffer.unwrapFully() as BaseJvmBuffer, timeout) }
        return rawSocketWrite(buffer, timeout)
    }

    internal override suspend fun rawSocketWrite(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val byteBuffer = (buffer.unwrapFully() as BaseJvmBuffer).byteBuffer
        var totalWritten = 0
        try {
            writeMutex.withLock {
                while (byteBuffer.hasRemaining()) {
                    val bytesWritten = socket.write(byteBuffer, selector, timeout)
                    if (bytesWritten < 0) {
                        throw SocketClosedException.EndOfStream("Received $bytesWritten from server indicating a socket close.")
                    }
                    totalWritten += bytesWritten
                }
            }
        } catch (e: IOException) {
            // Route every platform IOException (Broken pipe, Connection reset,
            // ClosedChannelException, etc.) through the single mapper so callers see
            // SocketClosedException.BrokenPipe / .ConnectionReset / .General — not a
            // raw IOException. CancellationException is not an IOException → propagates.
            throw wrapJvmException(e)
        }
        return totalWritten
    }

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }
}
