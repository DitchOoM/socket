package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveRefusedWork
import com.ditchoom.socket.quic.core.udp.SocketTicket

/**
 * Who owns the socket an endpoint carries its connection on. The loop reads it when the connection asks for a move or
 * the network under the socket changes; the io's close ends only the io's own use of a lent socket.
 */
internal sealed interface SocketOwnership {
    /** The loop opened the socket: it reopens it on a changed network, opens a second one for a move, and closes it. */
    data object Owned : SocketOwnership

    /**
     * The caller lent the socket and keeps it: the loop never closes or reopens it, and a move is refused, the
     * connection staying on it.
     */
    data object Lent : SocketOwnership
}

/**
 * The move to socket [ticket] that [route]'s client connection asked for: [open] runs for an owned socket; on a lent one
 * the move is refused, naming [ticket] ([MoveRefusal.SocketLent]), and the connection stays on the socket it has. A
 * server's route has no move.
 */
internal inline fun SocketOwnership.move(
    route: Route,
    ticket: SocketTicket,
    open: (Route.Fixed) -> Unit,
) = when (route) {
    is Route.Fixed ->
        when (this) {
            is SocketOwnership.Owned -> open(route)
            is SocketOwnership.Lent -> route.conn.submitFromLoop(MoveRefusedWork(ticket, MoveRefusal.SocketLent))
        }
    is Route.Demux -> Unit
}
