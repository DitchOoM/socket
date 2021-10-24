@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.buffer.toBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
interface ClientSocket : SuspendCloseable {

    fun isOpen(): Boolean
    fun localPort(): UShort?
    fun remotePort(): UShort?
    suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int
    suspend fun read(timeout: Duration = seconds(1)) = read(timeout) { buffer, bytesRead -> buffer.readUtf8(bytesRead) }
    suspend fun <T> read(timeout: Duration = seconds(1), bufferSize: UInt = 8096u, bufferRead: (PlatformBuffer, Int) -> T): SocketDataRead<T> {
        val buffer = allocateNewBuffer(bufferSize)
        val bytesRead = read(buffer, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }
    suspend fun <T> readTyped(timeout: Duration = seconds(1), bufferRead: (PlatformBuffer) -> T) =
        read(timeout) { buffer, _ ->
            bufferRead(buffer)
        }.result

    suspend fun write(buffer: PlatformBuffer, timeout: Duration = seconds(1)): Int
    suspend fun write(buffer: String, timeout: Duration = seconds(1)): Int
            = write(buffer.toBuffer().also { it.position(it.limit().toInt()) }, timeout)

    suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration) {
        while (buffer.position() < buffer.limit()) {
            write(buffer, timeout)
        }
    }
}

data class SocketDataRead<T>(val result: T, val bytesRead: Int)

@ExperimentalTime
suspend fun openClientSocket(port: UShort,
                             timeout: Duration = seconds(1),
                             hostname: String? = null,
                             socketOptions: SocketOptions? = null): ClientToServerSocket {
    val socket = getClientSocket()
    socket.open(port, timeout, hostname, socketOptions)
    return socket
}

@ExperimentalTime
fun getClientSocket(): ClientToServerSocket {
    try {
        return asyncClientSocket()
    } catch (e: Throwable) {
        // failed to allocate async socket channel based socket, fallback to nio
    }
    return clientSocket(false)
}

@ExperimentalTime
expect fun asyncClientSocket(): ClientToServerSocket

@ExperimentalTime
expect fun clientSocket(blocking: Boolean = false): ClientToServerSocket

