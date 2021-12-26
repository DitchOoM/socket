@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.*
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
interface ClientSocket : SocketController, Reader<ReadBuffer>, Writer<PlatformBuffer>, SuspendCloseable {

    override fun isOpen(): Boolean
    suspend fun localPort(): UShort?
    suspend fun remotePort(): UShort?
    suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int
    override suspend fun readData(timeout: Duration) = readBuffer(timeout).result
    suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> =
        read(timeout) { buffer, _ -> buffer }

    suspend fun read(timeout: Duration = seconds(1)) = read(timeout) { buffer, bytesRead -> buffer.readUtf8(bytesRead) }
    suspend fun <T> read(
        timeout: Duration = seconds(1),
        bufferSize: UInt = 4u * 1024u,
        bufferRead: (PlatformBuffer, Int) -> T
    ): SocketDataRead<T> {
        val buffer = allocateNewBuffer(bufferSize)
        val bytesRead = read(buffer, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    suspend fun <T> readTyped(timeout: Duration = seconds(1), bufferRead: (PlatformBuffer) -> T) =
        read(timeout) { buffer, _ ->
            bufferRead(buffer)
        }.result

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int
    suspend fun write(buffer: String, timeout: Duration = seconds(1)): Int =
        write(buffer.toBuffer().also { it.position(it.limit().toInt()) }, timeout)

    suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration) {
        while (buffer.position() < buffer.limit()) {
            write(buffer, timeout)
        }
    }
}

data class SocketDataRead<T>(val result: T, val bytesRead: Int)

@ExperimentalTime
suspend fun openClientSocket(
    port: UShort,
    timeout: Duration = seconds(1),
    hostname: String? = null,
    socketOptions: SocketOptions? = null
): ClientToServerSocket {
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

