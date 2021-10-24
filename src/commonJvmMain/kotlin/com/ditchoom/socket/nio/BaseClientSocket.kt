package com.ditchoom.socket.nio

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.socket.SocketDataRead
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.write
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
abstract class BaseClientSocket(
    protected val blocking: Boolean = false,
) : ByteBufferClientSocket<SocketChannel>() {

    val selector = if (!blocking) Selector.open()!! else null

    override fun remotePort() = (socket?.remoteAddress as? InetSocketAddress)?.port?.toUShort()

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration) =
        socket!!.read((buffer as JvmBuffer).byteBuffer, selector, timeout)

    override suspend fun <T> read(timeout: Duration, bufferSize: UInt,  bufferRead: (PlatformBuffer, Int) -> T): SocketDataRead<T> {
        val buffer = allocateNewBuffer(bufferSize) as JvmBuffer
        val byteBuffer = buffer.byteBuffer
        val bytesRead = socket!!.read(byteBuffer, selector, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration) =
        socket!!.write((buffer as JvmBuffer).byteBuffer, selector, timeout)

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }

}