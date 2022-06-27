package com.ditchoom.socket.nio2

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

abstract class AsyncBaseClientSocket(private val bufferFactory: () -> PlatformBuffer) :
    ByteBufferClientSocket<AsynchronousSocketChannel>() {
    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(timeout: Duration): ReadBuffer {
        val buffer = bufferFactory() as JvmBuffer
        socket.aRead(buffer.byteBuffer, timeout)
        return buffer
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        return socket.aWrite((buffer as JvmBuffer).byteBuffer, timeout)
    }
}