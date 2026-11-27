package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.udp.UdpConnectError

/**
 * The send refusal a refused connect to the peer amounts to, as socket-udp classified it; [thrown] is what the connect
 * threw. socket-udp's unreachable is every no-path errno, so it reads as the network's, as a refused send does on a JVM.
 */
internal fun connectRefusalOf(
    error: UdpConnectError,
    thrown: Thrown,
): SendRefusal =
    when (error) {
        is UdpConnectError.Unreachable -> SendRefusal.NetworkUnreachable
        is UdpConnectError.NotPermitted, is UdpConnectError.Denied -> SendRefusal.PermissionDenied
        is UdpConnectError.AddressInUse, is UdpConnectError.LocalAddressUnavailable, is UdpConnectError.SocketUnavailable,
        is UdpConnectError.OsError, is UdpConnectError.PlatformError, is UdpConnectError.Transport,
        -> SendRefusal.Unnamed(thrown)
    }
