@file:OptIn(ExperimentalAtomicApi::class, ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicWireConnectionId
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.conn.ConnPath
import com.ditchoom.socket.quic.core.conn.DatagramSlots
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.loop.Collected
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClosed
import com.ditchoom.socket.quic.core.loop.LoopParts
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.toInetSocketAddress
import com.ditchoom.socket.quic.toNativeSockAddr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.net.InetSocketAddress
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What a server's one loop does with the datagrams from its one unconnected socket: routes each by destination
 * connection id to the connection it belongs to, answers an unsupported version with Version Negotiation, answers a
 * first Initial with Retry when address validation is on, and otherwise accepts the Initial as a new connection when
 * the admission bounds allow. Collects the output of every connection the loop was inside, after the stateless
 * replies that were queued.
 *
 * Every field is the loop thread's. A connection owns its peer address, `recv_info` and send target, built once when
 * it is accepted; no table is keyed by peer address.
 */
internal class ServerParts(
    private val s: ServerSetup,
) : LoopParts {
    private var attached: Attached = Attached.Nothing
    private val header = PacketHeader()
    private val table = RouteTable<ServerPeer>()
    private val peers = ArrayList<ServerPeer>()
    private var touchedHead: PeerLink = PeerLink.End
    private var shutdown: Shutdown = Shutdown.Running
    private val handshakes = Handshakes()
    private val stateless = StatelessReplies(s.factory)
    private val headerScratch = HeaderScratch(s.factory)
    private val tokens =
        when (val validation = s.serverConfig.addressValidation) {
            AddressValidation.Off -> Tokens.None
            is AddressValidation.Retry -> Tokens.Of(AddressTokens(s.factory, s.random, validation.lifetime.inWholeNanoseconds))
        }
    private val newScid = s.factory.allocate(QUIC_ISSUED_CONN_ID_LEN)
    private val tokenOut = s.factory.allocate(MAX_TOKEN)
    private val oneScid = CidRead()
    private val tick = Tick()
    private var admissionPass = -1L
    private var startedThisPass = 0

    /** Once, before the loop's first pass. */
    fun attach(loop: Loop) {
        attached = Attached.To(loop)
    }

    // --- datagrams ---

    override fun onDatagram(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        when (header.read(buffer.buffer, length)) {
            Wire.Short, Wire.OtherV1 -> deliver(buffer, length)
            Wire.InitialV1 ->
                when (val route = table.find(header.cid)) {
                    is Route<ServerPeer> -> deliverTo(route.owner, buffer, length)
                    RouteLink.End -> accept(buffer, length, from)
                }
            Wire.UnsupportedVersion ->
                if (length < MIN_INITIAL) {
                    s.counts.droppedRunt++
                } else {
                    stateless.negotiateVersion(s.api, header, buffer, from, s.counts, s.localKey)
                }
            Wire.Unroutable -> s.counts.droppedUnknown++
        }
    }

    private fun deliver(
        buffer: ReceiveBuffer,
        length: Int,
    ) {
        when (val route = table.find(header.cid)) {
            is Route<ServerPeer> -> deliverTo(route.owner, buffer, length)
            RouteLink.End -> s.counts.droppedUnknown++
        }
    }

    private fun deliverTo(
        peer: ServerPeer,
        buffer: ReceiveBuffer,
        length: Int,
    ) {
        s.counts.routed++
        peer.conn.received(buffer, length)
    }

    // --- accepting ---

    private fun accept(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
    ) {
        if (shutdown !== Shutdown.Running) {
            s.counts.droppedUnknown++
            return
        }
        if (length < MIN_INITIAL) {
            s.counts.droppedRunt++
            return
        }
        when (val validation = tokens) {
            Tokens.None -> admit(buffer, length, from, Admit.Fresh)
            is Tokens.Of -> {
                val tokenLength = headerScratch.readToken(s.api, buffer, length)
                if (tokenLength < 0) {
                    s.counts.droppedUnknown++
                } else if (tokenLength == 0) {
                    retry(validation.tokens, buffer, from)
                } else {
                    val checked = validation.tokens.check(headerScratch.token, tokenLength, s.clock.nowNanos(), header.cid, from)
                    when (checked) {
                        is TokenCheck.Valid -> admit(buffer, length, from, Admit.Redeemed(checked))
                        TokenCheck.Malformed, TokenCheck.Forged, TokenCheck.Expired -> s.counts.droppedToken++
                    }
                }
            }
        }
    }

    /** Answers an Initial that carries no token with a Retry that does. */
    private fun retry(
        tokens: AddressTokens,
        buffer: ReceiveBuffer,
        from: PathKey,
    ) {
        val c0 = s.random.nextLong()
        val c1 = s.random.nextLong()
        newScid.resetForWrite()
        newScid.writeLong(c0)
        newScid.writeLong(c1)
        val tokenLength = tokens.mint(tokenOut, s.clock.nowNanos(), header.cid, c0, c1, from)
        stateless.retry(s.api, header, buffer, from, addressOf(newScid), addressOf(tokenOut), tokenLength, s.counts, s.localKey)
    }

    private fun admit(
        buffer: ReceiveBuffer,
        length: Int,
        from: PathKey,
        how: Admit,
    ) {
        val loop = (attached as Attached.To).loop
        val pass = loop.counts.passes
        if (pass != admissionPass) {
            admissionPass = pass
            startedThisPass = 0
        }
        if (startedThisPass >= s.serverConfig.admission.perPass || handshakes.inProgress >= s.serverConfig.admission.inProgress) {
            s.counts.droppedAdmission++
            return
        }
        val scidAddress: Long
        val odcidAddress: Long
        val odcidLength: Int
        when (how) {
            Admit.Fresh -> {
                newScid.resetForWrite()
                newScid.writeLong(s.random.nextLong())
                newScid.writeLong(s.random.nextLong())
                scidAddress = addressOf(newScid)
                oneScid.set(newScid.getLong(0), newScid.getLong(WORD), 0L, QUIC_ISSUED_CONN_ID_LEN)
                odcidAddress = 0L
                odcidLength = 0
            }
            is Admit.Redeemed -> {
                scidAddress = buffer.address + header.dcidOffset
                oneScid.set(header.cid.c0, header.cid.c1, 0L, QUIC_ISSUED_CONN_ID_LEN)
                odcidAddress = addressOf(headerScratch.token) + how.token.originalDcidOffset
                odcidLength = how.token.originalDcidLength
            }
        }
        startedThisPass++
        val peerSocket = (from.toInetSocketAddress() as InetSocketAddress)
        val created = create(buffer, from, peerSocket, scidAddress, odcidAddress, odcidLength, loop)
        if (created !is Created.Peer) return
        val peer = created.peer
        s.counts.accepted++
        peer.scidRoute = table.add(oneScid, peer)
        if (how === Admit.Fresh) peer.originalRoute = table.add(header.cid, peer)
        peer.slot = peers.size
        peers.add(peer)
        peer.conn.received(buffer, length)
    }

    private fun create(
        buffer: ReceiveBuffer,
        from: PathKey,
        peerSocket: InetSocketAddress,
        scidAddress: Long,
        odcidAddress: Long,
        odcidLength: Int,
        loop: Loop,
    ): Created {
        val peerAddress = peerSocket.toNativeSockAddr(s.factory)
        val handle =
            try {
                s.api.accept(
                    scidAddress,
                    QUIC_ISSUED_CONN_ID_LEN,
                    odcidAddress,
                    odcidLength,
                    s.local.address,
                    s.local.length,
                    peerAddress.address,
                    peerAddress.length,
                    s.config,
                )
            } catch (refused: Exception) {
                peerAddress.free()
                s.counts.droppedUnknown++
                return Created.Refused
            }
        if (handle.handle == 0L) {
            peerAddress.free()
            s.counts.droppedUnknown++
            return Created.Refused
        }
        val path =
            ConnPath(
                s.api.recvInfoNew(peerAddress.address, peerAddress.length, s.local.address, s.local.length),
                s.api.sendInfoNew(),
                SendTarget.ServerReply(to = from, from = ReplySource.Recorded(s.localKey)),
            )
        s.counts.recvInfosBuilt++
        val scope = CoroutineScope(SupervisorJob())
        val id = hexOf(oneScid)
        val connection =
            CoreConnection(
                bufferFactory = s.factory,
                remoteAddress = SocketAddress.ofLiteral(literalOf(peerSocket), peerSocket.port),
                identity = QuicConnectionIdentity(QuicSessionId(id), QuicWireConnectionId.Known(id)),
                role = QuicRole.Server,
                readPolicy = s.readPolicy,
                writePolicy = s.writePolicy,
                scope = scope,
            )
        val peer =
            ServerPeer(
                parts = this,
                loop = loop,
                peer = from,
                peerAddress = peerAddress,
                path = path,
                api = s.api,
                handle = handle,
                clock = s.clock,
                events = AcceptedEvents(connection, handshakes) { s.accepted.trySend(it) },
                slots = DatagramSlots.Of(s.slots),
                factory = s.factory,
                connection = connection,
                scope = scope,
            )
        connection.bind(peer.conn)
        connection.whenEnded { loop.submit(Unroute(this, peer)) }
        s.resources.acquire()
        handshakes.began()
        return Created.Peer(peer)
    }

    // --- connections ---

    /** The loop is inside [peer] in this pass. */
    internal fun touched(peer: ServerPeer) {
        if (peer.nextTouched !== PeerLink.NotQueued) return
        peer.nextTouched = touchedHead
        touchedHead = peer
    }

    /** Loop thread: [peer] ended. Its routes go, and its memory is released once nobody is inside it. */
    internal fun unroute(peer: ServerPeer) {
        when (val route = peer.scidRoute) {
            is Route<ServerPeer> -> table.remove(route)
            RouteLink.End -> Unit
        }
        when (val route = peer.originalRoute) {
            is Route<ServerPeer> -> table.remove(route)
            RouteLink.End -> Unit
        }
        peer.scidRoute = RouteLink.End
        peer.originalRoute = RouteLink.End
        if (peer.slot >= 0) {
            val last = peers.removeAt(peers.size - 1)
            if (last !== peer) {
                peers[peer.slot] = last
                last.slot = peer.slot
            }
            peer.slot = -1
        }
        release(peer)
    }

    /** Releases [peer]'s connection to whoever is inside it last, and then its memory. */
    internal fun release(peer: ServerPeer) = peer.conn.release(peer.freed(s.resources))

    override fun collect(out: SendBatch): Collected {
        if (stateless.writeInto(out) === Collected.MoreWaiting) return Collected.MoreWaiting
        while (true) {
            val head = touchedHead
            if (head !is ServerPeer) break
            if (head.conn.collect(out) === Collected.MoreWaiting) {
                if (out.room() === Room.Full) return Collected.MoreWaiting
                continue
            }
            touchedHead = head.nextTouched
            head.nextTouched = PeerLink.NotQueued
        }
        finishShutdownWhenFlushed()
        return Collected.Everything
    }

    // --- ending ---

    /** Loop thread: stops accepting, tells every connection to close, and stops the loop once they are flushed. */
    fun beginShutdown(closed: LoopClosed) {
        val loop = (attached as Attached.To).loop
        shutdown = Shutdown.Draining(loop.counts.passes, closed)
        for (peer in peers) peer.close(QuicError.NoError)
        // A pass after this one is owed even when every connection was held by a caller and queued nothing here.
        loop.submit(tick)
    }

    private fun finishShutdownWhenFlushed() {
        val now = shutdown
        if (now !is Shutdown.Draining) return
        val loop = (attached as Attached.To).loop
        if (loop.counts.passes <= now.startedPass || touchedHead !== PeerLink.End) return
        shutdown = Shutdown.Finished
        for (peer in ArrayList(peers)) peer.conn.serverClosed()
        loop.beginClosing(
            LoopClosed { counts ->
                loopEnded(LoopEnd.Stopped)
                now.closed.closed(counts)
            },
        )
    }

    override fun loopFailed(cause: Throwable) {
        for (peer in ArrayList(peers)) peer.conn.loopFailed(cause)
        loopEnded(LoopEnd.Failed(cause))
    }

    /** The loop is over: what only it used is freed, and the memory connections share is released from its hold. */
    private fun loopEnded(end: LoopEnd) {
        when (end) {
            LoopEnd.Stopped -> s.accepted.close()
            is LoopEnd.Failed -> s.accepted.close(end.cause)
        }
        peers.clear()
        stateless.free()
        headerScratch.free()
        newScid.freeNativeMemory()
        tokenOut.freeNativeMemory()
        s.resources.release()
    }

    /** The connections alive, for tests. */
    val connections: Int get() = peers.size

    private companion object {
        const val MIN_INITIAL = 1200
        const val MAX_TOKEN = 256
        const val WORD = 8
    }
}

/** How a server's loop ended: its owner closed it, or it failed. */
private sealed interface LoopEnd {
    object Stopped : LoopEnd

    class Failed(
        val cause: Throwable,
    ) : LoopEnd
}
