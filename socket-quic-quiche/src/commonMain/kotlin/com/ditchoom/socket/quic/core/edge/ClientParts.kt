package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.conn.ClientSession
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSide
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Hold
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.MAX_CACHED_TARGETS
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.traceRecorderFor
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel

/**
 * One client connection's parts on its loop: its socket's batches and caller lanes, the connection, the endpoint that
 * carries it, and the release that frees everything once the endpoint left the loop and nobody is inside.
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

    private val limits = BatchLimits(maxDatagrams = MAX_BATCH, maxDatagramSize = MAX_DATAGRAM)
    private val callerLimits = BatchLimits(maxDatagrams = CALLER_BATCH, maxDatagramSize = MAX_DATAGRAM)
    private val send = SendBatch(w.memory.native(limits.maxDatagrams * limits.maxDatagramSize), limits)
    private val lanes =
        FixedPool(CALLER_LANES) { i ->
            CallerSend(i, SendBatch(w.memory.native(callerLimits.maxDatagrams * callerLimits.maxDatagramSize), callerLimits, lane = i + 1))
        }
    private val socketIo =
        w.socket.ioOf(
            w.memory,
            listOf(send.storage) + List(CALLER_LANES) { lanes[it].batch.storage },
            limits.maxDatagrams,
            MAX_CACHED_TARGETS,
            w.running.loop.port::poke,
        )
    private val io = w.wrapIo(socketIo.io)
    private val loop = w.running.loop
    private val socket = SendPort(io, loop.port, CallerLanes.Of(lanes), SocketDiedNotice(), loop.counts, loop.callerCounts)
    private val alpn = w.memory.native(MAX_ALPN)
    val capture: TraceCapture = traceRecorderFor(w.options)
    private val edge = EdgeState(alpn.owner, capture)
    val conn: Conn =
        buildConn(
            rules =
                SlotRules.forWaits(
                    w.running.waits,
                    w.handshake,
                    closeRuleOf(w.options),
                    datagramRuleOf(w.options),
                    keepAliveRuleOf(w.options),
                ),
            q = QuicheConnRef(w.api, w.handle),
            side = ConnSide.Client(w.ids, w.offer, w.memory.native(ClientSession.TICKET_BYTES)),
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
            port = loop.port,
            socket = socket,
            clock = w.clock,
            sink = edge,
            loopEntrant = Entrant.loopTurn(loop.id, 1),
            counts = ConnCounts(),
        )

    /** Runs on the loop once the endpoint left it: frees the connection when nobody is inside, then its memory. */
    private val released =
        Runnable {
            conn.release(
                Runnable {
                    w.api.configFree(w.config)
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
    ): CoreConnection = CoreConnection(conn, edge, factory, remote, identity, w.readPolicy, w.writePolicy, w.scope)

    private val endpoint = Endpoint(socketIo.ref, io, socket, send, Route.Fixed(conn), released, w.readiness)

    /** The endpoint joins its loop and the connection's first flight goes out in the loop's next pass. */
    fun start() {
        loop.place(endpoint)
        conn.start()
    }

    /** Any thread, after [start]: the socket is reopened on the network that carries its address now. */
    fun rehome() = loop.rehome(endpoint)

    /**
     * Any thread, after [start]: the socket moves to [bind]'s address, on a network assumed to have a round trip of
     * [prior], and the connection migrates with it.
     */
    fun migrate(
        bind: PathKey,
        prior: RttPrior,
    ) = loop.move(endpoint, bind, prior)

    private companion object {
        /** Datagrams the loop collects for the network in one pass. */
        const val MAX_BATCH = 64

        /**
         * Lanes for threads that send in place, and the datagrams each holds. A thread that finds none free hands the
         * send to the loop, so the count bounds memory, not correctness: on Android a direct buffer lives on the Java
         * heap, so per-connection buffers decide how many connections a process holds.
         */
        const val CALLER_LANES = 2
        const val CALLER_BATCH = 8
        const val MAX_ALPN = 255
    }
}
