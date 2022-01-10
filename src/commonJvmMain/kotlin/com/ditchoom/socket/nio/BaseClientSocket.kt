package com.ditchoom.socket.nio

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.ParcelablePlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.socket.SocketDataRead
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aRemoteAddress
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

    override suspend fun remotePort() = (socket.aRemoteAddress() as? InetSocketAddress)?.port?.toUShort()

    override suspend fun read(buffer: ParcelablePlatformBuffer, timeout: Duration): Int {
        val bytesRead = try {
            socket.read((buffer as JvmBuffer).byteBuffer, selector, timeout)
        } catch (e: Exception) {
            -1
        }
        if (bytesRead < 0) {
            disconnectedFlow.emit(Unit)
        }
        return bytesRead
    }

    override suspend fun <T> read(
        timeout: Duration,
        bufferSize: UInt,
        bufferRead: (ParcelablePlatformBuffer, Int) -> T
    ): SocketDataRead<T> {
        val buffer = allocateNewBuffer(bufferSize) as JvmBuffer
        val byteBuffer = buffer.byteBuffer
        val bytesRead = try {
            socket.read(byteBuffer, selector, timeout)
        } catch (e: Exception) {
            -1
        }
        if (bytesRead < 0) {
            disconnectedFlow.emit(Unit)
        }
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    override suspend fun write(buffer: ParcelablePlatformBuffer, timeout: Duration): Int {
        val bytesWritten = try {
            socket.write((buffer as JvmBuffer).byteBuffer, selector, timeout)
        } catch (e: Exception) {
            -1
        }
        if (bytesWritten < 0) {
            disconnectedFlow.emit(Unit)
        }
        return bytesWritten
    }

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }

}