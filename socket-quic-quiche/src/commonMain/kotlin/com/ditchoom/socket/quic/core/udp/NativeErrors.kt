package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NativeSocketRefusal
import com.ditchoom.socket.quic.SocketRefusal

/**
 * A `qnet` refusal as the core's [UdpError], mapped once. An unreachable network or an address no longer available
 * (an outage) is each datagram's own fault, never the socket's, as on the JDK stack.
 */
internal fun udpErrorOf(refusal: SocketRefusal): UdpError =
    when (refusal) {
        SocketRefusal.EAgain -> UdpError.WouldBlock
        SocketRefusal.EMsgSize -> UdpError.MessageTooLarge
        SocketRefusal.ENetUnreach, SocketRefusal.EAddrNotAvail -> UdpError.NetworkUnreachable
        SocketRefusal.EHostUnreach -> UdpError.HostUnreachable
        SocketRefusal.EConnRefused -> UdpError.PortUnreachable
        SocketRefusal.ENoBufs -> UdpError.NoBufferSpace
        SocketRefusal.EAcces, SocketRefusal.EPerm -> UdpError.PermissionDenied
        SocketRefusal.EBadF -> UdpError.SocketClosed
        SocketRefusal.EIo, SocketRefusal.EAddrInUse, SocketRefusal.EAfNoSupport,
        SocketRefusal.ENoMem, SocketRefusal.EInval, SocketRefusal.ENoProtoOpt,
        SocketRefusal.ENoSys, SocketRefusal.EPipe, SocketRefusal.Unmapped,
        -> UdpError.Unnamed(Thrown(NativeSocketRefusal(refusal)))
    }
