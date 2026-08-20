package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.StreamLossCause
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * **A byte the transport accepted and the application never got must never disappear quietly.**
 *
 * Issue #393 could only ever be seen from the far end: an application-level ledger noticing that bytes
 * it wrote never came back. Every release site inside the read path was silent, so a trace from a run
 * that lost data looked exactly like a trace from a run that did not, and the only way to tell them
 * apart was to already know the answer.
 *
 * That is what these pin. `STREAM_LOSS` names the loss at the moment and place it happens, with the
 * stream, the byte count, and a typed cause — so the *next* occurrence is diagnosable from the
 * artifact CI already uploads, instead of costing another device walk.
 *
 * ### Why this suite exists when the fix it accompanies has no test
 *
 * The #414 window (a chunk taken off the queue, then the deadline landing before the coroutine can
 * register its result) is real by construction but was not reproducible in 3000 targeted attempts —
 * kotlinx protects a body that completes without suspending, so what is left is a few instructions
 * wide. A test that cannot force it proves nothing, and the one that tried was deleted for exactly
 * that reason.
 *
 * The instrumentation has no such problem. [DriverStreamAdapter.releaseUndeliveredReads] reaches a
 * loss deterministically — queue chunks, drop the read side — so the recording path is provable even
 * where the race that motivated it is not. If the unreachable window ever does fire, it arrives
 * carrying `QueueClosed`/`SalvageUnclaimed` and stops being a hypothesis.
 */
class StreamLossTraceTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Collects what the driver recorded, so a test asserts on typed events rather than on log text. */
    private class CollectingSink : TraceSink {
        val events = mutableListOf<TraceEvent>()

        override fun emit(event: TraceEvent) {
            events += event
        }

        fun losses(): List<TraceEvent.StreamLoss> = events.filterIsInstance<TraceEvent.StreamLoss>()
    }

    private fun driverRecording(sink: CollectingSink): QuicheDriver =
        QuicheDriver(
            // No path ever moves here: the loss under test is on the read path, and migration is only
            // what makes read timeouts likely in the field, not part of the mechanism.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = StubQuicheApi(),
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel =
                object : UdpChannel {
                    override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

                    override suspend fun send(
                        buffer: PlatformBuffer,
                        len: Int,
                        dest: PathKey?,
                    ): SendOutcome = SendOutcome.Sent

                    override fun close() {}
                },
            clientMode = false,
            isServer = false,
            recorder = QuicTraceRecorder(sink),
        )

    /** Queue [size] readable bytes for [slot], the way the driver's teardown drain does. */
    private fun queueChunk(
        slot: StreamSlot,
        size: Int,
    ) {
        val buffer = bufferFactory.allocate(size)
        repeat(size) { buffer.writeByte(0x41) }
        buffer.resetForRead()
        assertTrue(slot.pendingData.trySend(buffer).isSuccess, "the test could not seed the queue")
    }

    @Test
    fun releaseUndeliveredReadsNamesEveryByteTheApplicationNeverGot() {
        val sink = CollectingSink()
        val slot = StreamSlot(QuicStreamId(4L))
        val adapter = DriverStreamAdapter(driverRecording(sink), slot)

        queueChunk(slot, 128)
        queueChunk(slot, 64)

        // The read side goes away for good — close()/reset(). Releasing here is CORRECT; the queued
        // chunks can no longer be handed out and holding them would leak. They are still 192 bytes
        // quiche accepted that the application never saw, and that is the fact a short stream needs.
        adapter.releaseUndeliveredReads()

        val losses = sink.losses()
        assertEquals(2, losses.size, "expected one STREAM_LOSS per undelivered chunk, got $losses")
        assertEquals(listOf(128, 64), losses.map { it.bytes }, "byte counts must survive into the trace")
        assertTrue(losses.all { it.streamId == 4L }, "every loss must name its stream: $losses")
        assertTrue(losses.all { it.cause == "ReaderGone" }, "wrong cause token: $losses")
    }

    @Test
    fun aStreamThatLosesNothingRecordsNothing() {
        val sink = CollectingSink()
        val slot = StreamSlot(QuicStreamId(8L))
        val adapter = DriverStreamAdapter(driverRecording(sink), slot)

        // The negative control. Without it, a recorder that emitted on every call would pass the test
        // above and turn every healthy stream into a false alarm — which is worse than silence,
        // because the next real loss would be indistinguishable from the noise.
        adapter.releaseUndeliveredReads()

        assertEquals(emptyList(), sink.losses(), "an empty queue is not a loss")
    }

    @Test
    fun everyStreamLossCauseRoundTripsThroughTheV1Format() {
        // The trace is only useful if it survives being written and read back — the fixtures, the
        // deobfuscator and the failure dumps all go through this boundary.
        val causes = listOf(StreamLossCause.ReaderGone, StreamLossCause.QueueClosed, StreamLossCause.SalvageUnclaimed)
        val sink = CollectingSink()
        val recorder = QuicTraceRecorder(sink)
        causes.forEachIndexed { i, cause -> recorder.streamLoss(streamId = i.toLong(), bytes = 100 + i, cause = cause) }

        val recorded = sink.losses()
        assertEquals(causes.size, recorded.size, "one line per cause")
        recorded.forEach { event ->
            assertEquals(event, TraceEvent.parse(event.toString()), "parse(emit(e)) != e for $event")
        }
        assertEquals(
            listOf("ReaderGone", "QueueClosed", "SalvageUnclaimed"),
            recorded.map { it.cause },
            "the v1 tokens are frozen — changing one invalidates every recorded trace",
        )
    }

    @Test
    fun aStreamLossIsAnObservationNotAReplayableInput() {
        // Replay drives the transport from the far side. Feeding a STREAM_LOSS back in would replay
        // this endpoint's own reaction rather than the input that caused it, so it must stay out of
        // the input subset the fixture codegen consumes.
        assertTrue(!TraceEvent.StreamLoss(1.nanoseconds, 4L, 128, "ReaderGone").isInput)
    }
}
