package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ClientSocket : SocketController, Reader, Writer, SuspendCloseable {
    companion object
}

suspend fun ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 1.seconds,
    socketOptions: SocketOptions? = null,
): ClientToServerSocket {
    val socket = ClientSocket.allocate()
    socket.open(port, timeout, hostname, socketOptions)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 1.seconds,
    socketOptions: SocketOptions? = null,
    lambda: suspend (ClientSocket) -> T
): T {
    val socket = ClientSocket.allocate()
    socket.open(port, timeout, hostname, socketOptions)
    val result = lambda(socket)
    socket.close()
    return result
}

expect fun ClientSocket.Companion.allocate(
    bufferFactory: () -> PlatformBuffer = {
        PlatformBuffer.allocate(8 * 1024, AllocationZone.Direct)
    }
): ClientToServerSocket

