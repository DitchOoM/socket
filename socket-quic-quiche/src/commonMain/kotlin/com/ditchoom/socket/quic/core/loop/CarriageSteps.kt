package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.transport.NetworkId

/**
 * Every transition of a client's [Carriage]: [event] in this state makes its changes to [endpoint]'s sockets through
 * [io], [poller] and the loop's slots, [seats], and says what follows. An answer naming a ticket the state does not hold
 * is [Step.Same]. Nothing opens, promotes or reopens once the carriage is dead or left. A move and a second socket's
 * event are a [Step.Kept]: neither ends the connection.
 */
@LoopOnly
internal fun Carriage.on(
    event: CarriageEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step<Carriage> =
    when (event) {
        is SocketEvent -> socketOn(event, io, poller, seats, endpoint)
        is MoveEvent -> moveOn(event, io, poller, seats, endpoint)
        is BesideEvent -> besideOn(event, io, poller, seats, endpoint)
    }

/** Every transition of a state with no second socket, a server's included: [event] on the socket polled as [token]. */
@LoopOnly
internal fun Carriage.Single.held(
    event: SocketEvent,
    io: CarriageIo,
    poller: Poller,
    token: Int,
): Step<Carriage.Single> =
    when (this) {
        is Carriage.Unplaced ->
            when (event) {
                is SocketEvent.Placed ->
                    Step.Moves(
                        when (event.first) {
                            is SocketOwnership.Owned -> placedOn(event.network)
                            is SocketOwnership.Lent -> Carriage.Lent
                        },
                        MoveFact.None,
                    )
                is SocketEvent.Leave -> Step.Moves(Carriage.Left, MoveFact.None)
                is SocketEvent.Rehome, is SocketEvent.CarryingDied -> Step.Same
            }
        is Carriage.One ->
            when (event) {
                is SocketEvent.Rehome -> rehome(event, io, poller, token)
                is SocketEvent.CarryingDied -> Step.Ends(Carriage.Dead, event.end)
                is SocketEvent.Leave -> left(poller, token)
                is SocketEvent.Placed -> Step.Same
            }
        // The io stops its own use of a lent socket; the caller keeps it open.
        is Carriage.Lent ->
            when (event) {
                is SocketEvent.CarryingDied -> Step.Ends(Carriage.Dead, event.end)
                is SocketEvent.Leave -> left(poller, token)
                is SocketEvent.Placed, is SocketEvent.Rehome -> Step.Same
            }
        is Carriage.Dead ->
            when (event) {
                is SocketEvent.Leave -> left(poller, token)
                is SocketEvent.Placed, is SocketEvent.Rehome, is SocketEvent.CarryingDied -> Step.Same
            }
        is Carriage.Unpolled ->
            when (event) {
                is SocketEvent.Leave -> Step.Moves(Carriage.Left, MoveFact.None)
                is SocketEvent.Placed, is SocketEvent.Rehome, is SocketEvent.CarryingDied -> Step.Same
            }
        is Carriage.Left -> Step.Same
    }

/** An owned first socket placed on [network]; one on no named network is the shared [ONE_FIRST]. */
private fun placedOn(network: NetworkId): Carriage.One =
    if (network == NetworkId.Unidentified) ONE_FIRST else Carriage.One(Held(SocketTicket.FIRST, network))

private fun Carriage.socketOn(
    event: SocketEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step<Carriage> =
    when (this) {
        is Carriage.Single -> held(event, io, poller, endpoint.token)
        is Carriage.Opening ->
            when (event) {
                is SocketEvent.Rehome -> rehome(event, io, poller, endpoint.token)
                is SocketEvent.CarryingDied -> {
                    io.closeBeside()
                    Step.Ends(Carriage.Dead, event.end)
                }
                // The io's close closes the socket being made.
                is SocketEvent.Leave -> left(poller, endpoint.token)
                is SocketEvent.Placed -> Step.Same
            }
        is Carriage.Two ->
            when (event) {
                is SocketEvent.Rehome -> rehome(event, io, poller, seats)
                is SocketEvent.CarryingDied -> {
                    io.dropBeside(poller, seats, token)
                    Step.Ends(Carriage.Dead, event.end)
                }
                is SocketEvent.Leave -> {
                    io.unseatBeside(poller, seats, token)
                    left(poller, endpoint.token)
                }
                is SocketEvent.Placed -> Step.Same
            }
    }

/** The carrying socket, polled as [token], leaves the poller for good; the io's close closes every socket it holds. */
internal fun left(
    poller: Poller,
    token: Int,
): Step.Moves<Carriage.Left> {
    poller.remove(token)
    return Step.Moves(Carriage.Left, MoveFact.None)
}

/** The carrying socket could not be reopened: it is off the poller, its connection ends, and its io is still closed. */
internal fun reopenRefused(refused: Reopened.Refused): Step.Ends<Carriage.Unpolled> =
    Step.Ends(Carriage.Unpolled, ConnEnd.SocketFailed(SocketFault.ReopenRefused(refused.thrown)))

private fun Carriage.moveOn(
    event: MoveEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept<Carriage> =
    when (this) {
        is Carriage.One ->
            when (event) {
                is MoveEvent.OpenBeside -> open(event, io, poller, seats, endpoint)
                is MoveEvent.Promote, is MoveEvent.CloseSecond -> Step.Same
            }
        is Carriage.Opening ->
            when (event) {
                is MoveEvent.OpenBeside -> {
                    io.closeBeside()
                    Carriage.One(carrying).open(event, io, poller, seats, endpoint)
                }
                is MoveEvent.CloseSecond ->
                    if (event.ticket == beside.ticket) {
                        io.closeBeside()
                        Step.Moves(Carriage.One(carrying), MoveFact.None)
                    } else {
                        Step.Same
                    }
                is MoveEvent.Promote -> Step.Same
            }
        is Carriage.Two ->
            when (event) {
                is MoveEvent.OpenBeside -> {
                    io.dropBeside(poller, seats, token)
                    Carriage.One(carrying).open(event, io, poller, seats, endpoint)
                }
                is MoveEvent.Promote ->
                    if (event.ticket == beside.ticket) {
                        io.promote(poller, seats, endpoint.token, token)
                        Step.Moves(Carriage.One(beside), MoveFact.Promoted(beside.ticket))
                    } else {
                        Step.Same
                    }
                is MoveEvent.CloseSecond ->
                    if (event.ticket == beside.ticket) {
                        io.dropBeside(poller, seats, token)
                        Step.Moves(Carriage.One(carrying), MoveFact.None)
                    } else {
                        Step.Same
                    }
            }
        is Carriage.Lent ->
            when (event) {
                is MoveEvent.OpenBeside -> Step.Moves(this, MoveFact.Refused(event.ticket, MoveRefusal.SocketLent))
                is MoveEvent.Promote, is MoveEvent.CloseSecond -> Step.Same
            }
        is Carriage.Unplaced, is Carriage.Dead, is Carriage.Unpolled, is Carriage.Left -> Step.Same
    }

/**
 * A second socket is opened on the address [ask] names beside the carrying one, held on the network the ask named, or
 * the move is refused, typed.
 */
private fun Carriage.One.open(
    ask: MoveEvent.OpenBeside,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept<Carriage> {
    val opening = Carriage.Opening(carrying, Held(ask.ticket, ask.network), ask.prior)
    return when (val opened = io.openBeside(ask.bind, ask.ticket)) {
        is Beside.Opened -> seat(opening, opened.local, io, poller, seats, endpoint)
        is Beside.Arriving -> Step.Moves(opening, MoveFact.None)
        is Beside.Refused -> Step.Moves(this, MoveFact.Refused(ask.ticket, MoveRefusal.Unopened))
        is Beside.Unsupported -> Step.Moves(this, MoveFact.Refused(ask.ticket, MoveRefusal.NoSecond))
    }
}

/** The second socket [opening] names is open at [local]: it is seated, and the connection told it moved. */
private fun seat(
    opening: Carriage.Opening,
    local: PathKey,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept<Carriage> {
    val token = seats.seat(endpoint)
    io.seatBeside(poller, token)
    return Step.Moves(
        Carriage.Two(opening.carrying, opening.beside, token, local),
        MoveFact.Moved(MoveTo(local, opening.prior, opening.beside.ticket)),
    )
}

private fun Carriage.besideOn(
    event: BesideEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept<Carriage> =
    when (this) {
        is Carriage.Opening ->
            when (event) {
                is BesideEvent.Made -> made(io, poller, seats, endpoint)
                is BesideEvent.BesideLost ->
                    if (event.ticket == beside.ticket) {
                        io.closeBeside()
                        Step.Moves(Carriage.One(carrying), MoveFact.Refused(beside.ticket, MoveRefusal.Unopened))
                    } else {
                        Step.Same
                    }
            }
        is Carriage.Two ->
            when (event) {
                is BesideEvent.BesideLost ->
                    if (event.ticket == beside.ticket) {
                        io.dropBeside(poller, seats, token)
                        Step.Moves(Carriage.One(carrying), MoveFact.SecondLost(beside.ticket))
                    } else {
                        Step.Same
                    }
                is BesideEvent.Made -> Step.Same
            }
        is Carriage.Single -> Step.Same
    }

/** The pass's look at the socket being made: once it is ready the connection is told, as an open tells it. */
private fun Carriage.Opening.made(
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept<Carriage> =
    when (val settled = io.settleBeside()) {
        is Beside.Opened -> seat(this, settled.local, io, poller, seats, endpoint)
        is Beside.Arriving -> Step.Same
        is Beside.Refused -> Step.Moves(Carriage.One(carrying), MoveFact.Refused(beside.ticket, MoveRefusal.Unopened))
        is Beside.Unsupported -> Step.Moves(Carriage.One(carrying), MoveFact.Refused(beside.ticket, MoveRefusal.NoSecond))
    }
