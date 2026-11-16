package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.jvmSendErrorOf
import java.io.IOException
import java.nio.channels.ClosedChannelException

/**
 * NIO carries no errno: socket-udp's [jvmSendErrorOf] reads the JDK's exception types and strerror phrases once, and
 * this folds its answer into the core's [UdpError]. An unreachable network (an outage) is each datagram's own fault,
 * never the socket's, so an outage cannot count towards the socket's end.
 */
internal fun udpErrorOf(e: IOException): UdpError =
    when (val error = jvmSendErrorOf(e, attempted = 0, limit = 0)) {
        is DatagramSendError.PortUnreachable -> UdpError.PortUnreachable
        is DatagramSendError.Unreachable -> UdpError.NetworkUnreachable
        is DatagramSendError.TooLarge -> UdpError.MessageTooLarge
        is DatagramSendError.WouldBlock -> UdpError.NoBufferSpace
        is DatagramSendError.NotPermitted -> UdpError.PermissionDenied
        is DatagramSendError.SourceAddressUnavailable -> UdpError.NetworkUnreachable
        is DatagramSendError.Transport -> if (e is ClosedChannelException) UdpError.SocketClosed else UdpError.Unnamed(Thrown(e))
        is DatagramSendError.OsError, is DatagramSendError.PlatformError -> UdpError.Unnamed(Thrown(e))
    }
