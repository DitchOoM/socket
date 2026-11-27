package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.ready.Poller

/**
 * Where an endpoint keeps which of its sockets carries: the loop's one record of it. Its kind is fixed by the route: a
 * client's ([OfClient]) holds a [Carriage], a server's ([OfServer]) only a [Carriage.Single], which has no state with a
 * second socket. Each one's `take` is the only place its state is written. The loop thread only.
 */
internal sealed interface Seat {
    /** A client's: its carrying socket and a second one its moves open, stepped by [Carriage.on]. */
    @LoopOnly
    class OfClient : Seat {
        var carriage: Carriage = Carriage.Unplaced
            private set

        /** Takes [event]'s step on [endpoint]'s sockets through [io], [poller] and the loop's slots, [seats]. */
        fun take(
            event: CarriageEvent,
            io: CarriageIo,
            poller: Poller,
            seats: Loop,
            endpoint: Endpoint,
        ): Step<Carriage> = carriage.on(event, io, poller, seats, endpoint).also { carriage = it.nextOr(carriage) }
    }

    /** A server's: one socket, stepped by [Carriage.Single.held]. */
    @LoopOnly
    class OfServer : Seat {
        var held: Carriage.Single = Carriage.Unplaced
            private set

        /** Takes [event]'s step on the socket polled as [token], through [io] and [poller]. */
        fun take(
            event: SocketEvent,
            io: CarriageIo,
            poller: Poller,
            token: Int,
        ): Step<Carriage.Single> = held.held(event, io, poller, token).also { held = it.nextOr(held) }
    }
}

/** The state the seat holds now. */
internal fun Seat.carriage(): Carriage =
    when (this) {
        is Seat.OfClient -> carriage
        is Seat.OfServer -> held
    }

/** The state a step leaves: its next one, or [now] when nothing changed. */
private fun <C : Carriage> Step<C>.nextOr(now: C): C =
    when (this) {
        is Step.Same -> now
        is Step.Moves -> next
        is Step.Ends -> next
    }
