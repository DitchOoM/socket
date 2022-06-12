package com.ditchoom.socket

import com.ditchoom.buffer.*
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ClientSocket : SocketController, Reader<ReadBuffer>, Writer<PlatformBuffer>, SuspendCloseable {
    val allocationZone: AllocationZone
        get() = AllocationZone.Direct

    override fun isOpen(): Boolean
    suspend fun localPort(): UShort?
    suspend fun remotePort(): UShort?
    suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int
    override suspend fun readData(timeout: Duration) = readBuffer(timeout).result
    suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> =
        read(timeout) { buffer, _ -> buffer }

    suspend fun read(timeout: Duration = 1.seconds) = read(timeout) { buffer, bytesRead -> buffer.readUtf8(bytesRead) }
    suspend fun <T> read(
        timeout: Duration = 1.seconds,
        bufferSize: Int = 4 * 1024,
        bufferRead: (PlatformBuffer, Int) -> T
    ): SocketDataRead<T> {

        val buffer = PlatformBuffer.allocate(bufferSize, zone = allocationZone)
        val bytesRead = read(buffer, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    suspend fun <T> readTyped(timeout: Duration = 1.seconds, bufferRead: (PlatformBuffer) -> T) =
        read(timeout) { buffer, _ ->
            bufferRead(buffer)
        }.result

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int
    suspend fun write(buffer: String, timeout: Duration = 1.seconds): Int =
        write(buffer.toBuffer().also { it.position(it.limit()) }, timeout)

    suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration) {
        while (buffer.position() < buffer.limit()) {
            write(buffer, timeout)
        }
    }
}

data class SocketDataRead<T>(val result: T, val bytesRead: Int)

suspend fun openClientSocket(
    port: UShort,
    timeout: Duration = 1.seconds,
    hostname: String? = null,
    socketOptions: SocketOptions? = null
): ClientToServerSocket {
    val socket = getClientSocket()
    socket.open(port, timeout, hostname, socketOptions)
    return socket
}

fun getClientSocket(zone: AllocationZone = AllocationZone.Direct): ClientToServerSocket {
    try {
        return asyncClientSocket(zone)
    } catch (e: Throwable) {
        // failed to allocate async socket channel based socket, fallback to nio
    }
    return clientSocket(zone, false)
}

expect fun asyncClientSocket(zone: AllocationZone = AllocationZone.Direct): ClientToServerSocket

expect fun clientSocket(zone: AllocationZone = AllocationZone.Direct, blocking: Boolean = false): ClientToServerSocket
