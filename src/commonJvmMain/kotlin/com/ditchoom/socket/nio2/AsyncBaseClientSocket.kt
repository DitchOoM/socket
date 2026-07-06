package com.ditchoom.socket.nio2

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import com.ditchoom.socket.translateRead
import com.ditchoom.socket.wrapJvmException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

abstract class AsyncBaseClientSocket : ByteBufferClientSocket<AsynchronousSocketChannel>() {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (!isOpen) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(deadline) }
        val receiveBuffer = socket.getOption(StandardSocketOptions.SO_RCVBUF)
        val buffer = readBufferSource.acquire(effectiveReadBufferSize(receiveBuffer))
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
                readMutex.withLock {
                    socket.aRead(buffer.byteBuffer, timeout)
                }
            } catch (e: IOException) {
                // Route every platform IOException through the single mapper. The async
                // failed() callback also wraps; this catches synchronous throws from
                // channel setup and passes already-wrapped SocketException through.
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
                    val bytesWritten = socket.aWrite(byteBuffer, timeout)
                    if (bytesWritten < 0) {
                        throw SocketClosedException.EndOfStream("Received $bytesWritten from server indicating a socket close.")
                    }
                    totalWritten += bytesWritten
                }
            }
        } catch (e: IOException) {
            // Route every platform IOException (Broken pipe, Connection reset, etc.)
            // through the single mapper. The async failed() callback also wraps; this
            // catches synchronous throws and passes already-wrapped SocketException through.
            throw wrapJvmException(e)
        }
        return totalWritten
    }
}
