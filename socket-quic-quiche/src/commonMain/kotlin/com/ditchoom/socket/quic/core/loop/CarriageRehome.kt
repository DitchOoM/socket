package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.transport.NetworkId

// A rehome names the socket it is about and the network its address is on now. Only a socket that still carries, or a
// move's second socket, is reopened, and only on a network other than the one it is held on.

/** How a held socket stands against the network a rehome names. */
internal enum class Stamp {
    /** It is on that network already. */
    Same,

    /** It was on no named network: it takes this one, and nothing is reopened. */
    Taken,

    /** It is on another network: it is reopened on this one. */
    Other,
}

internal fun Held.against(network: NetworkId): Stamp =
    when {
        this.network == network -> Stamp.Same
        this.network == NetworkId.Unidentified -> Stamp.Taken
        else -> Stamp.Other
    }

/** The carrying socket, polled as [token], when [event] names it; one that cannot be reopened ends its connection. */
internal fun Carriage.One.rehome(
    event: SocketEvent.Rehome,
    io: CarriageIo,
    poller: Poller,
    token: Int,
): Step<Carriage.Single> {
    if (event.ticket != carrying.ticket) return Step.Same
    val now = Held(carrying.ticket, event.network)
    return when (carrying.against(event.network)) {
        Stamp.Same -> Step.Moves(this, MoveFact.OnNetwork)
        Stamp.Taken -> Step.Moves(Carriage.One(now), MoveFact.OnNetwork)
        Stamp.Other ->
            when (val reopened = io.reopenCarrying(poller, token)) {
                is Reopened.SameEndpoint -> Step.Moves(Carriage.One(now), MoveFact.CarryingRehomed)
                is Reopened.Refused -> reopenRefused(reopened)
            }
    }
}

/** As [Carriage.One.rehome], while a second socket is being made: a refused reopen closes the one being made too. */
internal fun Carriage.Opening.rehome(
    event: SocketEvent.Rehome,
    io: CarriageIo,
    poller: Poller,
    token: Int,
): Step<Carriage> {
    if (event.ticket != carrying.ticket) return Step.Same
    val now = Carriage.Opening(Held(carrying.ticket, event.network), beside, prior)
    return when (carrying.against(event.network)) {
        Stamp.Same -> Step.Moves(this, MoveFact.OnNetwork)
        Stamp.Taken -> Step.Moves(now, MoveFact.OnNetwork)
        Stamp.Other ->
            when (val reopened = io.reopenCarrying(poller, token)) {
                is Reopened.SameEndpoint -> Step.Moves(now, MoveFact.CarryingRehomed)
                is Reopened.Refused -> {
                    io.closeBeside()
                    reopenRefused(reopened)
                }
            }
    }
}

/**
 * While a move is open the second socket holds the address the connection is moving to, so a rehome naming either
 * socket is about it: on another network it is reopened there, and one that cannot be reopened is lost, the
 * connection staying where it is.
 */
internal fun Carriage.Two.rehome(
    event: SocketEvent.Rehome,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
): Step.Kept<Carriage> {
    if (event.ticket != carrying.ticket && event.ticket != beside.ticket) return Step.Same
    val now = Carriage.Two(carrying, Held(beside.ticket, event.network), token, at)
    return when (beside.against(event.network)) {
        Stamp.Same -> Step.Moves(this, MoveFact.OnNetwork)
        Stamp.Taken -> Step.Moves(now, MoveFact.OnNetwork)
        Stamp.Other ->
            when (io.reopenBeside(poller, seats, token)) {
                is Reopened.SameEndpoint -> Step.Moves(now, MoveFact.BesideRehomed)
                is Reopened.Refused -> Step.Moves(Carriage.One(carrying), MoveFact.SecondLost(beside.ticket))
            }
    }
}
