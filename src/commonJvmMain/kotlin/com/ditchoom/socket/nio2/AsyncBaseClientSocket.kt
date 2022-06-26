package com.ditchoom.socket.nio2

import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.TLSProps
import com.ditchoom.socket.nio.ByteBufferClientSocket
import com.ditchoom.socket.nio2.util.aRead
import com.ditchoom.socket.nio2.util.aWrite
import com.ditchoom.socket.nio2.util.assignedPort
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.Duration

abstract class AsyncBaseClientSocket(useTLS: Boolean) :
    ByteBufferClientSocket<AsynchronousSocketChannel>() {

    abstract val isClient :Boolean

    private val tlsProps = if (useTLS) {TLSProps(useClientMode = isClient)} else {null}


    override suspend fun remotePort() = socket.assignedPort(remote = true)

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        var exception: Exception? = null
        val bytesRead = try {
            val plainTextBuffer = (buffer as JvmBuffer).byteBuffer
            tlsProps?.read(buffer, socket::aRead, timeout) ?: socket.aRead(plainTextBuffer, timeout)
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
        var exception: Exception? = null
        val bytesWritten = try {
            val plainTextBuffer = (buffer as JvmBuffer).byteBuffer
            tlsProps?.write(plainTextBuffer, socket::aWrite, timeout) ?: socket.aWrite(plainTextBuffer, timeout)
        } catch (e: Exception) {
            exception = e
            -1
        }
        if (bytesWritten < 0) {
            isClosing.set(true)
            disconnectedFlow.emit(
                SocketException(
                    "Socket read channel has reached end-of-stream",
                    closeInitiatedClientSide,
                    exception
                )
            )
        }
        return bytesWritten
    }
}