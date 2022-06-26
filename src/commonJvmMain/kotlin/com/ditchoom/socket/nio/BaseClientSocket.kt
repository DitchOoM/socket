package com.ditchoom.socket.nio

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.socket.SocketDataRead
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.TLSProps
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aRemoteAddress
import com.ditchoom.socket.nio.util.read
import com.ditchoom.socket.nio.util.write
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import kotlin.time.Duration

abstract class BaseClientSocket(
    useTLS: Boolean,
    protected val blocking: Boolean = false,
    override val allocationZone: AllocationZone = AllocationZone.Direct
) : ByteBufferClientSocket<SocketChannel>() {

    val selector = if (!blocking) Selector.open()!! else null
    private val tlsProps = if (useTLS) { TLSProps(useClientMode = true) } else {null}


    override suspend fun remotePort() = (socket.aRemoteAddress() as? InetSocketAddress)?.port ?: -1

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        val buffer = buffer as JvmBuffer
        var exception: Exception? = null
        val bytesRead = try {
            tlsProps?.read(buffer, { currentBuffer, currentTimeout ->
                socket.read(currentBuffer, selector, currentTimeout)
            }, timeout)
                ?: socket.read(buffer.byteBuffer, selector, timeout)
        } catch (e: Exception) {
            exception = e
            -1
        }
        if (bytesRead < 0) {
            isClosing.set(true)
            disconnectedFlow.emit(
                SocketException(
                    "Socket read channel has reached end-of-stream",
                    closeInitiatedClientSide,
                    exception
                )
            )
        }
        return bytesRead
    }


    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        val byteBuffer = (buffer as JvmBuffer).byteBuffer
        var exception: Exception? = null
        val bytesWritten = try {
            tlsProps?.write(byteBuffer, { currentBuffer, currentTimeout ->
                socket.write(currentBuffer, selector, currentTimeout)
            }, timeout) ?: socket.write(byteBuffer, selector, timeout)
        } catch (e: Exception) {
            exception = e
            -1
        }
        if (bytesWritten < 0) {
            isClosing.set(true)
            disconnectedFlow.emit(
                SocketException(
                    "Socket write channel has reached end-of-stream",
                    closeInitiatedClientSide,
                    exception
                )
            )
        }
        return bytesWritten
    }

    override suspend fun close() {
        selector?.aClose()
        super.close()
    }

}