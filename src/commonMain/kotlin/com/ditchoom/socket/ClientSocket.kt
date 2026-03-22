package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
import com.ditchoom.data.Writer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A client socket that can connect to a remote server.
 * Provides both reading and writing capabilities with suspend functions.
 */
interface ClientSocket :
    SocketController,
    Reader,
    Writer,
    SuspendCloseable {
    companion object
}

suspend fun ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 15.seconds,
    socketOptions: SocketOptions = SocketOptions(),
    zone: AllocationZone = AllocationZone.Direct,
): ClientToServerSocket {
    val socket = ClientSocket.allocate(zone)
    socket.open(port, timeout, hostname, socketOptions)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    timeout: Duration = 15.seconds,
    socketOptions: SocketOptions = SocketOptions(),
    lambda: suspend (ClientSocket) -> T,
): T {
    val socket = ClientSocket.allocate()
    return try {
        socket.open(port, timeout, hostname, socketOptions)
        lambda(socket)
    } finally {
        socket.close()
    }
}

expect fun ClientSocket.Companion.allocate(allocationZone: AllocationZone = AllocationZone.Direct): ClientToServerSocket
