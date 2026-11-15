package com.ditchoom.socket.quic

/**
 * What the operating system gave one of a QUIC server's UDP sockets for its kernel receive buffer.
 *
 * Every connection a server holds sends to the same socket, and what arrives while that socket's buffer is full
 * is dropped. A server with many connections needs [Granted]; [Capped] means the host's limit is below what the
 * server asked for, and raising it is the operator's to do (Linux: `sysctl net.core.rmem_max`).
 */
sealed interface UdpReceiveBuffer {
    /** The buffer holds [usableBytes], at least the [requestedBytes] the server asked for. */
    data class Granted(
        val requestedBytes: Int,
        val usableBytes: Int,
    ) : UdpReceiveBuffer

    /** The host's limit is below the request: the buffer holds [usableBytes]. */
    data class Capped(
        val requestedBytes: Int,
        val usableBytes: Int,
    ) : UdpReceiveBuffer

    /** The size could not be set, or not read back: the buffer is the host's default. */
    data class Unread(
        val requestedBytes: Int,
    ) : UdpReceiveBuffer

    /** The socket is not one the server can size. */
    data object Unsupported : UdpReceiveBuffer
}
