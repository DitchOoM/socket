package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.time.RttPrior

/** Inside: the network changed as [change] says. */
internal class NetworkWork(
    private val change: NetworkChange,
) : ConnWork() {
    override fun run(inside: Inside) = inside.network(change)

    override fun gone(end: ConnEnd) = Unit
}

/** Where a client's socket now leaves from, and the round trip assumed of the network it is on. */
internal class MoveTo(
    val local: PathKey,
    val prior: RttPrior,
)

/** Inside: the client's socket was moved and now leaves from [to]. */
internal class MovedWork(
    private val to: MoveTo,
) : ConnWork() {
    override fun run(inside: Inside) = inside.moved(to)

    override fun gone(end: ConnEnd) = Unit
}

/** Inside: the loop routed [spare] to this server connection; quiche hands them to the peer. */
internal class IssueIdsWork(
    private val spare: List<SpareId>,
) : ConnWork() {
    override fun run(inside: Inside) = inside.issue(spare)

    override fun gone(end: ConnEnd) = Unit
}
