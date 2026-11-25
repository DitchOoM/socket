package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicConnection
import com.ditchoom.socket.quic.QuicStream
import com.ditchoom.socket.quic.QuicStreamReceiver
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.StreamReceive
import com.ditchoom.socket.quic.StreamSend
import com.ditchoom.socket.quic.StreamWrite
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.JDK_BOXED_HANDLES_PER_OP
import com.ditchoom.socket.quic.core.testing.loopGateOfThisStack
import com.ditchoom.socket.quic.core.testing.withDescriptorsAbove127
import com.ditchoom.socket.quic.getOrElse
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * What the tier-2 shape costs: a [QuicStreamReceiver] registered once per stream, called on the connection's handler
 * lane with the stream's lent buffer, replying with the non-suspending [com.ditchoom.socket.quic.QuicSendStream.trySend].
 * Bytes per operation on the client's caller, handler lane and loop threads (exact per-thread counters), gated a little
 * over their measurement; and a receiver that blocks holding back only its own stream's peer.
 */
class ReceiverAllocationTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun anEchoDrivenFromTheReceiverAllocatesNothingOnTheLaneOrTheLoop() =
        measured { port ->
            val connection = connect(port)
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            val pingPong = PingPong(stream, ECHO_WARM_UP + ECHOES)
            assertSame(StreamReceive.Receiving, stream.receive.receive(pingPong))
            pingPong.start()
            pingPong.reached(ECHO_WARM_UP).get(WAIT_SECONDS, TimeUnit.SECONDS)
            val from = Counters(callerThread = Thread.currentThread().id)
            pingPong.reached(ECHO_WARM_UP + ECHOES).get(WAIT_SECONDS, TimeUnit.SECONDS)
            val row = from.per(ECHOES.toLong())
            report("echo 64 B, replies by trySend from the receiver", row)
            stream.send.tryFinish()
            row.gate(ECHO_LANE_GATE, ECHO_LOOP_GATE, CALLER_GATE)
        }

    @Test
    fun aDownloadIntoAReceiverAllocatesNothingPerRead() =
        measured { port ->
            val connection = connect(port)
            val stream = connection.openStream().getOrElse { fail("not opened: $it") }
            val count = Counting(WARM_READS)
            assertSame(StreamReceive.Receiving, stream.receive.receive(count))
            if (stream.send.writeAndFinish(byteOf(DOWNLOAD)) !== StreamWrite.Written) fail("the request was not written")
            withTimeout(WAIT_SECONDS.seconds) { count.end.await() }
            assertTrue(count.bytes == CHUNK.toLong() * CHUNKS, "every byte arrived: ${count.bytes}")
            val row = count.from.per(count.calls - WARM_READS)
            report("16 MiB one way into a receiver, per call", row)
            row.gate(DOWNLOAD_LANE_GATE, DOWNLOAD_LOOP_GATE, CALLER_GATE)
        }

    @Test
    fun aNewStreamPerEchoWithAReceiverCostsItsOpenAndLittleElse() =
        measured { port ->
            val connection = connect(port)
            val payload = payloadOf(ECHO)

            suspend fun one() {
                val stream = connection.openStream().getOrElse { fail("not opened: $it") }
                val done = Ended()
                stream.receive.receive(done)
                payload.position(0)
                stream.send.trySend(payload)
                stream.send.tryFinish()
                done.end.await()
            }
            repeat(NEW_WARM_UP) { one() }
            val from = Counters(callerThread = Thread.currentThread().id)
            repeat(NEW_STREAMS) { one() }
            val row = from.per(NEW_STREAMS.toLong())
            report("new stream per echo, a receiver per stream", row)
            row.gate(NEW_LANE_GATE, NEW_LOOP_GATE, NEW_CALLER_GATE)
        }

    // ---- the receivers ----------------------------------------------------------------------------------------------

    /** Sends a 64 B ping on start and again each time its echo arrived, from the lane, until [total] echoes. */
    private class PingPong(
        private val stream: QuicStream,
        private val total: Int,
    ) : QuicStreamReceiver {
        private val payload = payloadOf(ECHO)
        private var got = 0
        private val echoes = AtomicLong()
        private val warm = CompletableFuture<Unit>()
        private val all = CompletableFuture<Unit>()

        /** Completes once [echoes] went by: the warm-up's count, or the total. */
        fun reached(echoes: Int): CompletableFuture<Unit> = if (echoes == total) all else warm

        fun start() = ping()

        private fun ping() {
            payload.position(0)
            if (stream.send.trySend(payload) !== StreamSend.Sent) fail("a ping did not fit")
        }

        override fun onBytes(bytes: ReadBuffer) {
            got += bytes.remaining()
            bytes.position(bytes.limit())
            while (got >= ECHO) {
                got -= ECHO
                val n = echoes.incrementAndGet().toInt()
                if (n == ECHO_WARM_UP) warm.complete(Unit)
                if (n == total) all.complete(Unit)
                if (n < total) ping()
            }
        }

        override fun onEnd(end: ScopedRead.End) = Unit
    }

    /** Counts what arrives; takes the counters once [warm] calls went by. */
    private class Counting(
        private val warm: Long,
    ) : QuicStreamReceiver {
        var calls = 0L
        var bytes = 0L
        lateinit var from: Counters
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            calls++
            this.bytes += bytes.remaining()
            bytes.position(bytes.limit())
            if (calls == warm) from = Counters(callerThread = NO_THREAD)
        }

        override fun onEnd(end: ScopedRead.End) {
            this.end.complete(end)
        }
    }

    /** Waits for its stream's end. */
    private class Ended : QuicStreamReceiver {
        val end = CompletableDeferred<ScopedRead.End>()

        override fun onBytes(bytes: ReadBuffer) {
            bytes.position(bytes.limit())
        }

        override fun onEnd(end: ScopedRead.End) {
            this.end.complete(end)
        }
    }

    // ---- harness ----------------------------------------------------------------------------------------------------

    private fun measured(body: suspend (Int) -> Unit) =
        runBlocking {
            withDescriptorsAbove127 {
                skipOnMissingNativeLib(ReceiverAllocationTests::class) {
                    withCoreServer(onConnection = { serveRawStreams(it) }) { server -> body(server.port) }
                }
            }
        }

    private suspend fun connect(port: Int): QuicConnection =
        connectCore(port, loops = loops, factory = BufferFactory.network()).connection.public()

    /** A stream whose first read is the single byte [DOWNLOAD] gets [CHUNKS] KiB back; any other is echoed until its FIN. */
    private suspend fun serveRawStreams(connection: CoreConnection) {
        val chunk = payloadOf(CHUNK)
        connection.handleStreams { stream ->
            when (val first = stream.read(10.seconds)) {
                is ReadResult.Data -> {
                    val buffer = first.buffer
                    if (buffer.remaining() == 1 && buffer.get(buffer.position()) == DOWNLOAD) {
                        buffer.freeIfNeeded()
                        repeat(CHUNKS) {
                            chunk.position(0)
                            stream.writeFully(chunk, 10.seconds)
                        }
                    } else {
                        stream.writeFully(buffer, 10.seconds)
                        buffer.freeIfNeeded()
                        while (true) {
                            when (val next = stream.read(10.seconds)) {
                                is ReadResult.Data -> {
                                    stream.writeFully(next.buffer, 10.seconds)
                                    next.buffer.freeIfNeeded()
                                }
                                else -> break
                            }
                        }
                    }
                }
                else -> Unit
            }
            stream.close()
        }
    }

    private class Row(
        val caller: Double,
        val lane: Double,
        val loop: Double,
    ) {
        fun gate(
            laneGate: Double,
            loopGate: Double,
            callerGate: Double,
        ) {
            assertTrue(
                lane <= laneGate && loop <= stackLoopGate(loopGate) && caller <= callerGate,
                "caller %.1f B/op (gate %.0f), lane %.1f (gate %.0f), loop %.1f (gate %.0f)".format(
                    caller,
                    callerGate,
                    lane,
                    laneGate,
                    loop,
                    stackLoopGate(loopGate),
                ),
            )
        }
    }

    /** The client's loop, handler-lane and caller threads' allocation counters from when it is made. */
    private class Counters(
        private val callerThread: Long,
    ) {
        private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val loop = threadsNamed(CLIENT_LOOP).filterNot { it in threadsNamed(CLIENT_LANE) }
        private val lanes = threadsNamed(CLIENT_LANE)
        private val loopFrom = bytes(loop)
        private val lanesFrom = bytes(lanes)
        private val callerFrom = if (callerThread == NO_THREAD) 0L else mx.getThreadAllocatedBytes(callerThread)

        private fun bytes(ids: List<Long>) = ids.sumOf { mx.getThreadAllocatedBytes(it) }

        fun per(ops: Long): Row {
            val n = ops.toDouble()
            val caller = if (callerThread == NO_THREAD) 0.0 else (mx.getThreadAllocatedBytes(callerThread) - callerFrom) / n
            return Row(caller, (bytes(lanes) - lanesFrom) / n, (bytes(loop) - loopFrom) / n)
        }
    }

    private fun report(
        what: String,
        row: Row,
    ) = println("[receiver allocation] %s: caller %.1f B/op, lane %.1f B/op, loop %.1f B/op".format(what, row.caller, row.lane, row.loop))

    private companion object {
        const val CLIENT_LOOP = "quic-core-client"
        const val CLIENT_LANE = "quic-core-client-handler"
        const val NO_THREAD = -1L
        const val DOWNLOAD: Byte = 'D'.code.toByte()
        const val ECHO = 64
        const val CHUNK = 1024
        const val CHUNKS = 16_384
        const val ECHO_WARM_UP = 2_000
        const val ECHOES = 5_000
        const val WARM_READS = 1_000L
        const val NEW_WARM_UP = 500
        const val NEW_STREAMS = 2_000
        const val WAIT_SECONDS = 120L

        // Gates, a little over what JDK 21 on macOS measured: 0 B on every thread for the echo and the download; per new
        // stream 1,611 B on the caller (its open of the stream most of it), 75 B on the lane and 40 B on the loop.
        const val CALLER_GATE = 8.0
        const val ECHO_LANE_GATE = 8.0
        const val ECHO_LOOP_GATE = 8.0
        const val DOWNLOAD_LANE_GATE = 8.0
        const val DOWNLOAD_LOOP_GATE = 8.0
        const val NEW_CALLER_GATE = 1_700.0
        const val NEW_LANE_GATE = 80.0
        const val NEW_LOOP_GATE = 44.0

        fun threadsNamed(prefix: String): List<Long> =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name.startsWith(prefix) }
                .map { it.id }

        fun payloadOf(size: Int) =
            BufferFactory.network().allocate(size).apply {
                repeat(size) { writeByte(it.toByte()) }
                resetForRead()
            }

        fun byteOf(value: Byte) =
            BufferFactory.network().allocate(1).apply {
                writeByte(value)
                resetForRead()
            }
    }
}

/** [native] on the native stack; on the JDK stack (Windows) it also allows the selector's boxed handles. */
private fun stackLoopGate(native: Double): Double = loopGateOfThisStack(native, native + JDK_BOXED_HANDLES_PER_OP)
