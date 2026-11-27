package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketTicket

/**
 * Every transition of a [Carriage]: [event] in this state makes its changes to [endpoint]'s sockets through [io],
 * [poller] and the loop's slots, [seats], and says what follows. An answer naming a ticket the state does not hold is
 * [Step.Same]. Nothing opens, promotes or reopens once the carriage is [Carriage.Dead] or [Carriage.Left], and a second
 * socket's failure is a [Step.Kept]: it never ends the connection.
 */
@LoopOnly
internal fun Carriage.on(
    event: CarriageEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step =
    when (this) {
        is Carriage.Unplaced -> unplacedOn(event)
        is Carriage.One -> oneOn(event, io, poller, seats, endpoint)
        is Carriage.Opening -> openingOn(event, io, poller, seats, endpoint)
        is Carriage.Two -> twoOn(event, io, poller, seats, endpoint)
        is Carriage.Lent -> lentOn(event, poller, endpoint)
        is Carriage.Dead -> deadOn(event, poller, endpoint)
        is Carriage.Left -> Step.Same
    }

private fun Carriage.Unplaced.unplacedOn(event: CarriageEvent): Step =
    when (event) {
        is CarriageEvent.Placed ->
            Step.Moves(
                when (first) {
                    is SocketOwnership.Owned -> ONE_FIRST
                    is SocketOwnership.Lent -> Carriage.Lent
                },
                MoveFact.None,
            )
        is CarriageEvent.Leave -> Step.Moves(Carriage.Left, MoveFact.None)
        is CarriageEvent.CarryingDied -> Step.Ends(Carriage.Left, event.end)
        is CarriageEvent.OpenBeside, is CarriageEvent.Made, is CarriageEvent.Promote, is CarriageEvent.CloseSecond,
        is CarriageEvent.Rehome, is CarriageEvent.BesideLost,
        -> Step.Same
    }

private fun Carriage.One.oneOn(
    event: CarriageEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step =
    when (event) {
        is CarriageEvent.OpenBeside -> open(event.ticket, event.bind, event.prior, io, poller, seats, endpoint)
        is CarriageEvent.Rehome ->
            when (val reopened = io.reopenCarrying(poller, endpoint.token)) {
                is Reopened.SameEndpoint -> Step.Moves(this, MoveFact.CarryingRehomed)
                is Reopened.Refused -> reopenRefused(reopened)
            }
        is CarriageEvent.CarryingDied -> Step.Ends(Carriage.Dead, event.end)
        is CarriageEvent.Leave -> left(poller, endpoint.token)
        is CarriageEvent.Placed, is CarriageEvent.Made, is CarriageEvent.Promote, is CarriageEvent.CloseSecond,
        is CarriageEvent.BesideLost,
        -> Step.Same
    }

/** A second socket [ticket] is opened on [bind]'s address beside the carrying one, or the move is refused, typed. */
private fun Carriage.One.open(
    ticket: SocketTicket,
    bind: PathKey,
    prior: RttPrior,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept =
    when (val opened = io.openBeside(bind, ticket)) {
        is Beside.Opened -> seat(ticket, opened.local, prior, io, poller, seats, endpoint)
        is Beside.Arriving -> Step.Moves(Carriage.Opening(carrying, ticket, prior), MoveFact.None)
        is Beside.Refused -> Step.Moves(this, MoveFact.Refused(ticket, MoveRefusal.Unopened))
        is Beside.Unsupported -> Step.Moves(this, MoveFact.Refused(ticket, MoveRefusal.NoSecond))
    }

private fun Carriage.One.seat(
    ticket: SocketTicket,
    local: PathKey,
    prior: RttPrior,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept {
    val token = seats.seat(endpoint)
    io.seatBeside(poller, token)
    return Step.Moves(Carriage.Two(carrying, ticket, token), MoveFact.Moved(MoveTo(local, prior, ticket)))
}

/** The carrying socket, polled as [token], leaves the poller for good; the io's close closes every socket it holds. */
private fun left(
    poller: Poller,
    token: Int,
): Step {
    poller.remove(token)
    return Step.Moves(Carriage.Left, MoveFact.None)
}

private fun Carriage.Opening.openingOn(
    event: CarriageEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step =
    when (event) {
        is CarriageEvent.OpenBeside -> {
            io.closeBeside()
            Carriage.One(carrying).on(event, io, poller, seats, endpoint)
        }
        is CarriageEvent.Made -> made(io, poller, seats, endpoint)
        is CarriageEvent.CloseSecond ->
            if (event.ticket == beside) {
                io.closeBeside()
                Step.Moves(Carriage.One(carrying), MoveFact.None)
            } else {
                Step.Same
            }
        is CarriageEvent.Rehome ->
            when (val reopened = io.reopenCarrying(poller, endpoint.token)) {
                is Reopened.SameEndpoint -> Step.Moves(this, MoveFact.CarryingRehomed)
                is Reopened.Refused -> {
                    io.closeBeside()
                    reopenRefused(reopened)
                }
            }
        is CarriageEvent.CarryingDied -> {
            io.closeBeside()
            Step.Ends(Carriage.Dead, event.end)
        }
        // The io's close closes the socket being made.
        is CarriageEvent.Leave -> left(poller, endpoint.token)
        is CarriageEvent.Placed, is CarriageEvent.Promote, is CarriageEvent.BesideLost -> Step.Same
    }

/** The carrying socket could not be reopened: it is off the poller and the connection ends. */
private fun reopenRefused(refused: Reopened.Refused): Step =
    Step.Ends(Carriage.Left, ConnEnd.SocketFailed(SocketFault.ReopenRefused(refused.thrown)))

/** The pass's look at the socket being made: once it is ready the connection is told, as an open tells it. */
private fun Carriage.Opening.made(
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step.Kept =
    when (val settled = io.settleBeside()) {
        is Beside.Opened -> Carriage.One(carrying).seat(beside, settled.local, prior, io, poller, seats, endpoint)
        is Beside.Arriving -> Step.Same
        is Beside.Refused -> Step.Moves(Carriage.One(carrying), MoveFact.Refused(beside, MoveRefusal.Unopened))
        is Beside.Unsupported -> Step.Moves(Carriage.One(carrying), MoveFact.Refused(beside, MoveRefusal.NoSecond))
    }

private fun Carriage.Two.twoOn(
    event: CarriageEvent,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
    endpoint: Endpoint,
): Step =
    when (event) {
        is CarriageEvent.OpenBeside -> {
            io.dropBeside(poller, seats, token)
            Carriage.One(carrying).on(event, io, poller, seats, endpoint)
        }
        is CarriageEvent.Promote ->
            if (event.ticket == beside) {
                io.promote(poller, seats, endpoint.token, token)
                Step.Moves(Carriage.One(beside), MoveFact.Promoted(beside))
            } else {
                Step.Same
            }
        is CarriageEvent.CloseSecond ->
            if (event.ticket == beside) {
                io.dropBeside(poller, seats, token)
                Step.Moves(Carriage.One(carrying), MoveFact.None)
            } else {
                Step.Same
            }
        is CarriageEvent.Rehome -> rehomeBeside(io, poller, seats)
        is CarriageEvent.BesideLost -> lost(event.ticket, io, poller, seats)
        is CarriageEvent.CarryingDied -> {
            io.dropBeside(poller, seats, token)
            Step.Ends(Carriage.Dead, event.end)
        }
        is CarriageEvent.Leave -> {
            io.unseatBeside(poller, seats, token)
            left(poller, endpoint.token)
        }
        is CarriageEvent.Placed, is CarriageEvent.Made -> Step.Same
    }

/**
 * While a move is open the second socket holds the address the connection is moving to, so a changed network reopens
 * it; one that cannot be reopened is lost, and the connection stays where it is.
 */
private fun Carriage.Two.rehomeBeside(
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
): Step.Kept =
    when (io.reopenBeside(poller, seats, token)) {
        is Reopened.SameEndpoint -> Step.Moves(this, MoveFact.BesideRehomed)
        is Reopened.Refused -> Step.Moves(Carriage.One(carrying), MoveFact.SecondLost(beside))
    }

/** Second socket [ticket] read closed: it is closed, and the connection ends the move it served and stays. */
private fun Carriage.Two.lost(
    ticket: SocketTicket,
    io: CarriageIo,
    poller: Poller,
    seats: Loop,
): Step.Kept {
    if (ticket != beside) return Step.Same
    io.dropBeside(poller, seats, token)
    return Step.Moves(Carriage.One(carrying), MoveFact.SecondLost(beside))
}

private fun Carriage.Lent.lentOn(
    event: CarriageEvent,
    poller: Poller,
    endpoint: Endpoint,
): Step =
    when (event) {
        is CarriageEvent.OpenBeside -> Step.Moves(this, MoveFact.Refused(event.ticket, MoveRefusal.SocketLent))
        is CarriageEvent.CarryingDied -> Step.Ends(Carriage.Dead, event.end)
        // The io stops its own use of the socket; the caller keeps it open.
        is CarriageEvent.Leave -> left(poller, endpoint.token)
        is CarriageEvent.Placed, is CarriageEvent.Made, is CarriageEvent.Promote, is CarriageEvent.CloseSecond,
        is CarriageEvent.Rehome, is CarriageEvent.BesideLost,
        -> Step.Same
    }

private fun Carriage.Dead.deadOn(
    event: CarriageEvent,
    poller: Poller,
    endpoint: Endpoint,
): Step =
    when (event) {
        is CarriageEvent.Leave -> left(poller, endpoint.token)
        is CarriageEvent.Placed, is CarriageEvent.OpenBeside, is CarriageEvent.Made, is CarriageEvent.Promote,
        is CarriageEvent.CloseSecond, is CarriageEvent.Rehome, is CarriageEvent.BesideLost, is CarriageEvent.CarryingDied,
        -> Step.Same
    }
