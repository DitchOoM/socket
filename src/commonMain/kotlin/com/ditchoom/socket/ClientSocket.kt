package com.ditchoom.socket

/**
 * A client socket that can connect to a remote server.
 *
 * A [ClientSocket] **is** a [com.ditchoom.buffer.flow.ByteStream] (via [SocketController]) — there is
 * no separate adapter. Buffer allocation and read/write policy are injected once via [TransportConfig]
 * at [ClientToServerSocket.open] time, not mutated on the socket.
 */
interface ClientSocket : SocketController {
    companion object
}

suspend fun ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    config: TransportConfig = TransportConfig(),
): ClientToServerSocket {
    val socket = ClientSocket.allocate()
    socket.open(port, hostname, config)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    config: TransportConfig = TransportConfig(),
    lambda: suspend (ClientSocket) -> T,
): T {
    val socket = ClientSocket.allocate()
    return try {
        socket.open(port, hostname, config)
        lambda(socket)
    } finally {
        socket.close()
    }
}

expect fun ClientSocket.Companion.allocate(): ClientToServerSocket
