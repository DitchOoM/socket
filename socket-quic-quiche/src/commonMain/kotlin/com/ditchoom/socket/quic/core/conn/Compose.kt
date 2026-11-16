package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.ConnLife
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.path.SockAddr
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.WorkInbox
import com.ditchoom.socket.quic.core.stream.StreamIo
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture

/** The memory one connection is built with, all from the caller's factory. */
internal class ConnMemory(
    val copies: FixedPool<CopySlot>,
    val factory: BufferFactory,
    /** Where the negotiated ALPN is copied at establishment. */
    val alpn: NativeBytes,
    /** The flight recorder's payload arena. */
    val arena: NativeBytes,
)

/** Where a connection's datagrams go and come from, and how it is recorded. */
internal class ConnAddress(
    val peer: SockAddr,
    val local: SockAddr,
    val peerKey: PathKey,
    val target: SendTarget,
)

/** The only place a variant or a component of a connection is constructed. */
internal fun buildConn(
    rules: SlotRules,
    q: QuicheConnRef,
    role: QuicRole,
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
): Conn {
    val chosen =
        ConnSlots(
            pacing =
                when (val r = rules.pacing) {
                    is PacingRule.Unpaced -> Pacing.Unpaced
                    is PacingRule.ByQuiche -> Pacing.ByQuiche(Pacer(r))
                },
            ack =
                when (rules.ack) {
                    is AckRule.Immediate -> AckCadence.Immediate
                },
            paths = Paths.Single(SinglePath(q, address.peer, address.local, address.target)),
            resumption = Resumption.None,
            datagrams = Datagrams.Off,
        )
    val parts =
        ConnParts(
            life = ConnLife(q),
            handshake = Handshake(q, clock.now().value, memory.alpn),
            streams = StreamTable(q, StreamIo(q), role),
            timer = ConnTimer(q, port, WakeSink()),
            ingress = Ingress(q, memory.copies, memory.factory, counts),
            egress = Egress(q),
            release = Release(q),
            network = NetworkWatch(rules.network),
            flight = ConnFlight(flight, memory.arena, address.peerKey, capture, clock.now().value),
            inbox = WorkInbox(),
            slots = chosen,
            sink = sink,
        )
    return Conn(Turn(parts, clock, port), port, socket, clock, loopEntrant, counts)
}
