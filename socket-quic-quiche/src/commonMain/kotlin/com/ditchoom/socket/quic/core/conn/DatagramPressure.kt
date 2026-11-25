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
 * How hard arrivals pressed on a connection's receive queue: the most it held at once, how many reads (each a socket
 * read's datagrams, stamped with one time) brought more than it had room for, and the most datagrams kept since a batch
 * receive was last lent the queue when one dropped. Kept within a pass's reads, a drop's receiver waited for the loop;
 * kept over several, it fell passes behind. Evidence for a dropping receiver; counts nothing a receive does. Inside
 * the connection only.
 */
@Confined
internal class DatagramPressure {
    /** The most datagrams the queue held at once. */
    var highWater = 0
        private set

    /** Reads that dropped at least one datagram for want of room. */
    var droppingReads = 0
        private set

    /** The most datagrams kept since the last lend, the one that dropped another included, at any drop. */
    var keptSinceLendAtDrop = 0
        private set

    private var keptSinceLend = 0

    private var lastDrop: LastDrop = LastDrop.None

    /** A datagram was kept, the queue holding [size] with it. */
    fun queued(size: Int) {
        keptSinceLend++
        if (size > highWater) highWater = size
    }

    /** A batch receive was lent what the queue held. */
    fun lent() {
        keptSinceLend = 0
    }

    /** A datagram was dropped for the one being kept, in the read stamped [at]. */
    fun dropped(at: Nanos) {
        if (keptSinceLend + 1 > keptSinceLendAtDrop) keptSinceLendAtDrop = keptSinceLend + 1
        when (val last = lastDrop) {
            is LastDrop.At -> if (last.at == at) return
            is LastDrop.None -> Unit
        }
        droppingReads++
        lastDrop = LastDrop.At(at)
    }
}
