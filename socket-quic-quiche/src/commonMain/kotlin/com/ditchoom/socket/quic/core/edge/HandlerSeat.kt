@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.sched.Entered
import com.ditchoom.socket.quic.core.sched.HandlerLane
import com.ditchoom.socket.quic.core.sched.HandlerObservation
import com.ditchoom.socket.quic.core.sched.HandlerOutbox
import com.ditchoom.socket.quic.core.sched.HandlerPool
import com.ditchoom.socket.quic.core.sched.HandlerSite
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What [HandlerSeat.claim] answered. */
internal sealed interface HandlerClaim {
    /** The caller handles the connection's streams on [lane], the pool entered for it; it calls [HandlerSeat.left] after. */
    class Claimed(
        val lane: HandlerLane,
    ) : HandlerClaim

    /** Another call handles them already. */
    data object Taken : HandlerClaim

    /** The endpoint stopped: the connection has ended or is ending, and no handler starts. The seat is left as it was. */
    data object Refused : HandlerClaim
}

/** What [HandlerSeat.enter] answered. */
internal sealed interface LaneEntry {
    /** The caller's block runs on [lane], the pool entered for it; it calls [HandlerSeat.left] after. */
    class Entered(
        val lane: HandlerLane,
    ) : LaneEntry

    /** The endpoint stopped: no block is placed. */
    data object Refused : LaneEntry
}

/** Whether a connection has its lane yet, and whether its streams are handled. */
private sealed interface Seat {
    data object Empty : Seat

    /** A server's, once offered: the lane the offer ran on; nobody handles its streams yet. */
    class Laned(
        val lane: HandlerLane,
    ) : Seat

    class Handling(
        val lane: HandlerLane,
    ) : Seat
}

/** An endpoint's handler pool and the side of its loop that feeds it, from which each connection gets its seat. */
internal class HandlerSeats(
    val pool: HandlerPool,
    private val outbox: HandlerOutbox,
) {
    fun seat(connection: QuicSessionId): HandlerSeat = HandlerSeat(pool, outbox, connection)
}

/**
 * One connection's place in its endpoint's [HandlerPool]. Its lane is made at its first application code: a server's
 * connection at its offer, once its handshake completed (or its 0-RTT was accepted); a client's at its first
 * [handleStreams]. A connection that ends before then costs the pool nothing but this seat.
 */
@Published
internal class HandlerSeat(
    private val pool: HandlerPool,
    private val outbox: HandlerOutbox,
    private val connection: QuicSessionId,
) {
    private val seat = AtomicReference<Seat>(Seat.Empty)

    /** Any thread: the caller handles the connection's streams, unless another call does or the endpoint stopped. */
    fun claim(): HandlerClaim {
        while (true) {
            val now = seat.load()
            if (now is Seat.Handling) return HandlerClaim.Taken
            when (pool.enter()) {
                Entered.Entered -> Unit
                Entered.Refused -> return HandlerClaim.Refused
            }
            val lane =
                when (now) {
                    is Seat.Empty -> pool.lane(outbox, connection)
                    is Seat.Laned -> now.lane
                    is Seat.Handling -> now.lane
                }
            if (seat.compareAndSet(now, Seat.Handling(lane))) return HandlerClaim.Claimed(lane)
            pool.leave()
        }
    }

    /** Any thread: the connection's lane for a block of the caller's, made if this is its first application code. */
    fun enter(): LaneEntry {
        when (pool.enter()) {
            Entered.Entered -> Unit
            Entered.Refused -> return LaneEntry.Refused
        }
        while (true) {
            when (val now = seat.load()) {
                is Seat.Laned -> return LaneEntry.Entered(now.lane)
                is Seat.Handling -> return LaneEntry.Entered(now.lane)
                is Seat.Empty -> {
                    val lane = pool.lane(outbox, connection)
                    if (seat.compareAndSet(now, Seat.Laned(lane))) return LaneEntry.Entered(lane)
                }
            }
        }
    }

    /**
     * The loop, once the connection is to be offered: the connection gets its lane, and [offered] runs on it, never on the
     * loop. Nothing is offered once the endpoint stopped.
     */
    fun offer(offered: Runnable) {
        when (pool.enter()) {
            Entered.Entered -> Unit
            Entered.Refused -> return
        }
        while (true) {
            val now = seat.load()
            val lane =
                when (now) {
                    is Seat.Empty -> pool.lane(outbox, connection)
                    is Seat.Laned -> now.lane
                    is Seat.Handling -> now.lane
                }
            if (now is Seat.Empty && !seat.compareAndSet(now, Seat.Laned(lane))) continue
            lane.dispatch(HandlerSite.Offering, OfferStep(offered, pool))
            return
        }
    }

    /** A use that entered the pool ended. */
    fun left() = pool.leave()

    fun report(observation: HandlerObservation) = pool.report(observation)
}

/** The offer, run on the connection's lane; the pool use it entered for ends with it, whatever it does. */
private class OfferStep(
    private val offered: Runnable,
    private val pool: HandlerPool,
) : Runnable {
    override fun run() {
        try {
            offered.run()
        } finally {
            pool.leave()
        }
    }
}
