package com.ditchoom.socket.quic.core.loop

/**
 * Who owns the socket a connect hands its endpoint: what the endpoint's [Carriage] starts as once placed. The io's close
 * ends only the io's own use of a lent socket.
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
