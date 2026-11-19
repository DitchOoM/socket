package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** Whether an Initial that would start a handshake is held for later or started now. */
private enum class Mode {
    /** The loop is reading its socket: nothing slow is done until the read is over. */
    Receiving,

    /** The loop is starting handshakes from the backlog. */
    Admitting,
}

/**
 * A server's routing: each datagram from the one unconnected socket goes to the connection its destination id names;
 * an unsupported version is answered with Version Negotiation, an Initial without a token that proves its address
 * with Retry when address validation is on, and otherwise an Initial starts a connection through [admission]. Serves
 * every connection it routes to and removes each one that ended. Every connection owns its peer's address and
 * `recv_info`, built once when it is accepted; no table is keyed by address. Loop thread only.
 */
@LoopOnly
internal class Demux(
    private val admission: Admission,
    private val stateless: Stateless,
    private val scratch: DemuxScratch,
    private val port: LoopPort,
    private val clock: LoopClock,
    private val counts: ServerCounts,
) {
    private val header = PacketHeader()
    private val table = RouteTable<ServerPeer>()
    private val spares = SpareRoutes(table, admission)
    private val peers = ArrayList<ServerPeer>()
    private val ended = EndedIds(ENDED_IDS)
    private val life = ServerLife()
    private val tick = PassTick()
    private var mode = Mode.Receiving

    /** The connections routed to: read it on the loop, or once the server is quiet. */
    val connections: Int get() = peers.size

    /** The connection ids routed, spare ids included: read it on the loop, or once the server is quiet. */
    val routes: Int get() = table.size

    fun phase(): ServerPhase = life.view()

    fun passBegins() {
        admission.passBegins()
    }

    /** Routes every datagram of [batch]; replies without a connection are written into [out]. */
    fun route(
        batch: ReceiveBatch,
        out: SendBatch,
        at: Nanos,
    ) {
        for (i in 0 until batch.count) routeOne(batch.bufferAt(i).bytes, batch.lengthAt(i), batch.fromAt(i), batch.toAt(i), out, at)
    }

    /** Starts a chunk of the held Initials, oldest first; [Admitted.More] when the loop should read and come back. */
    fun admitSome(out: SendBatch): Admitted {
        admission.chunkBegins()
        mode = Mode.Admitting
        val backlog = admission.backlog
        val at = clock.now()
        while (admission.mayTakeMore()) {
            routeOne(backlog.oldest(), backlog.oldestLength(), backlog.oldestFrom(), backlog.oldestTo(), out, at)
            backlog.dropOldest()
        }
        mode = Mode.Receiving
        return admission.afterChunk()
    }

    /** Serves every connection the loop is inside, removes the ended ones, and steps the shutdown. */
    fun turn(out: SendBatch): Collected {
        var i = 0
        while (i < peers.size) {
            val peer = peers[i]
            when (peer.conn.turn(out)) {
                Collected.MoreWaiting -> if (out.room() == Room.Full) return Collected.MoreWaiting else continue
                Collected.Everything, Collected.Abandoned, Collected.AckHeld -> Unit
            }
            if (peer.conn.life() is Life.Ended) {
                unroute(peer)
            } else {
                spares.retire(peer)
                spares.issue(peer)
                i++
            }
        }
        when (life.step(clock.now(), unsettled())) {
            ServerStep.Serving -> Unit
            ServerStep.Finish -> endAll(ConnEnd.ServerClosed)
            ServerStep.FinishAtBound -> endAtBound()
        }
        if (admission.owesPass()) tick.owe(port)
        return Collected.Everything
    }

    /**
     * Stops taking connections and tells each one to close; the server stops once every close is out, or at the drain
     * bound. A connection the loop may enter is served in the next pass; a caller inside wakes the loop when it leaves.
     */
    fun beginShutdown() {
        if (life.begin(clock.now(), port) != ServerPhase.Running) return
        for (peer in peers) peer.conn.closeForServer()
    }

    /** Ends every connection with [end] and lets go of each; the server takes no more. */
    fun endAll(end: ConnEnd) {
        life.stopped()
        for (peer in peers) peer.conn.endWithLoop(end)
        while (peers.isNotEmpty()) unroute(peers[peers.size - 1])
    }

    /** How many connections still have a close that is not out. */
    private fun unsettled(): Int {
        var count = 0
        for (peer in peers) if (!peer.conn.closeSettled()) count++
        return count
    }

    /** The drain bound passed: each connection whose close is not out ends saying so, and is counted. */
    private fun endAtBound() {
        life.stopped()
        for (peer in peers) {
            if (peer.conn.closeSettled()) {
                peer.conn.endWithLoop(ConnEnd.ServerClosed)
            } else {
                counts.closeUnsettled()
                peer.conn.endWithLoop(ConnEnd.ServerClosedUnsettled)
            }
        }
        while (peers.isNotEmpty()) unroute(peers[peers.size - 1])
    }

    private fun routeOne(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
        out: SendBatch,
        at: Nanos,
    ) {
        when (header.read(datagram, length)) {
            Wire.Short, Wire.OtherV1 -> deliver(datagram, length, from, at)
            Wire.InitialV1 ->
                when (val route = table.find(header.cid)) {
                    is Route<ServerPeer> -> deliverTo(route.owner, datagram, length, from, at)
                    is RouteLink.End ->
                        when (ended.find(header.cid)) {
                            is Route<EndedId> -> counts.unknownDropped()
                            is RouteLink.End -> start(datagram, length, from, to, out, at)
                        }
                }
            Wire.UnsupportedVersion ->
                if (length < MIN_INITIAL) counts.runtDropped() else stateless.negotiate(header, datagram, from, to, out)
            Wire.Unroutable -> counts.unknownDropped()
        }
    }

    private fun deliver(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        at: Nanos,
    ) = when (val route = table.find(header.cid)) {
        is Route<ServerPeer> -> deliverTo(route.owner, datagram, length, from, at)
        is RouteLink.End -> counts.unknownDropped()
    }

    private fun deliverTo(
        peer: ServerPeer,
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        at: Nanos,
    ) {
        counts.routedOne()
        peer.conn.received(datagram, length, from, at)
    }

    /** An Initial that names no connection: a new one, if the server takes it. */
    private fun start(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
        out: SendBatch,
        at: Nanos,
    ) {
        if (life.view() != ServerPhase.Running) return counts.unknownDropped()
        if (length < MIN_INITIAL) return counts.runtDropped()
        when (val tokens = stateless.tokens) {
            is Tokens.None -> admit(Admit.Fresh, datagram, length, from, to, at)
            is Tokens.Of ->
                when (val screened = stateless.screen(tokens, header, datagram, length, from, to, scratch, clock.now(), out)) {
                    is Screened.Start -> admit(screened.how, datagram, length, from, to, at)
                    is Screened.Answered -> Unit
                }
        }
    }

    private fun admit(
        how: Admit,
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
        at: Nanos,
    ) {
        when (mode) {
            Mode.Receiving -> return admission.hold(datagram, length, from, to)
            Mode.Admitting -> Unit
        }
        val accepted =
            when (val accepted = admission.accept(how, datagram, header, from, to, scratch)) {
                is Accepted.Peer -> accepted
                is Accepted.Refused -> return
            }
        val peer = accepted.peer
        stateless.accepted(accepted.handle, how, from, scratch, clock.now())
        val original =
            when (how) {
                is Admit.Fresh, is Admit.Proven -> table.add(header.cid, peer)
                is Admit.Redeemed -> RouteLink.End
            }
        peer.routed(table.add(admission.newCid, peer), original)
        peer.listedAt(peers.size)
        peers.add(peer)
        peer.conn.received(datagram, length, from, at)
    }

    /** [peer] ended: its routes go, and its memory is released once nobody is inside it. */
    private fun unroute(peer: ServerPeer) {
        when (val route = peer.scidRoute) {
            is Route<ServerPeer> -> table.remove(route)
            is RouteLink.End -> Unit
        }
        when (val route = peer.originalRoute) {
            is Route<ServerPeer> -> {
                table.remove(route)
                ended.remember(route)
            }
            is RouteLink.End -> Unit
        }
        peer.spareRoutes { table.remove(it) }
        when (peer.listing) {
            Listing.Listed -> {
                val last = peers.removeAt(peers.size - 1)
                if (last !== peer) {
                    peers[peer.index] = last
                    last.listedAt(peer.index)
                }
            }
            Listing.Unlisted -> Unit
        }
        peer.unlisted()
        peer.conn.release(peer.freed)
    }

    private companion object {
        /** RFC 9000 section 14.1: a client's first datagram is at least this large. */
        const val MIN_INITIAL = 1200

        /** Ended connections whose first destination id a stale Initial still finds: the default backlog's size. */
        const val ENDED_IDS = AdmissionBounds.DEFAULT_BACKLOG
    }
}
