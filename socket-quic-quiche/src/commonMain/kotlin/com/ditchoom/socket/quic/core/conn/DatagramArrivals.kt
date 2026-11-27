package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.pool.LongQueue
import com.ditchoom.socket.quic.core.time.Nanos

/** When the datagrams quiche holds arrived, as far as an incoming age bound asks. */
internal sealed interface DatagramArrivals {
    /** No bound: no datagram's wait is asked. */
    data object Unstamped : DatagramArrivals

    /** Each datagram quiche holds, stamped oldest first with the time of the read that brought it, judged by [bound]. */
    class Stamped(
        val bound: AgeBound.Within,
    ) : DatagramArrivals {
        val at = LongQueue()
    }
}

/** The arrivals an incoming age bound of [incoming] asks for. */
internal fun arrivalsOf(incoming: AgeBound): DatagramArrivals =
    when (incoming) {
        is AgeBound.Unbounded -> DatagramArrivals.Unstamped
        is AgeBound.Within -> DatagramArrivals.Stamped(incoming)
    }

/** quiche holds [held] datagrams, having dropped [dropped] of the oldest since the last read: the ones new to it arrived at [now]. */
internal fun DatagramArrivals.read(
    held: Int,
    dropped: Long,
    now: Nanos,
) {
    when (this) {
        is DatagramArrivals.Stamped -> {
            var gone = dropped
            while (gone > 0 && at.isNotEmpty()) {
                at.takeOldest()
                gone--
            }
            repeat(held - at.size) { at.addLast(now.value) }
        }
        is DatagramArrivals.Unstamped -> Unit
    }
}

/** Whether the datagram just read out of quiche, its oldest, waited past the bound at [now]. */
internal fun DatagramArrivals.taken(now: Nanos): Expiry =
    when (this) {
        is DatagramArrivals.Unstamped -> Expiry.Fresh
        is DatagramArrivals.Stamped -> if (at.isNotEmpty()) bound.expired(Nanos(at.takeOldest()), now) else Expiry.Fresh
    }
