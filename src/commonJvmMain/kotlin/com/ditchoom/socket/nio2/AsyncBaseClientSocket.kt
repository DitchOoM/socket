package com.ditchoom.socket.nio2

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
abstract class AsyncBaseClientSocket :
    ByteBufferClientSocket<AsynchronousSocketChannel>() {
    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        val bytesRead = socket.aRead((buffer as JvmBuffer).byteBuffer, timeout)
        if (bytesRead < 0) {
            disconnectedFlow.emit(Unit)
        }
        return bytesRead
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        val bytesWritten = socket.aWrite((buffer as JvmBuffer).byteBuffer, timeout)
        if (bytesWritten < 0) {
            disconnectedFlow.emit(Unit)
        }
        return bytesWritten
    }
}