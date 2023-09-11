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

val EMPTY_BUFFER = PlatformBuffer.allocate(0)

suspend fun ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    tls: Boolean = false,
    timeout: Duration = 15.seconds,
    zone: AllocationZone = AllocationZone.Direct
): ClientToServerSocket {
    val socket = ClientSocket.allocate(tls, zone)
    socket.open(port, timeout, hostname)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    tls: Boolean = false,
    timeout: Duration = 15.seconds,
    lambda: suspend (ClientSocket) -> T
): T {
    val socket = ClientSocket.allocate(tls)
    socket.open(port, timeout, hostname)
    val result = lambda(socket)
    socket.close()
    return result
}

expect fun ClientSocket.Companion.allocate(
    tls: Boolean = false,
    allocationZone: AllocationZone = AllocationZone.Direct
): ClientToServerSocket
