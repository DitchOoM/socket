package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicConnectionIdentity
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSide
import com.ditchoom.socket.quic.core.conn.MoveWanted
import com.ditchoom.socket.quic.core.conn.MoveWantedWork
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.loop.SharedBatch
import com.ditchoom.socket.quic.core.loop.SocketDiedNotice
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.pool.Hold
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.storages
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.MAX_CACHED_TARGETS
import com.ditchoom.socket.quic.core.udp.PeerKeys
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.traceRecorderFor
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

    private val lanes = w.running.lanes
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
    val capture: TraceCapture = traceRecorderFor(w.options)

    /** The server's certificates the handshake copies for the connect's check. */
    val chain = KeptChain()
    private val edge = EdgeState(alpn.owner, capture, w.tickets, ChainHome.Kept(chain))
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
                ),
            q = QuicheConnRef(w.api, w.handle),
            side =
                ConnSide.Client(
                    w.ids,
                    w.offer,
                    w.running.sessions,
                    w.kept,
                    w.tokens,
                    { bind, prior -> loop.move(endpoint, bind, prior) },
                    chainSourceOf(w.options, w.memory.factory()),
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
    ): CoreConnection = CoreConnection(conn, edge, factory, remote, identity, w.readPolicy, w.writePolicy, w.scope)

    private val endpoint = Endpoint(socketIo.ref, io, socket, Route.Fixed(conn, SharedBatch(w.running.out)), released, w.readiness)

    /** The endpoint joins its loop and the connection's first flight goes out in the loop's next pass. */
    fun start() {
        loop.place(endpoint)
        conn.start()
    }

    /**
     * The setup's thread, only if [start] never ran: closes the socket, gives back the loop's seat and releases the
     * connection here, which hands an unsent ticket back and frees quiche's connection, the sockaddrs, the scope and the
     * memory.
     */
    fun releaseUnstarted() {
        io.close()
        loop.seatGivenBack()
        released.run()
    }

    /** Any thread, after [start]: the socket is reopened on the network that carries its address now. */
    fun rehome() = loop.rehome(endpoint)

    /**
     * Any thread, after [start]: the socket moves to [bind]'s address, on a network assumed to have a round trip of
     * [prior], once the connection can probe the path from there, and the connection migrates with it.
     */
    fun migrate(
        bind: PathKey,
        prior: RttPrior,
    ) = conn.submit(MoveWantedWork(MoveWanted(bind, prior)))

    private companion object {
        const val MAX_ALPN = 255
    }
}
