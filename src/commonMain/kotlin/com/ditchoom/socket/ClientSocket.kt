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
    val socket = ClientSocket.allocate(config)
    socket.open(port, hostname)
    return socket
}

suspend fun <T> ClientSocket.Companion.connect(
    port: Int,
    hostname: String? = null,
    config: TransportConfig = TransportConfig(),
    lambda: suspend (ClientSocket) -> T,
): T {
    val socket = ClientSocket.allocate(config)
    return try {
        socket.open(port, hostname)
        lambda(socket)
    } finally {
        socket.close()
    }
}

/**
 * Allocates the platform client socket, injecting [config] **at allocation time** so the concrete
 * implementation can be chosen from it (e.g. JVM I/O strategy; datagram-vs-stream for UDP) and so the
 * connection's [com.ditchoom.buffer.flow.ReadPolicy] / [com.ditchoom.buffer.flow.WritePolicy] are real
 * immutable vals rather than reassigned at [ClientToServerSocket.open]. Config is injected once, here —
 * [ClientToServerSocket.open] no longer takes it.
 */
expect fun ClientSocket.Companion.allocate(config: TransportConfig = TransportConfig()): ClientToServerSocket
