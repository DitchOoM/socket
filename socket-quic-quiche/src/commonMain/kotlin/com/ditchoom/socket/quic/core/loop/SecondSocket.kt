package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.MoveRefusedWork
import com.ditchoom.socket.quic.core.conn.MoveTo
import com.ditchoom.socket.quic.core.conn.MovedWork
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.conn.SecondLostWork
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRefs
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.Beside
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.UdpError

/** What a promotion found: no second socket for its ticket, or one promoted, with or without datagrams the io held. */
internal enum class Promotion { Stale, Promoted, PromotedWithBacklog }

/** Whether a rehome found a second socket to reopen. */
internal enum class SecondRehome { NotOpen, Reopened }

/**
 * An endpoint's second socket: whether one is open beside the carrying one ([Sockets]), how its reads stand, and the
 * tickets the endpoint has handed out. The loop opens it when the connection asks for a move and promotes or closes it
 * when the connection asks, by ticket; a request naming a ticket no longer held open is a no-op. A part of [Endpoint].
 */
@LoopOnly
internal class SecondSocket(
    private val io: DatagramBatchIo,
    private val refs: SocketRefs,
) {
    private var sockets: Sockets = Sockets.One(SocketTicket.FIRST)
    private var input = ReceiveInput.Maybe
    private var lastTicket = SocketTicket.FIRST

    /** The carrying socket's ticket. */
    fun carrying(): SocketTicket = sockets.carrying()

    fun readable() {
        if (input != ReceiveInput.Backlog) input = ReceiveInput.Maybe
    }

    /** Whether an open second socket may hold datagrams, [ReceiveInput.Backlog] when its io holds some the poller never signals. */
    fun input(): ReceiveInput =
        when (sockets) {
            is Sockets.Two -> input
            is Sockets.One, is Sockets.Opening -> ReceiveInput.Drained
        }

    /**
     * Reads what waits on the second socket, up to [most] datagrams, handing each read to [route]. What a read that failed
     * had taken is handed on too; a socket that failed for good is closed and its connection told ([lose]).
     */
    fun receive(
        route: Route,
        batch: ReceiveBatch,
        counts: LoopCounts,
        clock: LoopClock,
        readiness: Readiness,
        most: Int,
        poller: Poller,
        seats: Loop,
    ) {
        val two =
            when (val now = sockets) {
                is Sockets.Two -> now
                is Sockets.One, is Sockets.Opening -> return
            }
        if (input == ReceiveInput.Drained && readiness == Readiness.Polled) return
        var taken = 0
        while (taken + batch.limits.maxDatagrams <= most) {
            when (val got = traced(TraceSection.ReceiveBatch) { io.receiveSecond(batch) }) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    input = if (got is ReceiveBatchResult.GotWithBacklog) ReceiveInput.Backlog else ReceiveInput.Maybe
                    taken += batch.count
                    route.deliver(batch, clock.now(), two.second)
                }
                is ReceiveBatchResult.Nothing -> {
                    input = ReceiveInput.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    taken += batch.count
                    route.deliver(batch, clock.now(), two.second)
                    input = ReceiveInput.Drained
                    if (got.error is UdpError.SocketClosed) lose(poller, seats, route, two)
                    break
                }
            }
        }
        counts.received(taken)
    }

    /**
     * [endpoint]'s connection wants a socket on [bind]'s address: one is opened there, on a port of its own, beside the
     * carrying one, after any second one an earlier move left is closed, and the connection is told its ticket in this
     * pass; a socket made without waiting tells it in the pass that finds it ready ([settle]). One that cannot be opened
     * there refuses the move, and the connection stays where it is; one that cannot be moved ends it as its address went away.
     */
    fun open(
        endpoint: Endpoint,
        poller: Poller,
        seats: Loop,
        fixed: Route.Fixed,
        bind: PathKey,
        prior: RttPrior,
    ) {
        drop(poller, seats)
        val ticket = lastTicket.next()
        lastTicket = ticket
        when (val opened = io.openBeside(bind, ticket)) {
            is Beside.Opened -> seat(endpoint, poller, seats, fixed, ticket, opened.local, prior)
            is Beside.Arriving -> sockets = Sockets.Opening(sockets.carrying(), ticket, prior)
            is Beside.Refused, is Beside.Unsupported -> unopened(fixed, opened)
        }
    }

    /** Each pass while a second socket is being made: once it is ready the connection is told, as [open] tells it. */
    fun settle(
        endpoint: Endpoint,
        poller: Poller,
        seats: Loop,
        fixed: Route.Fixed,
    ) {
        val opening =
            when (val now = sockets) {
                is Sockets.Opening -> now
                is Sockets.One, is Sockets.Two -> return
            }
        when (val settled = io.settleBeside()) {
            is Beside.Opened -> seat(endpoint, poller, seats, fixed, opening.second, settled.local, opening.prior)
            is Beside.Arriving -> Unit
            is Beside.Refused, is Beside.Unsupported -> {
                sockets = Sockets.One(opening.carrying)
                unopened(fixed, settled)
            }
        }
    }

    /** The second socket [ticket] names carries from now on, polled as [token], and the old one is closed. */
    fun promote(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
        token: Int,
    ): Promotion {
        val two =
            when (val now = sockets) {
                is Sockets.Two -> now
                is Sockets.One, is Sockets.Opening -> return Promotion.Stale
            }
        if (two.second != ticket) return Promotion.Stale
        poller.remove(token)
        poller.remove(two.token)
        seats.unseat(two.token)
        io.promote()
        poller.add(refs.carrying, token)
        sockets = Sockets.One(ticket)
        return if (input == ReceiveInput.Backlog) Promotion.PromotedWithBacklog else Promotion.Promoted
    }

    /** The second socket [ticket] names is closed. */
    fun close(
        poller: Poller,
        seats: Loop,
        ticket: SocketTicket,
    ) {
        when (val now = sockets) {
            is Sockets.Two -> if (now.second == ticket) drop(poller, seats)
            is Sockets.One, is Sockets.Opening -> Unit
        }
    }

    /**
     * While a move is open the second socket holds the address the connection is moving to, so a changed network reopens
     * it, and [route]'s connection is told. One that cannot be reopened is lost ([lose]).
     */
    fun rehome(
        poller: Poller,
        seats: Loop,
        route: Route,
    ): SecondRehome {
        val two =
            when (val now = sockets) {
                is Sockets.Two -> now
                is Sockets.One, is Sockets.Opening -> return SecondRehome.NotOpen
            }
        poller.remove(two.token)
        when (val reopened = io.reopenSecond()) {
            is Reopened.SameEndpoint -> {
                poller.add(refs.second, two.token)
                input = ReceiveInput.Maybe
                route.rehomed()
            }
            is Reopened.Refused -> {
                poller.add(refs.second, two.token)
                lose(poller, seats, route, two)
            }
        }
        return SecondRehome.Reopened
    }

    /** The second socket's slot is let go with its endpoint, once; the io closes the socket. */
    fun leave(
        poller: Poller,
        seats: Loop,
    ) {
        when (val now = sockets) {
            is Sockets.Two -> {
                poller.remove(now.token)
                seats.unseat(now.token)
                sockets = Sockets.One(now.carrying)
            }
            is Sockets.One -> Unit
            is Sockets.Opening -> sockets = Sockets.One(now.carrying)
        }
    }

    /** The second socket failed for good: it is closed, and its connection ends the move it served and stays where it is. */
    private fun lose(
        poller: Poller,
        seats: Loop,
        route: Route,
        two: Sockets.Two,
    ) {
        drop(poller, seats)
        when (route) {
            is Route.Fixed -> route.conn.submitFromLoop(SecondLostWork(two.second))
            is Route.Demux -> Unit
        }
    }

    /** Closes the second socket, open or being made, whatever its ticket. */
    private fun drop(
        poller: Poller,
        seats: Loop,
    ) {
        when (val now = sockets) {
            is Sockets.Two -> {
                poller.remove(now.token)
                seats.unseat(now.token)
                io.closeSecond()
                sockets = Sockets.One(now.carrying)
            }
            is Sockets.Opening -> {
                io.closeSecond()
                sockets = Sockets.One(now.carrying)
            }
            is Sockets.One -> Unit
        }
    }

    private fun seat(
        endpoint: Endpoint,
        poller: Poller,
        seats: Loop,
        fixed: Route.Fixed,
        ticket: SocketTicket,
        local: PathKey,
        prior: RttPrior,
    ) {
        val token = seats.seat(endpoint)
        poller.add(refs.second, token)
        input = ReceiveInput.Maybe
        sockets = Sockets.Two(sockets.carrying(), ticket, token)
        fixed.conn.submitFromLoop(MovedWork(MoveTo(local, prior, ticket)))
    }

    /** No second socket came of a move: it is refused and the connection stays, or, for a socket that cannot move, ends. */
    private fun unopened(
        fixed: Route.Fixed,
        unopened: Beside,
    ) {
        when (unopened) {
            is Beside.Refused -> fixed.conn.submitFromLoop(MoveRefusedWork())
            is Beside.Unsupported -> fixed.conn.submitFromLoop(NetworkWork(NetworkChange.Moved))
            is Beside.Opened, is Beside.Arriving -> Unit
        }
    }
}

/** A client's connection is told its socket was reopened on the network up now. */
internal fun Route.rehomed() =
    when (this) {
        is Route.Fixed -> conn.submitFromLoop(NetworkWork(NetworkChange.Rehomed))
        is Route.Demux -> Unit
    }
