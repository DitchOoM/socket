package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.Link
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.udp.SendBatch

/** What a socket lends the threads that send in place: a pool of lanes, or nothing. */
internal sealed interface CallerLanes {
    data object None : CallerLanes

    class Of(
        val pool: FixedPool<CallerSend>,
    ) : CallerLanes
}

/** What follows a lane in the chain of lanes still holding datagrams the network has not taken. */
internal sealed interface LaneLink {
    data object End : LaneLink
}

/**
 * A send batch of its own for a thread that is not the loop. It is lent for one entry into a connection and then
 * given back, or, when the network refused part of it, handed to the loop to finish.
 */
@LoopOnly
internal class CallerSend(
    index: Int,
    val batch: SendBatch,
) : Pooled(index),
    LaneLink {
    /** Loop thread only: the next lane waiting for the network. */
    @Link
    internal var nextLane: LaneLink = LaneLink.End
}

internal enum class LaneSent {
    /** Everything went out or was dropped; the lane is still the caller's. */
    Sent,

    /** The network was full: the loop owns the lane now and sends the rest when the socket can take it. */
    Deferred,

    /** The socket died: the rest was dropped; the lane is still the caller's. */
    SocketDied,
}
