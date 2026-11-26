package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket

/** Which of an endpoint's sockets a poller slot reports: the carrying one, or the second one opened beside it. */
internal enum class Via { Carrying, Probing }

/**
 * An endpoint's sockets: the one carrying its connection, and the second one a move opens beside it. A third is
 * unrepresentable: a move opens its socket only after the loop closed any second one, and [One] is where a promotion
 * or a close leaves it.
 */
internal sealed interface Sockets {
    class One(
        val carrying: SocketTicket,
    ) : Sockets

    /** The second socket, [second], is being made without waiting; its network is assumed a round trip of [prior]. */
    class Opening(
        val carrying: SocketTicket,
        val second: SocketTicket,
        val prior: RttPrior,
    ) : Sockets

    /** The second socket, [second], is open beside the carrying one, and the poller reports it as [token]. */
    class Two(
        val carrying: SocketTicket,
        val second: SocketTicket,
        val token: Int,
    ) : Sockets
}

/** The carrying socket's ticket. */
internal fun Sockets.carrying(): SocketTicket =
    when (this) {
        is Sockets.One -> carrying
        is Sockets.Opening -> carrying
        is Sockets.Two -> carrying
    }
