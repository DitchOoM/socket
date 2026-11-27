package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.demux.Admitted
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
 * A socket on a loop and where its datagrams go. Its [Carriage] is the loop's one record of which socket carries the
 * connection and of a second one a client's move opens beside it; every change to it is a [Carriage.on] step. The loop
 * thread only.
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
    ownership: SocketOwnership = SocketOwnership.Owned,
) {
    var token = 0
        private set
    private var carriage: Carriage = unplaced(ownership)
    private var interest = WriteInterest.None
    private val sockets = CarriageIo(io, refs, readiness)
    private val closing = SocketClose(io, socket)
    private val out: Outbox = route.outbox()

    fun placed(
        token: Int,
        poller: Poller,
        seats: Loop,
    ) {
        this.token = token
        step(CarriageEvent.Placed, poller, seats)
        io.placed()
    }

    fun readable(via: Via) = sockets.readable(via)

    /** Whether the io holds datagrams the poller will never signal: the loop's next pass must not wait. */
    fun hasBacklog(): Boolean = sockets.backlog(carriage)

    /**
     * Receives what waits on the socket [via] names and hands it to the route. A server then starts held handshakes a
     * chunk at a time while [budget] lasts, reading the socket again after each chunk so its buffer never fills while they
     * start; reading is never cut by the budget, so nothing waits in the kernel to be read stale.
     */
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
            Via.Carrying -> {
                step(CarriageEvent.Made, poller, seats)
                val on =
                    when (val now = carriage) {
                        is Carriage.One -> now.carrying
                        is Carriage.Opening -> now.carrying
                        is Carriage.Two -> now.carrying
                        is Carriage.Lent -> SocketTicket.FIRST
                        is Carriage.Unplaced, is Carriage.Dead, is Carriage.Left -> return
                    }
                when (route) {
                    is Route.Fixed -> sockets.readCarrying(on, route, batch, counts, clock, socket)
                    is Route.Demux -> {
                        route.demux.passBegins()
                        sockets.readCarrying(on, route, batch, counts, clock, socket)
                        while (budget.state() == Budget.Left && route.demux.admitSome(route.out.batch) == Admitted.More) {
                            sockets.readAgain()
                            sockets.readCarrying(on, route, batch, counts, clock, socket)
                        }
                    }
                }
            }
            Via.Probing ->
                when (val now = carriage) {
                    is Carriage.Two ->
                        when (sockets.readBeside(now.beside, route, batch, counts, clock)) {
                            BesideRead.Read -> Unit
                            BesideRead.Closed -> step(CarriageEvent.BesideLost(now.beside), poller, seats)
                        }
                    is Carriage.Unplaced, is Carriage.One, is Carriage.Opening, is Carriage.Lent, is Carriage.Dead,
                    is Carriage.Left,
                    -> Unit
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
                            step(CarriageEvent.Leave, poller, seats)
                            closing.close()
                        }
                        is Shutdown.Closed -> step(CarriageEvent.Leave, poller, seats)
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
    ) = step(CarriageEvent.Rehome, poller, seats)

    /** A client's connection wants socket [ticket] on [bind]'s address, assumed [prior]; a server's route has no move. */
    fun move(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
        bind: PathKey,
        prior: RttPrior,
    ) = when (route) {
        is Route.Fixed -> step(CarriageEvent.OpenBeside(ticket, bind, prior), poller, seats)
        is Route.Demux -> Unit
    }

    /** The second socket [ticket] names carries from now on, polled as this endpoint's token, and the connection is told. */
    fun promote(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) = step(CarriageEvent.Promote(ticket), poller, seats)

    /** The second socket [ticket] names is closed, open or still being made. */
    fun closeSecond(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) = step(CarriageEvent.CloseSecond(ticket), poller, seats)

    /** The io threw: this socket's connection ends with it, and no other. */
    fun ioThrew(
        thrown: Thrown,
        poller: Poller,
        seats: Loop,
    ) = step(CarriageEvent.CarryingDied(ConnEnd.SocketFailed(SocketFault.Unnamed(thrown))), poller, seats)

    /** The loop stopped with [end]: the connection ends, and the socket is closed now or by the last lane back. */
    fun loopEnded(
        end: ConnEnd,
        poller: Poller,
        seats: Loop,
    ) {
        endConn(end)
        if (carries() != Carries.Gone) {
            step(CarriageEvent.Leave, poller, seats)
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
        step(CarriageEvent.Leave, poller, seats)
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
                if (carries() == Carries.Live) step(CarriageEvent.CarryingDied(ConnEnd.SocketFailed(health.fault)), poller, seats)
        }
    }

    /** The one place the carriage changes: [event]'s step is taken, and what it says is told or ends the connection. */
    private fun step(
        event: CarriageEvent,
        poller: Poller,
        seats: Loop,
    ) {
        when (val step = carriage.on(event, sockets, poller, seats, this)) {
            is Step.Same -> Unit
            is Step.Moves -> {
                carriage = step.next
                told(step.fact)
            }
            is Step.Ends -> {
                carriage = step.next
                endConn(step.end)
            }
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

    private fun carries(): Carries = carriage.carries()

    private fun refreshInterest(poller: Poller) {
        val wanted =
            if (carries() == Carries.Live && (out.holds() || socket.hasWaiting())) WriteInterest.Wanted else WriteInterest.None
        if (wanted == interest) return
        interest = wanted
        poller.interest(token, wanted)
    }
}
