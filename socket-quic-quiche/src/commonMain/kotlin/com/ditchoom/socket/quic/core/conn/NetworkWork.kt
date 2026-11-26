package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.SocketTicket

/** Inside: the network changed as [change] says. */
internal class NetworkWork(
    private val change: NetworkChange,
) : ConnWork() {
    override fun run(inside: Inside) = inside.network(change)

    override fun gone(end: ConnEnd) = Unit
}

/** Where a client's second socket, [ticket], leaves from, and the round trip assumed of the network it is on. */
internal class MoveTo(
    val local: PathKey,
    val prior: RttPrior,
    val ticket: SocketTicket,
)

/** Where a client's socket is wanted: [bind]'s address, on a network assumed to have a round trip of [prior]. */
internal class MoveWanted(
    val bind: PathKey,
    val prior: RttPrior,
)

/** Inside: the client's socket is wanted on another address ([Inside.moveWanted]). */
internal class MoveWantedWork(
    private val wanted: MoveWanted,
) : ConnWork() {
    override fun run(inside: Inside) = inside.moveWanted(wanted)

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the client's loop opened a second socket, which leaves from [to]. */
internal class MovedWork(
    private val to: MoveTo,
) : ConnWork() {
    override fun run(inside: Inside) = inside.moved(to)

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the loop could not open the socket a move asked for; the move is refused and the connection stays. */
internal class MoveRefusedWork : ConnWork() {
    override fun run(inside: Inside) = inside.moveRefused()

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the loop lost the second socket [ticket] names, which died or could not be reopened; its move ends. */
internal class SecondLostWork(
    private val ticket: SocketTicket,
) : ConnWork() {
    override fun run(inside: Inside) = inside.secondLost(ticket)

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the loop routed [spare] to this server connection; quiche hands them to the peer. */
internal class IssueIdsWork(
    private val spare: List<SpareId>,
) : ConnWork() {
    override fun run(inside: Inside) = inside.issue(spare)

    override fun gone(end: ConnEnd) = Unit
}

/**
 * Inside: the network refused the first datagram of the connection it was handed; a handshake judges what that means.
 */
internal class SendsRefusedWork : ConnWork() {
    override fun run(inside: Inside) = inside.sendsRefused()

    override fun gone(end: ConnEnd) = Unit
}
