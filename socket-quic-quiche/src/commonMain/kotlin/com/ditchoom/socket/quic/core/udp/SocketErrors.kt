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

    /** This datagram only; the socket is fine. One preallocated instance per named [DatagramFault]; an unnamed one is made. */
    class Datagram(
        val why: DatagramFault,
        val refusal: SendRefusal,
    ) : SendFault

    /** The socket is gone. */
    class Socket(
        val fault: SocketFault,
    ) : SendFault
}

/** Why the network refused a datagram. */
internal sealed interface SendRefusal {
    data object TooLarge : SendRefusal

    data object NetworkUnreachable : SendRefusal

    data object HostUnreachable : SendRefusal

    data object PortUnreachable : SendRefusal

    data object NoBufferSpace : SendRefusal

    data object PermissionDenied : SendRefusal

    /** An error the mapping does not name, with the platform's own exception. */
    class Unnamed(
        val thrown: Thrown,
    ) : SendRefusal
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

    /** The network changed and no socket could be opened again on the same local address and port. */
    class ReopenRefused(
        val thrown: Thrown,
    ) : SocketFault

    /** The local address changed and no socket could be opened at the new one. */
    class MoveRefused(
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
    private val tooLarge = SendFault.Datagram(DatagramFault.TooLarge, SendRefusal.TooLarge)
    private val networkUnreachable = SendFault.Datagram(DatagramFault.NetworkUnreachable, SendRefusal.NetworkUnreachable)
    private val hostUnreachable = SendFault.Datagram(DatagramFault.HostUnreachable, SendRefusal.HostUnreachable)
    private val portUnreachable = SendFault.Datagram(DatagramFault.PortUnreachable, SendRefusal.PortUnreachable)
    private val noBufferSpace = SendFault.Datagram(DatagramFault.NoBufferSpace, SendRefusal.NoBufferSpace)
    private val permissionDenied = SendFault.Datagram(DatagramFault.PermissionDenied, SendRefusal.PermissionDenied)

    fun classify(
        error: UdpError,
        probe: SocketProbe,
    ): SendFault =
        when (error) {
            is UdpError.WouldBlock, is UdpError.Replaced -> SendFault.Full
            is UdpError.MessageTooLarge -> tooLarge
            is UdpError.NetworkUnreachable -> networkUnreachable
            is UdpError.HostUnreachable -> hostUnreachable
            is UdpError.PortUnreachable -> portUnreachable
            is UdpError.NoBufferSpace -> noBufferSpace
            is UdpError.PermissionDenied -> permissionDenied
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
                    SendFault.Datagram(DatagramFault.Unnamed, SendRefusal.Unnamed(thrown))
                }
            }
        }

    companion object {
        /** Consecutive unnamed refusals that end a socket that keeps reporting itself open. */
        const val REPEATED_LIMIT = 8
    }
}
