package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **Bytes quiche delivered into a `read()` that has already timed out must not die with it.**
 *
 * `commands` is UNLIMITED, so `commands.send(StreamRecv)` never suspends: by the time a read's
 * `withTimeout` deadline (or an external cancel) can unwind the caller, the command is already queued
 * and the driver will process it regardless. [DriverStreamAdapter.streamRead]'s `finally` correctly
 * joins that in-flight command non-cancellably — that join is the use-after-free guard and is not what
 * these tests are about — but it then released the buffer with `transferred == false`, even when the
 * driver had *already* completed the deferred with `StreamRecvResult.Data`. quiche has by then advanced
 * the stream's receive offset and credited flow control: those bytes are unrecoverable, and the peer
 * will never resend them. A FIN riding on the same chunk was lost the same way — `slot.finReceived` is
 * set inside the `when` the cancellation skipped — after which the stream can never report a clean end
 * and every later `read()` parks on a `dataSignal` nothing will ever tickle again.
 *
 * This is issue #318's defect class on the other edge. #318 was the *teardown* edge (quiche still held
 * bytes when the connection died) and was fixed by draining them into [StreamSlot.pendingData]; this is
 * the *cancellation* edge, and the fix is the symmetric one — the salvaged chunk goes into the same
 * queue, so the same delivery ordering and the same [DriverStreamAdapter.releaseUndeliveredReads]
 * release path cover it.
 *
 * ### Field evidence (issue #393)
 *
 * A 124-minute on-device Android handoff run lost its stream twice while the connection stayed healthy.
 * Correlating every migration in that run against the read timeouts before it:
 *
 * | migration  | preceded by a read timeout | stream after |
 * |------------|----------------------------|--------------|
 * | t=460794ms | no                         | healthy      |
 * | t=545957ms | no                         | healthy      |
 * | t=1050416ms| yes, 8556ms before         | **dead**     |
 * | t=1204344ms| yes, 5952ms before         | **dead**     |
 * | t=7453333ms| no                         | healthy      |
 *
 * The connection then lived 101 more minutes with that dead stream (30s idle timeout, 5s keepalive, so
 * packets were flowing both ways the whole time) — only the stream was gone. iOS saw exactly one read
 * timeout in a comparable run and never lost a stream. A timeout is the trigger; a migration is only
 * what makes timeouts likely.
 *
 * ### How the race is pinned
 *
 * Same device as `ReactiveDriverTests`' buffer-lifetime regressions: the driver is parked in its startup
 * flush behind a gated UDP send, so a `StreamRecv` enqueued after `start()` is *guaranteed* still
 * unprocessed when the read's own deadline fires. Releasing the gate afterwards makes the driver deliver
 * into a read that has already unwound — the exact interleaving, with no scheduler luck involved.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, so a `commonTest` home
 * covered every platform *except* the one that ships this backend to users: Android is the only target
 * that runs quiche over JNI, and it is where issue #393 was found in the field. This directory is
 * `srcDir`'d into both source sets, so the same source runs unchanged on jvm/apple/linux **and** on a
 * real device — the move adds the lane that was missing and takes none away. See DitchOoM/socket#390.
 */
class StreamReadCancellationTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** The read deadline every timed-out read below uses, and the window we wait past it. */
    private val readDeadline = 150.milliseconds
    private val pastDeadline = 600L

    /**
     * A driver pinned in its startup flush until [udpGate] completes.
     *
     * `connSendOnce` makes the startup `afterCommand()` emit exactly one datagram and the UDP channel's
     * `send` parks on the gate, so the loop is stuck *before* it can dequeue any stream command. Copied
     * from `ReactiveDriverTests` rather than shared because that one is private to its class, the same
     * way `createTestDriver` is already duplicated across this module's driver suites.
     */
    private fun gatedStartupDriver(
        api: StubQuicheApi,
        udpGate: CompletableDeferred<Unit>,
        recorder: QuicTraceRecorder? = null,
    ): QuicheDriver {
        api.connSendOnce = 1300
        val gatedUdp =
            object : UdpChannel {
                override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

                override suspend fun send(
                    buffer: PlatformBuffer,
                    len: Int,
                    dest: PathKey?,
                ): SendOutcome {
                    udpGate.await()
                    return SendOutcome.Sent
                }

                override fun close() {}
            }
        return QuicheDriver(
            // Test double: these tests never move a path — the migration in the field report is only
            // what made read timeouts likely, not part of the mechanism under test.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = gatedUdp,
            clientMode = false,
            isServer = false,
            recorder = recorder,
        )
    }

    /** What a read produced, in one line, so a red run names the actual verdict instead of "was null". */
    private fun Result<ReadResult>.describe(): String =
        fold(
            onSuccess = {
                when (it) {
                    is ReadResult.Data -> "Data(${it.buffer.remaining()} bytes)"
                    else -> it::class.simpleName ?: "$it"
                }
            },
            onFailure = { "threw ${it::class.simpleName} (the read parked and hit its own deadline)" },
        )

    private suspend fun DriverStreamAdapter.readCatching(
        slot: StreamSlot,
        timeout: Duration,
    ): Result<ReadResult> = runCatching { streamRead(slot.id, bufferFactory, BUF, timeout) }

    /**
     * Drive one timed-out read that the driver answers with [delivered] only after the deadline has
     * passed, and hand back the adapter + slot so each test can assert on what the *next* read sees.
     */
    private suspend fun CoroutineScope.afterATimedOutReadAnsweredWith(
        delivered: StreamRecvResult,
        api: StubQuicheApi,
        udpGate: CompletableDeferred<Unit>,
        driver: QuicheDriver,
    ): Pair<DriverStreamAdapter, StreamSlot> {
        api.streamRecvSequence.addLast(delivered)
        api.streamRecvResult = StreamRecvResult.Done
        driver.start(this)
        val slot = StreamSlot(QuicStreamId(0L))
        val adapter = DriverStreamAdapter(driver, slot)

        // The StreamRecv is queued against a driver that cannot process it; the read's own deadline
        // fires while it is still in flight and the read parks in the non-cancellable join.
        val timedOut = async { adapter.readCatching(slot, readDeadline) }
        assertNull(
            withTimeoutOrNull(pastDeadline) { timedOut.await() },
            "the read unwound while its StreamRecv was still in-flight — the use-after-free guard this " +
                "test builds on is gone, so the interleaving below proves nothing",
        )

        // Now the driver delivers into a read that has already given up.
        udpGate.complete(Unit)
        val outcome = withTimeout(2.seconds) { timedOut.await() }
        assertIs<TimeoutCancellationException>(
            outcome.exceptionOrNull(),
            "the read was supposed to have timed out before the driver answered; it returned ${outcome.describe()}",
        )
        return adapter to slot
    }

    /**
     * **The regression.** A data chunk quiche handed to a read that had already timed out was freed with
     * the read's buffer. quiche had advanced the receive offset and credited flow control, so nothing
     * re-delivers it: the next `read()` sees a hole in the stream forever.
     */
    @Test
    fun bytesDeliveredIntoATimedOutReadSurviveForTheNextRead() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            try {
                val (adapter, slot) =
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Data(bytesRead = CHUNK, fin = false),
                        api,
                        udpGate,
                        driver,
                    )

                val next = adapter.readCatching(slot, 1.seconds)
                val data =
                    assertIs<ReadResult.Data>(
                        next.getOrNull(),
                        "the $CHUNK bytes quiche delivered into the timed-out read were freed with its buffer — " +
                            "quiche has already advanced the receive offset, so they are gone for good; " +
                            "the follow-up read produced ${next.describe()}",
                    )
                assertEquals(CHUNK, data.buffer.remaining(), "the whole delivered chunk must survive, not part of it")
                data.buffer.freeIfNeeded()
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }

    /**
     * **The coalesced FIN.** `stream_recv` can report bytes **and** the FIN in one result. The bytes have
     * to survive the timeout *and* so does the end-of-stream marker, or the stream can never report a
     * clean end — the shape that leaves a connection alive with a stream that is silently dead.
     */
    @Test
    fun aFinCoalescedIntoATimedOutReadStillEndsTheStream() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            try {
                val (adapter, slot) =
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Data(bytesRead = CHUNK, fin = true),
                        api,
                        udpGate,
                        driver,
                    )

                val next = adapter.readCatching(slot, 1.seconds)
                val data =
                    assertIs<ReadResult.Data>(
                        next.getOrNull(),
                        "the final chunk was dropped by the timeout; the follow-up read produced ${next.describe()}",
                    )
                assertEquals(CHUNK, data.buffer.remaining())
                data.buffer.freeIfNeeded()

                // Bytes outrank the FIN (RFC 9000 §2.4), so End is the read *after* the data — and it must
                // come from the recorded FIN, not from parking until this read's own deadline.
                val end = adapter.readCatching(slot, 1.seconds)
                assertIs<ReadResult.End>(
                    end.getOrNull(),
                    "the FIN that rode with the timed-out chunk was lost, so the stream can never end: " +
                        "the read after the data produced ${end.describe()}",
                )
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }

    /**
     * **The salvaged FIN announces itself.** `salvageCancelledRecv` is the one latch site that can end a
     * stream while losing nothing: it rescues the cancelled recv's bytes *and* its FIN, and latches on
     * the branch where the chunk queued successfully. `STREAM_LOSS` is therefore silent here by
     * construction, so a trace of a stream killed this way used to read as "nothing was dropped" — an
     * exoneration of the exact path issue #393 suspects.
     *
     * This drives the same interleaving as [aFinCoalescedIntoATimedOutReadStillEndsTheStream] and asserts
     * on the *trace* rather than on the reads: a `STREAM_END` naming `CancelledRecvSalvage`, and no
     * `STREAM_LOSS` beside it. Without this, the instrument's emission at its most important site would
     * be unproven — the round-trip tests only pin the codec, and the end-to-end suites cannot force this
     * window through the public API at all.
     */
    @Test
    fun aFinSalvagedOutOfATimedOutReadIsRecordedAtItsSite() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val events = mutableListOf<TraceEvent>()
            val driver = gatedStartupDriver(api, udpGate, QuicTraceRecorder(TraceSink { events += it }))
            // A recorder makes `start` launch two StateFlow collectors into whatever scope it is handed
            // (QuicheDriver.start), and a StateFlow collector never completes on its own — by design, it
            // ends when the connection's scope does. Handed this test's own scope it would instead hold
            // the test open to the whole-test budget, which is a 15s timeout rather than an assertion.
            // So the driver gets a child scope that this test cancels itself.
            val driverScope = CoroutineScope(coroutineContext + Job())
            try {
                with(driverScope) {
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Data(bytesRead = CHUNK, fin = true),
                        api,
                        udpGate,
                        driver,
                    )
                }

                val ends = events.filterIsInstance<TraceEvent.StreamEndLatched>()
                assertEquals(1, ends.size, "expected exactly one STREAM_END for the salvaged FIN, got $ends")
                assertEquals("Fin", ends.single().kind)
                assertEquals(
                    "CancelledRecvSalvage",
                    ends.single().site,
                    "the salvage site is the whole diagnostic value — a FIN latched here is the #393 shape",
                )
                assertTrue(
                    events.filterIsInstance<TraceEvent.StreamLoss>().isEmpty(),
                    "nothing was dropped on this path, which is exactly why STREAM_LOSS alone could not see it",
                )
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
                driverScope.cancel()
            }
        }

    /**
     * **The pure FIN.** `Data(0, fin = true)` carries no bytes to salvage, only the end of the stream.
     * Swallowing it wedges the reader just as thoroughly: quiche has delivered the FIN, so no further
     * data and no readable-signal is ever coming, and every subsequent `read()` burns its full deadline
     * and then throws.
     */
    @Test
    fun aPureFinSwallowedByATimedOutReadStillEndsTheStream() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            try {
                val (adapter, slot) =
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Data(bytesRead = 0, fin = true),
                        api,
                        udpGate,
                        driver,
                    )

                val end = adapter.readCatching(slot, 1.seconds)
                assertIs<ReadResult.End>(
                    end.getOrNull(),
                    "the FIN was consumed by the timed-out read and dropped, so the next read parks on a " +
                        "dataSignal nothing can ever tickle: it produced ${end.describe()}",
                )
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }

    /**
     * Salvaging must not trade a data-loss bug for a native-memory leak: a chunk parked in
     * [StreamSlot.pendingData] that the reader never comes back for is released when the read side goes
     * away for good, exactly like the teardown-drained chunks
     * (`ReactiveDriverTests.streamClose_releasesUndeliveredTeardownChunks`).
     */
    @Test
    fun aSalvagedChunkTheReaderAbandonsIsReleasedOnClose() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            try {
                val (adapter, slot) =
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Data(bytesRead = CHUNK, fin = false),
                        api,
                        udpGate,
                        driver,
                    )
                val stream = QuicheStreamByteStream(slot.id, adapter, driver.streamReadPool)

                // Anti-vacuity: an empty queue would satisfy the assertion below with no fix at all, so
                // prove the chunk really is parked here — then put it back, since `close()` releasing it
                // is the actual subject.
                val parked =
                    assertNotNull(
                        slot.pendingData.tryReceive().getOrNull(),
                        "nothing was salvaged into the slot, so this test would pass vacuously",
                    )
                assertEquals(CHUNK, parked.remaining())
                slot.pendingData.trySend(parked)

                stream.close()
                assertNull(
                    slot.pendingData.tryReceive().getOrNull(),
                    "close() must release the salvaged chunk — read() is rejected from here on, so holding " +
                        "it would leak one pooled/native buffer per timed-out read",
                )
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }

    private companion object {
        /** Bytes the driver reports delivering. Arbitrary; only that the count survives intact matters. */
        const val CHUNK = 9

        /** Per-read buffer size, matching the other driver suites. */
        const val BUF = 1024
    }

    /**
     * **The cancellation-edge reset (#398, the #393 shape).** A RESET_STREAM answered into a read
     * that already timed out must still end the stream *as a reset*: quiche collects the stream once
     * the reset is observed, so nothing re-delivers it — if the salvage path drops it, the next read
     * re-asks quiche, gets nothing forever, and the stream dies silently on a live connection.
     */
    @Test
    fun aResetDeliveredIntoATimedOutReadStillEndsTheStreamAsReset() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            try {
                val (adapter, slot) =
                    afterATimedOutReadAnsweredWith(
                        StreamRecvResult.Reset(QuicAppErrorCode(0x10c)),
                        api,
                        udpGate,
                        driver,
                    )

                val next = adapter.readCatching(slot, 1.seconds)
                assertIs<ReadResult.Reset>(
                    next.getOrNull(),
                    "the RESET_STREAM answered into the timed-out read was dropped — quiche has already " +
                        "collected the stream, so the abort is unrecoverable and the follow-up read " +
                        "produced ${next.describe()} (#398)",
                )
                assertEquals(
                    StreamEnd.Reset(QuicAppErrorCode(0x10c)),
                    slot.end,
                    "the peer's application error code must survive the cancellation edge, typed",
                )
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }
}
