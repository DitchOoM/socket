package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.loop.Endpoint
import com.ditchoom.socket.quic.core.loop.Readiness
import com.ditchoom.socket.quic.core.loop.Route
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.sched.CallerLanes
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.RecordingSink
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.loopOver
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.testLoopMemory
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.BatchCapability
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketClosed
import com.ditchoom.socket.quic.core.udp.SocketProbe
import kotlinx.coroutines.Runnable
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/** A socket whose kernel buffer holds [kernelBuffer] datagrams: one that arrives at a full buffer is lost and counted. */
internal class BoundedIo(
    private val kernelBuffer: Int,
) : DatagramBatchIo {
    class Arrival(
        val bytes: ByteArray,
        val from: PathKey,
    )

    class Sent(
        val bytes: ByteArray,
        val target: SendTarget,
    )

    override val capability: BatchCapability = BatchCapability.OneByOne
    private val waiting = ArrayDeque<Arrival>()
    val sent = ArrayList<Sent>()

    /** What happened to the socket, in order: each datagram sent, and its close. */
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    var kernelDrops = 0
        private set

    fun arrive(
        bytes: ByteArray,
        from: PathKey,
    ) {
        if (waiting.size >= kernelBuffer) kernelDrops++ else waiting.addLast(Arrival(bytes, from))
    }

    override fun send(batch: SendBatch): SendBatchResult {
        for (n in 0 until batch.pending) {
            val storage = batch.storage.owner
            storage.setLimit(batch.offsetOf(n) + batch.lengthOf(n))
            storage.position(batch.offsetOf(n))
            sent.add(Sent(storage.readByteArray(batch.lengthOf(n)), batch.targetOf(n)))
            events.add(if (events.contains(CLOSED)) "$SENT after close" else SENT)
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
        var got = 0
        while (waiting.isNotEmpty()) {
            val buffer = into.take()
            if (buffer !is ReceiveBuffer) break
            val arrival = waiting.removeFirst()
            val memory = buffer.bytes.owner
            memory.resetForWrite()
            memory.writeBytes(arrival.bytes)
            into.received(buffer, arrival.bytes.size, arrival.from, ArrivedOn.Unreported)
            got++
        }
        return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
    }

    override fun probe(): SocketProbe = SocketProbe.Open

    override fun reopen(): Reopened = Reopened.SameEndpoint

    override fun close(): SocketClosed {
        events.add(CLOSED)
        return SocketClosed
    }

    companion object {
        const val SENT = "sent"
        const val CLOSED = "closed"
    }
}

/**
 * A server's demux on a loop with no thread, over [api], a bounded fake socket and virtual time: what a serve builds,
 * with the edge replaced by a recording sink per accepted connection.
 */
internal class ServerRig(
    val api: QuicheApi = StubQuicheApi(),
    bounds: AdmissionBounds = AdmissionBounds.Default,
    backlog: Int = 64,
    kernelBuffer: Int = Int.MAX_VALUE,
    val tokens: Tokens = Tokens.None,
    /** Lanes for threads other than the loop: with none, no caller ever enters a connection. */
    lanes: Int = 0,
) {
    val clock = VirtualClock()
    val io = BoundedIo(kernelBuffer)
    val loop = loopOver(FakePoller(), clock)
    val counts = ServerCounts()
    val sinks = ArrayList<RecordingSink>()

    /** Every connection accepted, in order. */
    val conns = ArrayList<Conn>()

    /** Who was accepted, in order: each one's id is what a short header names. */
    val accepted = ArrayList<AcceptedPeer>()
    val socket =
        SendPort(
            io,
            loop.port,
            if (lanes == 0) {
                CallerLanes.None
            } else {
                CallerLanes.Of(FixedPool(lanes) { CallerSend(it, SendBatch(nativeBytes(8 * 1500), BatchLimits(8, 1500), it + 1)) })
            },
            RecordingWork(),
            loop.counts,
            loop.callerCounts,
        )
    private val local = sockAddr()
    private val parts =
        ServerParts(
            SlotRules.Default,
            BufferFactory.Default,
            FlightSize(records = 64),
            testLoopMemory(arenaBytes = 8 * 1024),
            loop.port,
            socket,
            clock,
            Entrant.loopTurn(loop.id, 1),
            QuicheConfig(1L),
            local,
            LOCAL,
        )
    private val hooks =
        ServerHooks(
            peerAddress = { PeerSockAddr.Encoded(sockAddr()) },
            open = { peer, build ->
                accepted += peer
                val sink = RecordingSink()
                sinks += sink
                Opened(build.build(sink, com.ditchoom.socket.quic.trace.TraceCapture.Off).also { conns += it }, Runnable { })
            },
        )
    val admission =
        Admission(
            api,
            bounds,
            InitialBacklog(List(backlog) { nativeBytes(1500) }),
            parts,
            hooks,
            MemoryHold(Runnable { }),
            kotlin.random.Random(1),
            counts,
        )
    val demux =
        Demux(
            api,
            admission,
            Stateless(api, tokens, kotlin.random.Random(2), LOCAL, counts),
            DemuxScratch(nativeBytes(DemuxScratch.FIELDS_BYTES), nativeBytes(256), nativeBytes(256), nativeBytes(16)),
            loop.port,
            clock,
            counts,
        )
    val endpoint =
        Endpoint(
            SocketRef(DatagramChannel.open()),
            io,
            socket,
            SendBatch(nativeBytes(64 * 1500), BatchLimits(64, 1500)),
            Route.Demux(demux),
            Runnable { },
            Readiness.EveryPass,
        )

    init {
        loop.attach(endpoint)
    }

    fun pass(at: Long = clock.at.value) {
        clock.at = Nanos(at)
        loop.pass(Nanos(at))
    }

    private fun sockAddr(): EncodedSockAddr {
        val bytes = nativeBytes(16)
        return EncodedSockAddr(bytes.owner, bytes.address, 16)
    }

    companion object {
        val LOCAL = PathKey(family = 4, port = 443, hi = 0L, lo = 0x7F000001L)

        fun peer(n: Int) = PathKey(family = 4, port = 1000 + n, hi = 0L, lo = 0x7F000001L)

        /** A client Initial of [size] bytes naming [version], whose destination id is [dcid]: a header quiche parses. */
        fun initial(
            dcid: ByteArray,
            version: Int = 1,
            size: Int = 1_200,
        ): ByteArray {
            val out = ByteBuffer.allocate(size)
            out.put(0xC0.toByte()).putInt(version)
            out.put(dcid.size.toByte()).put(dcid)
            out.put(8.toByte()).put(ByteArray(8) { 7 })
            out.put(0.toByte())
            val payload = size - out.position() - 2
            out.put((0x40 or (payload shr 8)).toByte()).put(payload.toByte())
            return out.array()
        }

        /** A short-header datagram addressed to the 16-byte id whose halves are [c0] and [c1]. */
        fun short(
            c0: Long,
            c1: Long,
        ): ByteArray =
            ByteBuffer
                .allocate(40)
                .put(0x40.toByte())
                .putLong(c0)
                .putLong(c1)
                .array()
    }
}
