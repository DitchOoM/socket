package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSide
import com.ditchoom.socket.quic.core.conn.MoveAsk
import com.ditchoom.socket.quic.core.conn.MoveRefusal
import com.ditchoom.socket.quic.core.conn.MoveWanted
import com.ditchoom.socket.quic.core.conn.MoveWantedWork
import com.ditchoom.socket.quic.core.conn.RebindWork
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.conn.earlyPlanOf
import com.ditchoom.socket.quic.core.conn.nameQlog
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SharedBatch
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.loop.SocketOwnership
import com.ditchoom.socket.quic.core.loop.closeSecond
import com.ditchoom.socket.quic.core.loop.move
import com.ditchoom.socket.quic.core.loop.promote
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.HomeCell
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.path.SilenceRule
import com.ditchoom.socket.quic.core.path.SocketAsk
import com.ditchoom.socket.quic.core.pool.Hold
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.storages
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.MAX_CACHED_TARGETS
import com.ditchoom.socket.quic.core.udp.PeerKeys
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel

/**
 * One client connection's parts on its loop: its socket, the connection, the endpoint that carries it, and the release
 * that frees everything once the endpoint left the loop and nobody is inside. Its output goes through the loop's batch
 * and lanes, which the loop's connections share.
 */
internal class ClientParts(
    private val w: ClientWiring,
    private val peerSockAddr: EncodedSockAddr,
    private val localSockAddr: EncodedSockAddr,
) {
    init {
        // The loop's shared memory is held until this connection's memory is freed.
        when (val held = w.running.hold.acquire()) {
            is Hold.Held -> w.memory.releasing(held.claim)
            is Hold.AlreadyFreed -> raise(Raised.LoopsClosed, QuicCloseCircumstance.HandshakeFailed)
        }
    }

    private val ownership = w.socket.ownership()

    // Only the loop sends on a lent channel: its io takes one sender at a time.
    private val lanes =
        when (ownership) {
            is SocketOwnership.Owned -> w.running.lanes
            is SocketOwnership.Lent -> CallerLanes.None
        }
    private val socketIo =
        w.socket.ioOf(
            listOf(w.running.out.first.storage) + lanes.storages(),
            MAX_CACHED_TARGETS,
            PeerKeys.CLIENT_SETS,
            w.running.loop.port::poke,
        )
    private val io = w.wrapIo(socketIo.io)
    private val loop = w.running.loop
    private val socket = SendPort(io, loop.port, lanes, SocketDiedNotice(), loop.counts, loop.callerCounts)
    private val alpn = w.memory.native(MAX_ALPN)
    val capture: TraceCapture = w.capture

    /** The server's certificates the handshake copies for the connect's check. */
    val chain = KeptChain()
    private val edge = EdgeState(alpn.owner, capture, w.tickets, ChainHome.Kept(chain))

    /** What the connection's silence watch says of its path, and where its socket landed: both wake its network feed. */
    val news = PathNewsCell()
    val home = HomeCell(news.wake)

    /** What the connection asks of its sockets, each loop work on [endpoint]. */
    private val ask: (SocketAsk) -> Unit = { asked ->
        when (asked) {
            is SocketAsk.OpenBeside -> loop.move(endpoint, asked.ticket, asked.bind, asked.prior)
            is SocketAsk.Promote -> loop.promote(endpoint, asked.ticket)
            is SocketAsk.CloseSecond -> loop.closeSecond(endpoint, asked.ticket)
        }
    }
    val conn: Conn =
        buildConn(
            rules =
                SlotRules.forWaits(
                    w.running.waits,
                    w.handshake,
                    closeChoiceOf(w.options),
                    datagramRuleOf(w.options),
                    keepAliveRuleOf(w.options),
                    mismatch = w.mismatch,
                    writeCut = w.options.abortCodes.writeCut,
                ),
            q = QuicheConnRef(w.api, w.handle),
            side =
                ConnSide.Client(
                    w.ids,
                    w.offer,
                    w.running.sessions,
                    w.kept,
                    w.tokens,
                    ask,
                    w.access,
                    chainSourceOf(w.options, w.memory.factory()),
                    SilenceRule(w.silence, probeCapOf(w.options), news, home),
                    earlyPlanOf(QuicheConnRef(w.api, w.handle), w.offer, w.options.resumption, nativeOf(w.memory.factory())),
                ),
            address =
                ConnAddress(
                    SockAddr(peerSockAddr.address, peerSockAddr.length),
                    SockAddr(localSockAddr.address, localSockAddr.length),
                    w.peer,
                    w.socket.local(),
                    SendTarget.ConnectedPeer,
                ),
            memory = ConnMemory(w.running.shared, w.memory.factory(), alpn),
            flight = w.flight,
            capture = capture,
            qlog = w.api.nameQlog(w.handle, capture, QuicRole.Client, w.session),
            port = loop.port,
            socket = socket,
            clock = w.clock,
            time = loop.time,
            sink = edge,
            loopEntrant = Entrant.loopTurn(loop.id, 1),
            counts = ConnCounts(),
        )

    /** Runs on the loop once the endpoint left it: frees the connection when nobody is inside, then its memory. */
    private val released =
        Runnable {
            conn.release(
                Runnable {
                    peerSockAddr.free()
                    localSockAddr.free()
                    w.memory.freeAll()
                    w.scope.cancel()
                },
            )
        }

    fun connection(
        factory: BufferFactory,
        remote: SocketAddress,
        identity: QuicConnectionIdentity,
    ): CoreConnection =
        CoreConnection(
            conn,
            edge,
            factory,
            remote,
            identity,
            w.readPolicy,
            w.writePolicy,
            w.options.streamOpenPolicy.toDeadline(),
            w.scope,
            HandlerSeat(w.running.handlers.pool(::poolContained), loop.handlers, identity.session),
            w.options.abortCodes,
            w.socket.hold(),
        )

    private val route = Route.Fixed(conn, SharedBatch(w.running.out))
    private val endpoint = Endpoint(socketIo.refs, io, socket, route, released, w.readiness, ownership)

    /** Tests: the endpoint on the loop that carries this connection's sockets. */
    internal val loopEndpoint: Endpoint get() = endpoint

    /** The endpoint joins its loop and the connection's first flight goes out in the loop's next pass. */
    fun start() {
        loop.place(endpoint)
        conn.start()
    }

    /**
     * The setup's thread, only if [start] never ran: closes the socket, gives back the loop's seat and releases the
     * connection here, which hands an unsent ticket back and frees quiche's connection, the sockaddrs, the scope and
     * the memory.
     */
    fun releaseUnstarted() {
        io.close()
        loop.seatGivenBack()
        released.run()
    }

    /** Any thread, after [start]: the socket is reopened on the network that carries its address now. */
    fun rehome() = loop.rehome(endpoint)

    /**
     * Any thread, after [start]: a second socket opens on [bind]'s address, on a network assumed to have a round trip
     * of [prior], once the connection can probe the path from there; the connection migrates to it once the path
     * validates, the old socket carrying until then. A connection on a lent socket is refused here, at once: no socket
     * can replace the caller's.
     */
    fun migrate(
        bind: PathKey,
        prior: RttPrior,
    ): MoveAsk =
        when (ownership) {
            is SocketOwnership.Owned -> MoveAsk.Asked.also { conn.submit(MoveWantedWork(MoveWanted(bind, prior))) }
            is SocketOwnership.Lent -> LENT_REFUSED
        }

    /**
     * Any thread, after [start]: the path was read silent, so a second socket is wanted on a fresh port of [bind]'s
     * address; the connection opens it only if the path is still silent and no move is open, and migrates as [migrate].
     */
    fun rebind(
        bind: PathKey,
        prior: RttPrior,
    ) = conn.submit(RebindWork(MoveWanted(bind, prior)))

    private companion object {
        val LENT_REFUSED = MoveAsk.Refused(MoveRefusal.SocketLent)

        const val MAX_ALPN = 255
    }
}
