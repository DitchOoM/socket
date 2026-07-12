package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.remoteAddressOrNull
import com.ditchoom.socket.nio.util.write
import com.ditchoom.socket.translateRead
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
    config: TransportConfig = TransportConfig(),
) : ByteBufferClientSocket<SocketChannel>(config) {
    val selector = if (!blocking) Selector.open()!! else null

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override suspend fun remotePort() = (socket.remoteAddressOrNull() as? InetSocketAddress)?.port ?: -1

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(deadline) }
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(socket.socket().receiveBufferSize))
        try {
            read(buffer.unwrapFully() as BaseJvmBuffer, deadline)
            buffer.resetForRead()
            return buffer
        } catch (e: Exception) {
            // Return the pooled buffer instead of dropping it to the GC.
            buffer.freeNativeMemory()
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
        deadline: Duration,
    ): BytesWritten {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return BytesWritten(it.wrap(buffer.unwrapFully() as BaseJvmBuffer, deadline)) }
        return BytesWritten(rawSocketWrite(buffer, deadline))
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
