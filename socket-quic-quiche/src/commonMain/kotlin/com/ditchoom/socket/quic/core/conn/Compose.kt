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
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.recordOr
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
     * back if it never sent; its server's NEW_TOKEN token goes to [tokens]; its socket is moved by [mover]; a handshake
     * the system refused every send of is judged by [access]; its server's certificates are copied at completion as
     * [chain] says.
     */
    class Client(
        val ids: Random,
        val offer: Offer,
        val scratch: FixedPool<SessionScratch>,
        val kept: KeptTicket,
        val tokens: TokenHome,
        val mover: (PathKey, RttPrior) -> Unit,
        val access: SendsAccess,
        val chain: ChainSource,
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
    time: QuicheTime,
    sink: ConnSink,
    loopEntrant: Entrant,
    counts: ConnCounts,
): Conn =
    q.composedAround(SinglePath(q, address.peer, address.local, address.target)) { first ->
        composed(
            rules,
            q,
            side,
            address,
            memory,
            flight,
            capture,
            port,
            socket,
            clock,
            TurnClock(TurnTime.of(time, clock), clock),
            sink,
            loopEntrant,
            counts,
            first,
        )
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
    turnClock: TurnClock,
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
                    is PacingRule.ByQuiche ->
                        when (turnClock.base()) {
                            is ClockBase.Libquiche -> Pacing.ByQuiche(Pacer(r, turnClock.firstVerdict()))
                            is ClockBase.Own -> Pacing.Unpaced
                        }
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
                    is ConnSide.Client -> Resumption.Client(ClientSession(q, side.offer, side.scratch, side.kept, side.tokens, side.chain))
                    is ConnSide.Server -> Resumption.Server(ServerSession(q, rules.earlyData))
                },
            datagrams =
                when (val r = rules.datagrams) {
                    is DatagramRule.Off -> Datagrams.Off
                    is DatagramRule.On ->
                        Datagrams.On(
                            DatagramBox(q, r.bound, r.incoming, memory.factory, counts),
                            r.sender(q, memory.factory, clock, counts),
                        )
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
            life = ConnLife(q, Linger(memory.factory, counts), rules.close),
            handshake = Handshake(q, rules.handshake, clock.now().value, memory.alpn, side.chainSource()),
            streams = StreamTable(q, StreamIo(q), side.role(), memory.shared.drains),
            timer = ConnTimer(q, port, WakeSink()),
            ingress = Ingress(q, memory.shared, memory.factory, counts, turnClock),
            egress = Egress(q, counts, turnClock),
            release = Release(q),
            network = NetworkWatch(),
            flight = ConnFlight(flight, memory.shared.arena, address.peerKey, capture, flightOrigin(capture, clock.now())),
            inbox = WorkInbox(),
            slots = chosen,
            sink = sink,
            ackReads = AckHoldReads(q),
            sends = socket.ledger,
            access =
                when (side) {
                    is ConnSide.Client -> side.access
                    is ConnSide.Server -> SendsAccess.NeverDenied
                },
            endHold = EndHold(StreamIo(q), memory.factory),
        )
    return Conn(Turn(parts, turnClock, port), port, socket, turnClock, loopEntrant, counts)
}

/**
 * What the connection's flight recording counts from, read at [now] on the loop's clock: the trace recorder's origin when
 * a trace is on, so the datagrams it records and the states the recorder stamps share one timeline; [now] when it is off.
 */
private fun flightOrigin(
    capture: TraceCapture,
    now: Nanos,
): Long = capture.recordOr(now.value) { now.value - it.now().inWholeNanoseconds }

private fun ConnSide.chainSource(): ChainSource =
    when (this) {
        is ConnSide.Client -> chain
        is ConnSide.Server -> ChainSource.NotAsked
    }

private fun ConnSide.role(): QuicRole =
    when (this) {
        is ConnSide.Client -> QuicRole.Client
        is ConnSide.Server -> QuicRole.Server
    }
