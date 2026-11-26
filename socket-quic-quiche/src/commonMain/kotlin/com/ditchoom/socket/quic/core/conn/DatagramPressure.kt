package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Confined

/**
 * Where the batch reader stood when quiche's queue dropped datagrams: away with its last batch; parked on the box and
 * not yet told any arrived; or told and not yet lent them.
 */
internal enum class ReaderStood { Away, Parked, Told }

/**
 * How hard arrivals pressed on a connection's datagram receive queue: the most quiche held when the box read it, the
 * most one batch was lent, and the drops counted by where the batch reader stood. A drop while it was parked is the
 * box's: every read that leaves quiche holding a datagram tells a parked reader. A drop while it was away or told is
 * the host's: the reader, or the work that lends to it, had not run. Evidence for a dropping receiver; counts nothing a
 * receive does. Inside the connection only.
 */
@Confined
internal class DatagramPressure {
    /** The most datagrams quiche's queue held when the box read it. */
    var heldHighWater = 0
        private set

    /** The most datagrams one batch was lent. */
    var largestLend = 0
        private set

    /** Batches lent at least one datagram. */
    var lends = 0L
        private set

    /** Drops counted while the batch reader was away with its batch, or there was none. */
    var dropsWhileAway = 0L
        private set

    /** Drops counted while the batch reader was parked and not yet told: the box's. */
    var dropsWhileParked = 0L
        private set

    /** Drops counted while the batch reader was told datagrams arrived and had not been lent them. */
    var dropsWhileTold = 0L
        private set

    /** quiche's queue held [count] datagrams. */
    fun held(count: Int) {
        if (count > heldHighWater) heldHighWater = count
    }

    /** A batch was lent [count] datagrams. */
    fun lent(count: Int) {
        lends++
        if (count > largestLend) largestLend = count
    }

    /** [count] drops were counted with the batch reader where it [stood]. */
    fun dropped(
        count: Long,
        stood: ReaderStood,
    ) {
        when (stood) {
            ReaderStood.Away -> dropsWhileAway += count
            ReaderStood.Parked -> dropsWhileParked += count
            ReaderStood.Told -> dropsWhileTold += count
        }
    }
}
