@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.Published
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** What a refused send means for the sender. */
internal sealed interface SendFault {
    /** The socket is full: wait until it is writable. */
    data object Full : SendFault

    /** This datagram only; the socket is fine. One preallocated instance per [DatagramFault]. */
    class Datagram(
        val why: DatagramFault,
    ) : SendFault

    /** The socket is gone. */
    class Socket(
        val fault: SocketFault,
    ) : SendFault
}

internal enum class DatagramFault {
    TooLarge,
    NetworkUnreachable,
    HostUnreachable,
    PortUnreachable,
    NoBufferSpace,
    PermissionDenied,
    Unnamed,
}

/** Why a socket can no longer be used. */
internal sealed interface SocketFault {
    data object Closed : SocketFault

    /** An error the mapping does not name, and the socket reports closed. */
    class Unnamed(
        val thrown: Thrown,
    ) : SocketFault

    /** [count] unnamed errors in a row with no datagram taken in between. */
    class Repeated(
        val count: Int,
        val thrown: Thrown,
    ) : SocketFault
}

/** What the I/O implementation answers when asked whether its socket still works (`isOpen`, `SO_ERROR`). */
internal enum class SocketProbe { Open, Closed }

/**
 * The one classifier of send and receive errors: per datagram, or the socket's end. Unnamed errors on a socket that
 * still reports open are that datagram's; [REPEATED_LIMIT] of them in a row, with no datagram taken in between,
 * are the socket's.
 */
@Published
internal class SocketErrors {
    private val unnamedRun = AtomicLong(0L)
    private val datagrams = Array(DatagramFault.entries.size) { SendFault.Datagram(DatagramFault.entries[it]) }

    fun classify(
        error: UdpError,
        probe: SocketProbe,
    ): SendFault =
        when (error) {
            is UdpError.WouldBlock -> SendFault.Full
            is UdpError.MessageTooLarge -> datagram(DatagramFault.TooLarge)
            is UdpError.NetworkUnreachable -> datagram(DatagramFault.NetworkUnreachable)
            is UdpError.HostUnreachable -> datagram(DatagramFault.HostUnreachable)
            is UdpError.PortUnreachable -> datagram(DatagramFault.PortUnreachable)
            is UdpError.NoBufferSpace -> datagram(DatagramFault.NoBufferSpace)
            is UdpError.PermissionDenied -> datagram(DatagramFault.PermissionDenied)
            is UdpError.SocketClosed -> SendFault.Socket(SocketFault.Closed)
            is UdpError.Unnamed -> unnamed(error.thrown, probe)
        }

    /** A datagram was taken by the network: the run of unnamed refusals is broken. */
    fun taken() {
        unnamedRun.store(0L)
    }

    private fun unnamed(
        thrown: Thrown,
        probe: SocketProbe,
    ): SendFault =
        when (probe) {
            SocketProbe.Closed -> SendFault.Socket(SocketFault.Unnamed(thrown))
            SocketProbe.Open -> {
                val run = unnamedRun.incrementAndFetch().toInt()
                if (run >= REPEATED_LIMIT) {
                    SendFault.Socket(SocketFault.Repeated(run, thrown))
                } else {
                    datagram(DatagramFault.Unnamed)
                }
            }
        }

    private fun datagram(why: DatagramFault): SendFault = datagrams[why.ordinal]

    companion object {
        /** Consecutive unnamed refusals that end a socket that keeps reporting itself open. */
        const val REPEATED_LIMIT = 8
    }
}
