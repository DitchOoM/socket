package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.Shutdown
import com.ditchoom.socket.quic.core.sched.SocketHealth
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable

/**
 * A socket on a loop and where its datagrams go. Its [Seat] holds the loop's one record of which socket carries: a
 * client's [Carriage], or a server's [Carriage.Single]. Every change to it is a step [step] takes. The loop thread only.
 */
@LoopOnly
internal class Endpoint(
    val refs: SocketRefs,
    private val io: DatagramBatchIo,
    private val socket: SendPort,
    private val route: Route,
    /** Runs on the loop once the socket has left it, after the connection ended and its last output went out. */
    private val onLeft: Runnable,
    readiness: Readiness = Readiness.Polled,
    private val ownership: SocketOwnership = SocketOwnership.Owned,
) {
    var token = 0
        private set
    private var interest = WriteInterest.None
    private val sockets = CarriageIo(io, refs, readiness)
    private val seat = if (route is Route.Fixed) Seat.OfClient() else Seat.OfServer()
    private val closing = SocketClose(io, socket)
    private val out: Outbox = route.outbox()

    fun placed(
        token: Int,
        poller: Poller,
        seats: Loop,
    ) {
        this.token = token
        step(SocketEvent.Placed(ownership), poller, seats)
        io.placed()
    }

    fun readable(via: Via) = sockets.readable(via)

    /** Whether the io holds datagrams the poller will never signal: the loop's next pass must not wait. */
    fun hasBacklog(): Boolean = sockets.backlog(seat.carriage())

    /** Each pass: a second socket being made is looked at, and once ready the connection told. */
    fun settle(
        poller: Poller,
        seats: Loop,
    ) = step(BesideEvent.Made, poller, seats)

    /** Receives what waits on the socket [via] names and hands it to the route ([CarriageIo.receiveCarrying]). */
    fun receive(
        via: Via,
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
        budget: ReceiveBudget,
        poller: Poller,
        seats: Loop,
    ) {
        when (via) {
            Via.Carrying -> sockets.receiveCarrying(seat.carriage(), route, socket, batch, counts, clock, budget)
            Via.Probing ->
                when (val now = seat.carriage()) {
                    is Carriage.Two ->
                        when (sockets.readBeside(now.beside, route, batch, counts, clock)) {
                            BesideRead.Read -> Unit
                            BesideRead.Closed -> step(BesideEvent.BesideLost(now.beside), poller, seats)
                        }
                    is Carriage.Single, is Carriage.Opening -> Unit
                }
        }
    }

    /**
     * Sends what lanes still hold, then serves the connection and sends its output. Once the connection has ended the
     * socket closes as [SocketClose] says.
     */
    fun serve(
        poller: Poller,
        seats: Loop,
        now: Nanos,
        port: LoopPort,
    ): Stay {
        noticeSocket(poller, seats)
        val sends = carries()
        if (sends == Carries.Live) socket.flushWaiting()
        val send = out.batch()
        while (true) {
            val collected =
                when (route) {
                    is Route.Fixed -> route.conn.turn(send)
                    is Route.Demux -> route.demux.turn(send)
                }
            if (send.pending > 0) {
                when (carries()) {
                    Carries.Live -> traced(TraceSection.SendBatch) { socket.flush(send) }
                    Carries.Dead, Carries.Gone -> send.took(send.pending)
                }
            }
            if (collected != Collected.MoreWaiting || send.pending > 0) break
        }
        out.served()
        noticeSocket(poller, seats)
        if (route.ended() && (!out.holds() || carries() != Carries.Live) && !socket.hasWaiting()) {
            when (carries()) {
                Carries.Gone -> Unit
                Carries.Live, Carries.Dead ->
                    // With lanes out, the last thread out wakes the loop to ask again, the lane bound at the latest.
                    when (closing.ask(now, port)) {
                        is Shutdown.Quiet -> {
                            step(SocketEvent.Leave, poller, seats)
                            closing.close()
                        }
                        is Shutdown.Closed -> step(SocketEvent.Leave, poller, seats)
                        is Shutdown.LanesOut, is Shutdown.Sending -> Unit
                    }
            }
            if (carries() == Carries.Gone) {
                out.left()
                onLeft.run()
                return Stay.Left
            }
        }
        refreshInterest(poller)
        return Stay.Stays
    }

    /**
     * The network under this socket changed: the socket holding the connection's address (the second one, while a move
     * is open) is reopened on the same address and port, and its connection told in this pass, so it sends at once. A
     * carrying socket that cannot be reopened ends its connection; a lent one is never reopened.
     */
    fun rehome(
        poller: Poller,
        seats: Loop,
    ) = step(SocketEvent.Rehome, poller, seats)

    /** A client's connection wants socket [ticket] on [bind]'s address, assumed [prior]. */
    fun move(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
        bind: PathKey,
        prior: RttPrior,
    ) = step(MoveEvent.OpenBeside(ticket, bind, prior), poller, seats)

    /** The second socket [ticket] names carries from now on, polled as this endpoint's token, and the connection is told. */
    fun promote(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) = step(MoveEvent.Promote(ticket), poller, seats)

    /** The second socket [ticket] names is closed, open or still being made. */
    fun closeSecond(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) = step(MoveEvent.CloseSecond(ticket), poller, seats)

    /** The io threw on the socket [via] names: a carrying socket's connection ends with it, a second's move. */
    fun ioThrew(
        thrown: Thrown,
        via: Via,
        poller: Poller,
        seats: Loop,
    ) = when (via) {
        Via.Carrying -> step(SocketEvent.CarryingDied(ConnEnd.SocketFailed(SocketFault.Unnamed(thrown))), poller, seats)
        Via.Probing ->
            when (val now = seat.carriage()) {
                is Carriage.Opening -> step(BesideEvent.BesideLost(now.beside), poller, seats)
                is Carriage.Two -> step(BesideEvent.BesideLost(now.beside), poller, seats)
                is Carriage.Single -> Unit
            }
    }

    /** The loop stopped with [end]: the connection ends, and the socket is closed now or by the last lane back. */
    fun loopEnded(
        end: ConnEnd,
        poller: Poller,
        seats: Loop,
    ) {
        endConn(end)
        if (carries() != Carries.Gone) {
            step(SocketEvent.Leave, poller, seats)
            closing.stopped()
        }
        out.left()
        onLeft.run()
    }

    /** The loop had stopped before this endpoint joined it: the connection ends and the socket is closed. */
    fun unplaced(
        end: ConnEnd,
        poller: Poller,
        seats: Loop,
    ) {
        endConn(end)
        step(SocketEvent.Leave, poller, seats)
        closing.stopped()
        out.left()
        onLeft.run()
    }

    private fun noticeSocket(
        poller: Poller,
        seats: Loop,
    ) {
        when (val health = socket.health()) {
            is SocketHealth.Live -> route.refused(socket.ledger.takeNews())
            is SocketHealth.Dead ->
                if (carries() == Carries.Live) step(SocketEvent.CarryingDied(ConnEnd.SocketFailed(health.fault)), poller, seats)
        }
    }

    /**
     * The one place the seat changes: [event]'s step is taken, and what it says is told or ends the connection. A
     * server's seat has no second socket and takes no move: a client's event leaves it as it is.
     */
    private fun step(
        event: CarriageEvent,
        poller: Poller,
        seats: Loop,
    ) {
        val taken =
            when (seat) {
                is Seat.OfClient -> seat.take(event, sockets, poller, seats, this)
                is Seat.OfServer ->
                    when (event) {
                        is SocketEvent -> seat.take(event, sockets, poller, token)
                        is ClientEvent -> Step.Same
                    }
            }
        when (val step = taken) {
            is Step.Same -> Unit
            is Step.Moves -> told(step.fact)
            is Step.Ends -> endConn(step.end)
        }
    }

    private fun told(fact: MoveFact) {
        when (fact) {
            // The carrying token was off the poller, and its write interest with it.
            is MoveFact.Promoted, is MoveFact.CarryingRehomed -> interest = WriteInterest.None
            is MoveFact.None, is MoveFact.Moved, is MoveFact.Refused, is MoveFact.SecondLost, is MoveFact.BesideRehomed -> Unit
        }
        route.told(fact)
    }

    private fun endConn(end: ConnEnd) = route.endWith(end)

    private fun carries(): Carries = seat.carriage().carries()

    private fun refreshInterest(poller: Poller) {
        val wanted =
            if (carries() == Carries.Live && (out.holds() || socket.hasWaiting())) WriteInterest.Wanted else WriteInterest.None
        if (wanted == interest) return
        interest = wanted
        poller.interest(token, wanted)
    }
}
