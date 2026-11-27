package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.demux.Admitted
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.UdpError
import com.ditchoom.socket.quic.core.udp.closedQuietly

/** How a read of a second socket ended: drained or bounded, or the socket found closed. */
internal enum class BesideRead { Read, Closed }

/**
 * An endpoint's sockets as its two-socket [io] holds them: the carrying socket is the io's own and the one beside it is
 * the io's second. It reads them, and makes the changes a [Carriage]'s steps ask for; which socket carries is the
 * carriage's alone. A part of [Endpoint], loop thread only.
 */
@LoopOnly
internal class CarriageIo(
    private val io: DatagramBatchIo,
    private val refs: SocketRefs,
    private val readiness: Readiness,
) {
    /** Whether a socket may hold datagrams, [ReceiveInput.Backlog] when its io holds some the poller never signals. */
    private var carryingInput = ReceiveInput.Maybe
    private var besideInput = ReceiveInput.Maybe

    fun readable(via: Via) {
        when (via) {
            Via.Carrying -> if (carryingInput != ReceiveInput.Backlog) carryingInput = ReceiveInput.Maybe
            Via.Probing -> if (besideInput != ReceiveInput.Backlog) besideInput = ReceiveInput.Maybe
        }
    }

    /** Whether the io holds datagrams the poller will never signal on a socket [carriage] reads. */
    fun backlog(carriage: Carriage): Boolean =
        when (carriage) {
            is Carriage.Two -> carryingInput == ReceiveInput.Backlog || besideInput == ReceiveInput.Backlog
            is Carriage.One, is Carriage.Opening, is Carriage.Lent -> carryingInput == ReceiveInput.Backlog
            is Carriage.Unplaced, is Carriage.Dead, is Carriage.Unpolled, is Carriage.Left -> false
        }

    /**
     * Receives what waits on the carrying socket [carriage] names and hands it to [route]. A server then starts
     * held handshakes a chunk at a time while [budget] lasts, reading the socket again after each chunk so its buffer
     * never fills while they start; reading is never cut by the budget, so nothing waits in the kernel to be read stale.
     */
    fun receiveCarrying(
        carriage: Carriage,
        route: Route,
        socket: SendPort,
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
        budget: ReceiveBudget,
    ) {
        val on =
            when (carriage) {
                is Carriage.One -> carriage.carrying.ticket
                is Carriage.Opening -> carriage.carrying.ticket
                is Carriage.Two -> carriage.carrying.ticket
                is Carriage.Lent -> SocketTicket.FIRST
                is Carriage.Unplaced, is Carriage.Dead, is Carriage.Unpolled, is Carriage.Left -> return
            }
        when (route) {
            is Route.Fixed -> readCarrying(on, route, batch, counts, clock, socket)
            is Route.Demux -> {
                route.demux.passBegins()
                readCarrying(on, route, batch, counts, clock, socket)
                while (budget.state() == Budget.Left && route.demux.admitSome(route.out.batch) == Admitted.More) {
                    carryingInput = ReceiveInput.Maybe
                    readCarrying(on, route, batch, counts, clock, socket)
                }
            }
        }
    }

    /**
     * Reads the carrying socket [on] names until drained or a batch more could pass [MAX_PER_PASS], handing each read to
     * [route]; a read's datagrams reach quiche at one [clock] reading. A failed read is told to [socket].
     */
    private fun readCarrying(
        on: SocketTicket,
        route: Route,
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
        socket: SendPort,
    ) {
        if (carryingInput == ReceiveInput.Drained && readiness == Readiness.Polled) return
        var taken = 0
        while (taken + batch.limits.maxDatagrams <= MAX_PER_PASS) {
            when (val got = traced(TraceSection.ReceiveBatch) { io.receive(batch) }) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    carryingInput = if (got is ReceiveBatchResult.GotWithBacklog) ReceiveInput.Backlog else ReceiveInput.Maybe
                    taken += batch.count
                    route.deliver(batch, clock.now(), on)
                }
                is ReceiveBatchResult.Nothing -> {
                    carryingInput = ReceiveInput.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    route.deliver(batch, clock.now(), on)
                    carryingInput = ReceiveInput.Drained
                    socket.receiveFailed(got.error)
                    break
                }
            }
        }
        counts.received(taken)
    }

    /** Opens second socket [ticket] on [bind]'s address beside the carrying one. */
    fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside = io.openBeside(bind, ticket)

    /** Whether the second socket being made is ready. */
    fun settleBeside(): Beside = io.settleBeside()

    /**
     * Closes the second socket, open or being made. A close is often made as a socket fails; what it throws is the
     * second socket's alone, so it is dropped ([closedQuietly]).
     */
    fun closeBeside() = closedQuietly { io.closeSecond() }

    /** A second socket just opened is polled as [token]. */
    fun seatBeside(
        poller: Poller,
        token: Int,
    ) {
        poller.add(refs.second, token)
        besideInput = ReceiveInput.Maybe
    }

    /** The second socket polled as [token] is closed and its slot given back. */
    fun dropBeside(
        poller: Poller,
        seats: Loop,
        token: Int,
    ) {
        unseatBeside(poller, seats, token)
        closeBeside()
    }

    /** The second socket's slot [token] is given back; the io's close closes the socket. */
    fun unseatBeside(
        poller: Poller,
        seats: Loop,
        token: Int,
    ) {
        poller.remove(token)
        seats.unseat(token)
    }

    /** The second socket, polled as [beside], carries from now on, polled as [carrying]; the old one is closed. */
    fun promote(
        poller: Poller,
        seats: Loop,
        carrying: Int,
        beside: Int,
    ) {
        poller.remove(carrying)
        unseatBeside(poller, seats, beside)
        io.promote()
        poller.add(refs.carrying, carrying)
        carryingInput = if (besideInput == ReceiveInput.Backlog) ReceiveInput.Backlog else ReceiveInput.Maybe
    }

    /** The carrying socket, polled as [token], is reopened on the network up now; one refused stays off the poller. */
    fun reopenCarrying(
        poller: Poller,
        token: Int,
    ): Reopened {
        poller.remove(token)
        val reopened = io.reopen()
        when (reopened) {
            is Reopened.SameEndpoint -> {
                poller.add(refs.carrying, token)
                carryingInput = ReceiveInput.Maybe
            }
            is Reopened.Refused -> Unit
        }
        return reopened
    }

    /** The second socket, polled as [token], is reopened on the network up now; one refused is closed, its slot given back. */
    fun reopenBeside(
        poller: Poller,
        seats: Loop,
        token: Int,
    ): Reopened {
        poller.remove(token)
        val reopened = io.reopenSecond()
        when (reopened) {
            is Reopened.SameEndpoint -> seatBeside(poller, token)
            is Reopened.Refused -> {
                seats.unseat(token)
                closeBeside()
            }
        }
        return reopened
    }

    /**
     * Reads what waits on second socket [ticket], up to [MAX_PER_PASS] datagrams, handing each read to [route]; what a
     * read that failed had taken is handed on too.
     */
    fun readBeside(
        ticket: SocketTicket,
        route: Route,
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
    ): BesideRead {
        if (besideInput == ReceiveInput.Drained && readiness == Readiness.Polled) return BesideRead.Read
        var taken = 0
        var read = BesideRead.Read
        while (taken + batch.limits.maxDatagrams <= MAX_PER_PASS) {
            when (val got = traced(TraceSection.ReceiveBatch) { io.receiveSecond(batch) }) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    besideInput = if (got is ReceiveBatchResult.GotWithBacklog) ReceiveInput.Backlog else ReceiveInput.Maybe
                    taken += batch.count
                    route.deliver(batch, clock.now(), ticket)
                }
                is ReceiveBatchResult.Nothing -> {
                    besideInput = ReceiveInput.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    taken += batch.count
                    route.deliver(batch, clock.now(), ticket)
                    besideInput = ReceiveInput.Drained
                    if (got.error is UdpError.SocketClosed) read = BesideRead.Closed
                    break
                }
            }
        }
        counts.received(taken)
        return read
    }

    private companion object {
        /** Per socket and pass, so a flood starves no deadline: half a default receive queue, which holds two passes' reads. */
        const val MAX_PER_PASS = 512
    }
}
