package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.core.life.ConnEnd

/** Loop work that hands a lane whose datagrams the network refused to the [SendPort] that finishes them. One per lane, built once. */
internal class AdoptLane(
    private val socket: SendPort,
    private val lane: CallerSend,
) : LoopWork() {
    override fun run(port: LoopPort) = socket.adopt(lane)

    // The loop has stopped: the datagrams have nowhere to go.
    override fun gone(end: ConnEnd) = Unit
}
