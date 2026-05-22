package com.ditchoom.socket.nio2

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
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

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!isOpen()) throw SocketClosedException.General("Socket is closed.")
        tlsHandler?.let { return it.unwrap(timeout) }
        val receiveBuffer = socket.getOption(StandardSocketOptions.SO_RCVBUF)
        val buffer = bufferFactory.allocate(receiveBuffer)
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

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        tlsHandler?.let { tls ->
            val decrypted = tls.unwrap(timeout)
            val bytesAvailable = decrypted.remaining()
            if (bytesAvailable > 0) {
                buffer.write(decrypted)
            }
            return bytesAvailable
        }
        return read((buffer as ReadBuffer).unwrapFully() as BaseJvmBuffer, timeout)
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
