package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate
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
    tls: Boolean = false,
    timeout: Duration = 15.seconds,
    tlsOptions: TlsOptions = TlsOptions.DEFAULT,
    zone: AllocationZone = AllocationZone.Direct,
): ClientToServerSocket {
    val socket = ClientSocket.allocate(tls, zone)
    socket.open(port, timeout, hostname, tlsOptions)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    tls: Boolean = false,
    timeout: Duration = 15.seconds,
    tlsOptions: TlsOptions = TlsOptions.DEFAULT,
    lambda: suspend (ClientSocket) -> T,
): T {
    val socket = ClientSocket.allocate(tls)
    return try {
        socket.open(port, timeout, hostname, tlsOptions)
        lambda(socket)
    } finally {
        socket.close()
    }
}

expect fun ClientSocket.Companion.allocate(
    tls: Boolean = false,
    allocationZone: AllocationZone = AllocationZone.Direct,
): ClientToServerSocket
