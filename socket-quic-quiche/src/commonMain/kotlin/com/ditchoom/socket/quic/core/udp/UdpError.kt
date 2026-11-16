package com.ditchoom.socket.quic.core.udp

/** Every platform error, mapped once, by the I/O implementation. */
internal sealed interface UdpError {
    data object WouldBlock : UdpError

    data object MessageTooLarge : UdpError

    data object NetworkUnreachable : UdpError

    data object HostUnreachable : UdpError

    data object PortUnreachable : UdpError

    data object NoBufferSpace : UdpError

    data object PermissionDenied : UdpError

    data object SocketClosed : UdpError

    /** An error the mapping does not name, with the platform's own exception. */
    class Unnamed(
        val thrown: Thrown,
    ) : UdpError
}
