package com.ditchoom.socket.quic.core.udp

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.nio.channels.ClosedChannelException

/** NIO carries no errno: the exception's type names the errors it can, and any other is [UdpError.Unnamed]. */
internal fun udpErrorOf(e: IOException): UdpError =
    when (e) {
        is PortUnreachableException -> UdpError.PortUnreachable
        is NoRouteToHostException -> UdpError.HostUnreachable
        is ClosedChannelException -> UdpError.SocketClosed
        else -> UdpError.Unnamed(Thrown(e))
    }
