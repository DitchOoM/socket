package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.observe.LoopOnly

/** The lanes whose datagrams the network refused, oldest first; the loop sends them when the socket can take more. */
@LoopOnly
internal class WaitingLanes {
    private var first: LaneLink = LaneLink.End
    private var last: LaneLink = LaneLink.End

    val isEmpty: Boolean get() = first !is CallerSend

    fun oldest(): LaneLink = first

    fun add(lane: CallerSend) {
        lane.nextLane = LaneLink.End
        when (val tail = last) {
            is CallerSend -> tail.nextLane = lane
            is LaneLink.End -> first = lane
        }
        last = lane
    }

    /** Drops the oldest lane from the chain. */
    fun removeOldest() {
        when (val head = first) {
            is CallerSend -> {
                first = head.nextLane
                if (first is LaneLink.End) last = LaneLink.End
                head.nextLane = LaneLink.End
            }
            is LaneLink.End -> Unit
        }
    }
}
