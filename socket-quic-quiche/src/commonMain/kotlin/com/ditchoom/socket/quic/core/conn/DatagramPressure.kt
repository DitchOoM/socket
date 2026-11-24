package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos

/** Whether a drop was seen yet, and at which read's time. */
private sealed interface LastDrop {
    data object None : LastDrop

    class At(
        val at: Nanos,
    ) : LastDrop
}

/**
 * How hard arrivals pressed on a connection's receive queue: the most it held at once, and how many reads (each a
 * socket read's datagrams, stamped with one time) brought more than it had room for. Evidence for a dropping receiver;
 * counts nothing a receive does. Inside the connection only.
 */
@Confined
internal class DatagramPressure {
    /** The most datagrams the queue held at once. */
    var highWater = 0
        private set

    /** Reads that dropped at least one datagram for want of room. */
    var droppingReads = 0
        private set

    private var lastDrop: LastDrop = LastDrop.None

    fun queued(size: Int) {
        if (size > highWater) highWater = size
    }

    fun dropped(at: Nanos) {
        when (val last = lastDrop) {
            is LastDrop.At -> if (last.at == at) return
            is LastDrop.None -> Unit
        }
        droppingReads++
        lastDrop = LastDrop.At(at)
    }
}
