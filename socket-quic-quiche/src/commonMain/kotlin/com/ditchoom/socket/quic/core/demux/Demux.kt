package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
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
 * an unsupported version is answered with Version Negotiation, a first Initial with Retry when address validation is
 * on, and otherwise an Initial starts a connection through [admission]. Serves every connection it routes to and
 * removes each one that ended. Every connection owns its peer's address and `recv_info`, built once when it is
 * accepted; no table is keyed by address. Loop thread only.
 */
@LoopOnly
internal class Demux(
    private val api: QuicheApi,
    private val admission: Admission,
    private val stateless: Stateless,
    private val scratch: DemuxScratch,
    private val port: LoopPort,
    private val clock: LoopClock,
    private val counts: ServerCounts,
) {
    private val header = PacketHeader()
    private val table = RouteTable<ServerPeer>()
    private val peers = ArrayList<ServerPeer>()
    private val life = ServerLife()
    private val tick = PassTick()
    private var passes = 0L
    private var mode = Mode.Receiving

    /** The connections routed to: read it on the loop, or once the server is quiet. */
    val connections: Int get() = peers.size

    fun phase(): ServerPhase = life.view()

    fun passBegins() {
        passes++
        admission.passBegins()
    }

    /** Routes every datagram of [batch]; replies without a connection are written into [out]. */
    fun route(
        batch: ReceiveBatch,
        out: SendBatch,
        at: Nanos,
    ) {
        for (i in 0 until batch.count) routeOne(batch.bufferAt(i).bytes, batch.lengthAt(i), batch.fromAt(i), out, at)
    }

    /** Starts a chunk of the held Initials, oldest first; [Admitted.More] when the loop should read and come back. */
    fun admitSome(out: SendBatch): Admitted {
        admission.chunkBegins()
        mode = Mode.Admitting
        val backlog = admission.backlog
        val at = clock.now()
        while (admission.mayTakeMore()) {
            routeOne(backlog.oldest(), backlog.oldestLength(), backlog.oldestFrom(), out, at)
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
                Collected.Everything, Collected.Abandoned -> Unit
            }
            if (peer.conn.life() is Life.Ended) unroute(peer) else i++
        }
        when (life.step(passes)) {
            ServerStep.Serving -> Unit
            ServerStep.Finish -> endAll(ConnEnd.ServerClosed)
        }
        if (admission.owesPass()) tick.owe(port)
        return Collected.Everything
    }

    /** Stops taking connections and tells each one to close; the server stops once their closes went out. */
    fun beginShutdown() {
        if (life.begin(passes) != ServerPhase.Running) return
        for (peer in peers) peer.conn.submit(ServerCloseWork(), CallerPlace.HandToLoop)
        tick.owe(port)
    }

    /** Ends every connection with [end] and lets go of each; the server takes no more. */
    fun endAll(end: ConnEnd) {
        life.stopped()
        for (peer in peers) peer.conn.endWithLoop(end)
        while (peers.isNotEmpty()) unroute(peers[peers.size - 1])
    }

    private fun routeOne(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        out: SendBatch,
        at: Nanos,
    ) {
        when (header.read(datagram, length)) {
            Wire.Short, Wire.OtherV1 -> deliver(datagram, length, at)
            Wire.InitialV1 ->
                when (val route = table.find(header.cid)) {
                    is Route<ServerPeer> -> deliverTo(route.owner, datagram, length, at)
                    is RouteLink.End -> start(datagram, length, from, out, at)
                }
            Wire.UnsupportedVersion ->
                if (length < MIN_INITIAL) counts.runtDropped() else stateless.negotiate(header, datagram, from, out)
            Wire.Unroutable -> counts.unknownDropped()
        }
    }

    private fun deliver(
        datagram: NativeBytes,
        length: Int,
        at: Nanos,
    ) = when (val route = table.find(header.cid)) {
        is Route<ServerPeer> -> deliverTo(route.owner, datagram, length, at)
        is RouteLink.End -> counts.unknownDropped()
    }

    private fun deliverTo(
        peer: ServerPeer,
        datagram: NativeBytes,
        length: Int,
        at: Nanos,
    ) {
        counts.routedOne()
        peer.conn.received(datagram, length, at)
    }

    /** An Initial that names no connection: a new one, if the server takes it. */
    private fun start(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        out: SendBatch,
        at: Nanos,
    ) {
        if (life.view() != ServerPhase.Running) return counts.unknownDropped()
        if (length < MIN_INITIAL) return counts.runtDropped()
        when (val tokens = stateless.tokens) {
            is Tokens.None -> admit(Admit.Fresh, datagram, length, from, at)
            is Tokens.Of ->
                when (scratch.readToken(api, datagram, length)) {
                    TokenRead.Refused -> counts.unknownDropped()
                    TokenRead.Absent -> stateless.retry(tokens.tokens, header, datagram, from, scratch, clock.now(), out)
                    TokenRead.Present -> redeem(tokens.tokens, datagram, length, from, at)
                }
        }
    }

    private fun redeem(
        tokens: AddressTokens,
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        at: Nanos,
    ) {
        val checked = tokens.check(scratch.token.owner, scratch.tokenLength, clock.now().value, header.cid, from)
        when (checked) {
            is TokenCheck.Valid -> admit(Admit.Redeemed(checked), datagram, length, from, at)
            is TokenCheck.Malformed, is TokenCheck.Forged, is TokenCheck.Expired -> counts.tokenDropped()
        }
    }

    private fun admit(
        how: Admit,
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        at: Nanos,
    ) {
        when (mode) {
            Mode.Receiving -> return admission.hold(datagram, length, from)
            Mode.Admitting -> Unit
        }
        val peer =
            when (val accepted = admission.accept(how, datagram, header, from, scratch)) {
                is Accepted.Peer -> accepted.peer
                is Accepted.Refused -> return
            }
        val original =
            when (how) {
                is Admit.Fresh -> table.add(header.cid, peer)
                is Admit.Redeemed -> RouteLink.End
            }
        peer.routed(table.add(admission.newCid, peer), original)
        peer.listedAt(peers.size)
        peers.add(peer)
        peer.conn.received(datagram, length, at)
    }

    /** [peer] ended: its routes go, and its memory is released once nobody is inside it. */
    private fun unroute(peer: ServerPeer) {
        when (val route = peer.scidRoute) {
            is Route<ServerPeer> -> table.remove(route)
            is RouteLink.End -> Unit
        }
        when (val route = peer.originalRoute) {
            is Route<ServerPeer> -> table.remove(route)
            is RouteLink.End -> Unit
        }
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
    }
}
