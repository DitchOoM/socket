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
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable

private enum class Phase { Live, Dead, Gone }

/** A socket on a loop and where its datagrams go, and the [SecondSocket] a client's move opens beside it. The loop thread only. */
@LoopOnly
internal class Endpoint(
    val refs: SocketRefs,
    private val io: DatagramBatchIo,
    private val socket: SendPort,
    private val route: Route,
    /** Runs on the loop once the socket has left it, after the connection ended and its last output went out. */
    private val onLeft: Runnable,
    private val readiness: Readiness = Readiness.Polled,
    private val ownership: SocketOwnership = SocketOwnership.Owned,
) {
    var token = 0
        private set
    private var input = ReceiveInput.Maybe
    private var phase = Phase.Live
    private var interest = WriteInterest.None
    val second = SecondSocket(io, refs)
    private val closing = SocketClose(io, socket)
    private val out: Outbox = route.outbox()

    fun placed(token: Int) = run { this.token = token }

    fun readable(via: Via) {
        when (via) {
            Via.Carrying -> if (input != ReceiveInput.Backlog) input = ReceiveInput.Maybe
            Via.Probing -> second.readable()
        }
    }

    /** Whether the io holds datagrams the poller will never signal: the loop's next pass must not wait. */
    fun hasBacklog(): Boolean = phase == Phase.Live && (input == ReceiveInput.Backlog || second.input() == ReceiveInput.Backlog)

    /**
     * Receives up to [MAX_PER_PASS] datagrams and hands them to the route. A server then starts held handshakes a chunk
     * at a time while [budget] lasts, reading the socket again after each chunk so its buffer never fills while they start;
     * reading is never cut by the budget, so nothing waits in the kernel to be read stale.
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
        if (phase != Phase.Live) return
        when (route) {
            is Route.Fixed ->
                when (via) {
                    Via.Carrying -> {
                        second.settle(this, poller, seats, route)
                        receiveWaiting(batch, counts, clock)
                    }
                    Via.Probing -> second.receive(route, batch, counts, clock, readiness, MAX_PER_PASS, poller, seats)
                }
            is Route.Demux -> {
                route.demux.passBegins()
                receiveWaiting(batch, counts, clock)
                while (budget.state() == Budget.Left && route.demux.admitSome(route.out.batch) == Admitted.More) {
                    input = ReceiveInput.Maybe
                    receiveWaiting(batch, counts, clock)
                }
            }
        }
    }

    /** Reads until drained or a batch more could pass [MAX_PER_PASS]; a read's datagrams reach quiche at one [clock] reading. */
    private fun receiveWaiting(
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
    ) {
        if (input == ReceiveInput.Drained && readiness == Readiness.Polled) return
        val on = second.carrying()
        var taken = 0
        while (taken + batch.limits.maxDatagrams <= MAX_PER_PASS) {
            when (val got = traced(TraceSection.ReceiveBatch) { io.receive(batch) }) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    input = if (got is ReceiveBatchResult.GotWithBacklog) ReceiveInput.Backlog else ReceiveInput.Maybe
                    taken += batch.count
                    route.deliver(batch, clock.now(), on)
                }
                is ReceiveBatchResult.Nothing -> {
                    input = ReceiveInput.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    route.deliver(batch, clock.now(), on)
                    input = ReceiveInput.Drained
                    socket.receiveFailed(got.error)
                    break
                }
            }
        }
        counts.received(taken)
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
        noticeSocket()
        if (phase == Phase.Live) socket.flushWaiting()
        val send = out.batch()
        while (true) {
            val collected =
                when (route) {
                    is Route.Fixed -> route.conn.turn(send)
                    is Route.Demux -> route.demux.turn(send)
                }
            if (send.pending > 0) {
                when (phase) {
                    Phase.Live -> traced(TraceSection.SendBatch) { socket.flush(send) }
                    Phase.Dead, Phase.Gone -> send.took(send.pending)
                }
            }
            if (collected != Collected.MoreWaiting || send.pending > 0) break
        }
        out.served()
        noticeSocket()
        if (route.ended() && (!out.holds() || phase != Phase.Live) && !socket.hasWaiting()) {
            when (phase) {
                Phase.Gone -> Unit
                Phase.Live, Phase.Dead ->
                    // With lanes out, the last thread out wakes the loop to ask again, the lane bound at the latest.
                    when (closing.ask(now, port)) {
                        is Shutdown.Quiet -> {
                            leave(poller, seats)
                            closing.close()
                        }
                        is Shutdown.Closed -> leave(poller, seats)
                        is Shutdown.LanesOut, is Shutdown.Sending -> Unit
                    }
            }
            if (phase == Phase.Gone) {
                out.left()
                onLeft.run()
                return Stay.Left
            }
        }
        refreshInterest(poller)
        return Stay.Stays
    }

    /**
     * The network under this socket changed: it is reopened on the same address and port (the second one, while a move is
     * open), and its connection told in this pass, so it sends at once. One that cannot be reopened ends its connection.
     */
    fun rehome(
        poller: Poller,
        seats: Loop,
    ) {
        if (ownership is SocketOwnership.Lent || phase != Phase.Live || second.rehome(poller, seats, route) == SecondRehome.Reopened) return
        poller.remove(token)
        when (val reopened = io.reopen()) {
            is Reopened.SameEndpoint -> {
                poller.add(refs.carrying, token)
                interest = WriteInterest.None
                input = ReceiveInput.Maybe
                route.rehomed()
            }
            is Reopened.Refused -> {
                phase = Phase.Gone
                endConn(ConnEnd.SocketFailed(SocketFault.ReopenRefused(reopened.thrown)))
            }
        }
    }

    /** A client's connection wants a socket on [bind]'s address, on a network assumed [prior]: see [SocketOwnership.move]. */
    fun move(
        poller: Poller,
        seats: Loop,
        bind: PathKey,
        prior: RttPrior,
    ) = when (route) {
        is Route.Fixed -> if (phase == Phase.Live) ownership.move(route) { second.open(this, poller, seats, route, bind, prior) } else Unit
        is Route.Demux -> Unit
    }

    /** The second socket [ticket] names carries from now on, polled as this endpoint's own token; a stale ticket is a no-op. */
    fun promote(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) {
        if (phase != Phase.Live) return
        when (second.promote(poller, seats, ticket, token)) {
            Promotion.Stale -> return
            Promotion.Promoted -> input = ReceiveInput.Maybe
            Promotion.PromotedWithBacklog -> input = ReceiveInput.Backlog
        }
        interest = WriteInterest.None
    }

    /** The io threw: this socket's connection ends with it, and no other. */
    fun ioThrew(thrown: Thrown) {
        if (phase != Phase.Live) return
        phase = Phase.Dead
        endConn(ConnEnd.SocketFailed(SocketFault.Unnamed(thrown)))
    }

    /** The loop stopped with [end]: the connection ends, and the socket is closed now or by the last lane back. */
    fun loopEnded(
        end: ConnEnd,
        poller: Poller,
        seats: Loop,
    ) {
        endConn(end)
        if (phase != Phase.Gone) {
            leave(poller, seats)
            closing.stopped()
        }
        out.left()
        onLeft.run()
    }

    /** The loop had stopped before this endpoint joined it: the connection ends and the socket is closed. */
    fun unplaced(end: ConnEnd) {
        endConn(end)
        phase = Phase.Gone
        closing.stopped()
        out.left()
        onLeft.run()
    }

    private fun noticeSocket() {
        when (val health = socket.health()) {
            is SocketHealth.Live -> route.refused(socket.ledger.takeNews())
            is SocketHealth.Dead ->
                if (phase == Phase.Live) {
                    phase = Phase.Dead
                    endConn(ConnEnd.SocketFailed(health.fault))
                }
        }
    }

    private fun endConn(end: ConnEnd) = route.endWith(end)

    private fun refreshInterest(poller: Poller) {
        val wanted =
            if (phase == Phase.Live && (out.holds() || socket.hasWaiting())) WriteInterest.Wanted else WriteInterest.None
        if (wanted == interest) return
        interest = wanted
        poller.interest(token, wanted)
    }

    /** Off the poller, the second socket's slot too; the io closes both sockets. */
    private fun leave(
        poller: Poller,
        seats: Loop,
    ) {
        phase = Phase.Gone
        poller.remove(token)
        second.leave(poller, seats)
    }

    private companion object {
        /** Per socket and pass, so a flood starves no deadline: half a default receive queue, which holds two passes' reads. */
        const val MAX_PER_PASS = 512
    }
}
