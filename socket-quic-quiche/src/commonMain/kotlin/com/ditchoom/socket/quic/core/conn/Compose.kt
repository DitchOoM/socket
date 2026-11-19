package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.ConnLife
import com.ditchoom.socket.quic.core.life.Linger
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.ClientPaths
import com.ditchoom.socket.quic.core.path.IdIssuer
import com.ditchoom.socket.quic.core.path.IdsRetired
import com.ditchoom.socket.quic.core.path.IdsWanted
import com.ditchoom.socket.quic.core.path.ServerPaths
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.WorkInbox
import com.ditchoom.socket.quic.core.stream.StreamIo
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlin.random.Random

/** The memory one connection is built with: what its loop's connections share, and its own ALPN scratch. */
internal class ConnMemory(
    val shared: LoopMemory,
    val factory: BufferFactory,
    /** Where the negotiated ALPN is copied at establishment. */
    val alpn: NativeBytes,
)

/** Which end of a connection this is, with what that end alone needs. */
internal sealed interface ConnSide {
    /**
     * A client draws the ids it hands its peer from [ids], offered quiche [offer] before its first flight, and copies
     * its server's session ticket through one of its loop's [scratch] slots; what it took from its stores, [kept], goes
     * back if it never sent; its server's NEW_TOKEN token goes to [tokens]; its socket is moved by [mover].
     */
    class Client(
        val ids: Random,
        val offer: Offer,
        val scratch: FixedPool<SessionScratch>,
        val kept: KeptTicket,
        val tokens: TokenHome,
        val mover: (PathKey, RttPrior) -> Unit,
    ) : ConnSide

    /** A server hands its peer the ids its loop routes to it. */
    data object Server : ConnSide
}

/** Where a connection's datagrams go and come from, and how it is recorded. */
internal class ConnAddress(
    val peer: SockAddr,
    val local: SockAddr,
    val peerKey: PathKey,
    val localKey: PathKey,
    val target: SendTarget,
)

/** The only place a variant or a component of a connection is constructed. */
internal fun buildConn(
    rules: SlotRules,
    q: QuicheConnRef,
    side: ConnSide,
    address: ConnAddress,
    memory: ConnMemory,
    flight: FlightSize,
    capture: TraceCapture,
    port: LoopPort,
    socket: SendPort,
    clock: LoopClock,
    sink: ConnSink,
    loopEntrant: Entrant,
    counts: ConnCounts,
): Conn =
    q.composedAround(SinglePath(q, address.peer, address.local, address.target)) { first ->
        composed(rules, q, side, address, memory, flight, capture, port, socket, clock, sink, loopEntrant, counts, first)
    }

private fun composed(
    rules: SlotRules,
    q: QuicheConnRef,
    side: ConnSide,
    address: ConnAddress,
    memory: ConnMemory,
    flight: FlightSize,
    capture: TraceCapture,
    port: LoopPort,
    socket: SendPort,
    clock: LoopClock,
    sink: ConnSink,
    loopEntrant: Entrant,
    counts: ConnCounts,
    first: SinglePath,
): Conn {
    val chosen =
        ConnSlots(
            pacing =
                when (val r = rules.pacing) {
                    is PacingRule.Unpaced -> Pacing.Unpaced
                    is PacingRule.ByQuiche -> Pacing.ByQuiche(Pacer(r))
                },
            ack =
                when (val r = rules.ack) {
                    is AckRule.Immediate -> AckCadence.Immediate
                    is AckRule.UntilPassEnds -> AckCadence.UntilPassEnds(SamePassHold())
                    is AckRule.AcrossTurns -> AckCadence.AcrossTurns(AckHold(r))
                },
            paths =
                when (side) {
                    is ConnSide.Client ->
                        Paths.Client(
                            ClientPaths(
                                q,
                                first,
                                memory.factory,
                                IdIssuer(memory.factory),
                                side.ids,
                                { info, made -> q.freeInfo(info, made) },
                                side.mover,
                            ),
                        )
                    is ConnSide.Server ->
                        Paths.Server(
                            ServerPaths(
                                q,
                                first,
                                address.peerKey,
                                address.localKey,
                                memory.factory,
                                IdIssuer(memory.factory),
                                IdsWanted(),
                                IdsRetired(),
                                { info, made -> q.freeInfo(info, made) },
                            ),
                        )
                },
            resumption =
                when (side) {
                    is ConnSide.Client -> Resumption.Client(ClientSession(q, side.offer, side.scratch, side.kept, side.tokens))
                    is ConnSide.Server -> Resumption.Server(ServerSession(q, rules.earlyData))
                },
            datagrams =
                when (val r = rules.datagrams) {
                    is DatagramRule.Off -> Datagrams.Off
                    is DatagramRule.On -> Datagrams.On(DatagramBox(q, r.bound, memory.factory, counts))
                },
            keepAlive =
                when (val r = rules.keepAlive) {
                    is KeepAliveRule.Off -> KeepAlive.Off
                    is KeepAliveRule.Every -> KeepAlive.Every(KeepAliveTimer(q, r.intervalNanos))
                },
        )
    val parts =
        ConnParts(
            role = side.role(),
            life = ConnLife(q, Linger(q, rules.close, memory.factory, counts)),
            handshake = Handshake(q, rules.handshake, clock.now().value, memory.alpn),
            streams = StreamTable(q, StreamIo(q), side.role(), memory.shared.drains),
            timer = ConnTimer(q, port, WakeSink()),
            ingress = Ingress(q, memory.shared, memory.factory, counts, clock),
            egress = Egress(q, counts, clock),
            release = Release(q),
            network = NetworkWatch(),
            flight = ConnFlight(flight, memory.shared.arena, address.peerKey, capture, clock.now().value),
            inbox = WorkInbox(),
            slots = chosen,
            sink = sink,
            ackReads = AckHoldReads(q),
            sends = socket.ledger,
        )
    return Conn(Turn(parts, clock, port), port, socket, clock, loopEntrant, counts)
}

private fun ConnSide.role(): QuicRole =
    when (this) {
        is ConnSide.Client -> QuicRole.Client
        is ConnSide.Server -> QuicRole.Server
    }
