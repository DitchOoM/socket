package com.ditchoom.socket.nio2

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.StandardSocketOptions
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

abstract class AsyncBaseClientSocket(
    private val allocationZone: AllocationZone,
) : ByteBufferClientSocket<AsynchronousSocketChannel>() {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(timeout: Duration): ReadBuffer {
        val receiveBuffer = socket.getOption(StandardSocketOptions.SO_RCVBUF)
        val buffer = PlatformBuffer.allocate(receiveBuffer, allocationZone) as BaseJvmBuffer
        read(buffer, timeout)
        return buffer
    }

    override suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int {
        val bytesRead =
            readMutex.withLock {
                socket.aRead(buffer.byteBuffer, timeout)
            }
        if (bytesRead < 0) {
            throw SocketClosedException("Received $bytesRead from server indicating a socket close.")
        }
        return bytesRead
    }

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        // Efficient JVM implementation using the existing method
        return read(buffer as BaseJvmBuffer, timeout)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val bytesWritten =
            writeMutex.withLock {
                socket.aWrite((buffer as BaseJvmBuffer).byteBuffer, timeout)
            }
        if (bytesWritten < 0) {
            throw SocketClosedException("Received $bytesWritten from server indicating a socket close.")
        }
        return bytesWritten
    }
}
