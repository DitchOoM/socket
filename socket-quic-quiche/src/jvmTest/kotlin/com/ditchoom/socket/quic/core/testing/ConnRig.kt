package com.ditchoom.socket.quic.core.testing

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.ClientSession
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSide
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.conn.EarlyReport
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.KeptTicket
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.MismatchResponse
import com.ditchoom.socket.quic.core.conn.Offer
import com.ditchoom.socket.quic.core.conn.Paths
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.TokenHome
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.conn.freeInfo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.ClientPaths
import com.ditchoom.socket.quic.core.path.IdIssuer
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable
import java.nio.channels.DatagramChannel
import kotlin.random.Random

/** What a connection told its sink. */
internal class RecordingSink : ConnSink {
    val established = ArrayList<ConnectReport>()
    val incoming = ArrayList<Long>()
    val ended = ArrayList<ConnEnd>()

    override fun established(report: ConnectReport) {
        established += report
    }

    override fun earlyData(report: EarlyReport) = Unit

    override fun incoming(
        stream: QuicStreamId,
        arrival: Arrival,
    ) {
        incoming += stream.id
    }

    override fun ticket(
        bytes: NativeBytes,
        length: Int,
    ) = Unit

    override fun ended(end: ConnEnd) {
        ended += end
    }
}

/** A loop of its own over [poller] and [clock], with no thread: tests call [Loop.pass]. */
internal fun loopOver(
    poller: Poller,
    clock: LoopClock,
    maxDatagrams: Int = 64,
): Loop =
    Loop(
        id = 1,
        name = "test-loop",
        poller = poller,
        clock = clock,
        receive =
            ReceiveBatch(
                FixedPool(maxDatagrams) { ReceiveBuffer(it, nativeBytes(1500)) },
                BatchLimits(maxDatagrams = maxDatagrams, maxDatagramSize = 1500),
            ),
        freeOnStop = Runnable { },
    )

/** A loop's shared memory for a test: [copies] copy slots and an arena of [arenaBytes]. */
internal fun testLoopMemory(
    copies: Int = 4,
    arenaBytes: Int = 64 * 1024,
): LoopMemory =
    LoopMemory(
        List(copies) { nativeBytes(1500) },
        List(LoopMemory.DRAIN_SLOTS) { nativeBytes(LoopMemory.DRAIN_BYTES) },
        FlightArena(nativeBytes(arenaBytes)),
    )

/** A connection built the way a connect builds one, on [loop], sending through [socket]. */
internal fun testConn(
    api: QuicheApi,
    rules: SlotRules,
    target: SendTarget,
    capture: TraceCapture,
    loop: Loop,
    socket: SendPort,
    clock: LoopClock,
    sink: ConnSink,
    counts: ConnCounts,
): Conn =
    buildConn(
        rules = rules,
        q = QuicheConnRef(api, QuicheConn(1L)),
        side = ConnSide.Client(Random(1), Offer.NoTicket, nativeBytes(ClientSession.TICKET_BYTES), KeptTicket.None, TokenHome.NotKept),
        address =
            ConnAddress(
                SockAddr(0L, 0),
                SockAddr(0L, 0),
                PathKey(4, 443, 0L, 0x7F000001L),
                PathKey(4, 50_000, 0L, 0x7F000001L),
                target,
            ),
        memory = ConnMemory(testLoopMemory(), BufferFactory.Default, nativeBytes(255)),
        flight = FlightSize(records = 512),
        capture = capture,
        port = loop.port,
        socket = socket,
        clock = clock,
        sink = sink,
        loopEntrant = Entrant.loopTurn(loop.id, 1),
        counts = counts,
    )

/** The rules every core test rig builds its connections with: the production defaults, except that a pacing clock mismatch fails. */
internal val CoreTestRules: SlotRules =
    SlotRules.forWaits(
        WaitResolution(1_000_000L),
        SlotRules.Default.handshake,
        SlotRules.Default.close,
        mismatch = MismatchResponse.Fail,
    )

/**
 * One connection built the way a connect builds one, over a scripted quiche, a fake socket and a virtual clock, on a
 * loop with no thread. [pass] runs one loop pass at the clock's time.
 */
internal class ConnRig(
    val api: QuicheApi = StubQuicheApi(),
    rules: SlotRules = CoreTestRules,
    val io: DatagramBatchIo = FakeIo(),
    target: SendTarget = SendTarget.ConnectedPeer,
    maxDatagrams: Int = 64,
    capture: TraceCapture = TraceCapture.Off,
    val clock: VirtualClock = VirtualClock(),
    poller: Poller = FakePoller(),
) {
    val sink = RecordingSink()
    val counts = ConnCounts()
    val loop = loopOver(poller, clock, maxDatagrams)
    val socket = SendPort(io, loop.port, CallerLanes.None, RecordingWork(), loop.counts, loop.callerCounts)
    val conn: Conn = testConn(api, rules, target, capture, loop, socket, clock, sink, counts)
    val endpoint =
        Endpoint(
            SocketRef(DatagramChannel.open()),
            io,
            socket,
            SendBatch(nativeBytes(maxDatagrams * 1500), BatchLimits(maxDatagrams, 1500)),
            Route.Fixed(conn),
            Runnable { },
        )

    init {
        loop.attach(endpoint)
        conn.start()
    }

    /** One pass at [at] nanoseconds. */
    fun pass(at: Long = clock.at.value): NextDue {
        clock.at = Nanos(at)
        return loop.pass(Nanos(at))
    }
}

/** A client's paths over [q], as a connect builds them, with every address zero. */
internal fun stubClientPaths(
    q: QuicheConnRef,
    target: SendTarget = SendTarget.ConnectedPeer,
): Paths =
    Paths.Client(
        ClientPaths(
            q,
            SinglePath(q, SockAddr(0L, 0), SockAddr(0L, 0), target),
            BufferFactory.Default,
            IdIssuer(BufferFactory.Default),
            Random(1),
            { info, made -> q.freeInfo(info, made) },
        ),
    )
