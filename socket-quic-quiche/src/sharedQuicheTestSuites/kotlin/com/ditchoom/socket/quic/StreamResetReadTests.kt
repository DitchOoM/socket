package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * **A peer RESET_STREAM is a reset, not a polite end** (issue #398).
 *
 * `quiche_conn_stream_recv` reports a peer's RESET_STREAM as `STREAM_RESET` (-16) with the peer's
 * application error code in `out_error_code`. Every backend used to discard the code and the driver
 * collapsed the result to [ReadResult.End] — indistinguishable from a graceful FIN, so an
 * application could not tell a cancelled request from a completed one. [ReadResult.Reset] existed,
 * was handled by every consumer, and had **zero producers** in any quiche backend.
 *
 * These tests pin the read side's contract at the driver level: a reset reads as [ReadResult.Reset];
 * the peer's code lands, typed, in [StreamSlot.end] (the [ReadResult.Reset] marker itself cannot
 * carry it — it is a field-less object in the pinned buffer artifact); the verdict is sticky across
 * later reads even once quiche has collected the stream; bytes the transport already delivered
 * outrank the verdict (the #318/#393 rule); and the two answers that are NOT a reset keep their
 * meaning — a zero-byte chunk without a FIN is a wait, not an end, and a pure FIN delivers drained
 * bytes before End.
 *
 * The write path has carried the peer's code since the `QuicStreamAbort` work — this file is the
 * read-path parity. The cancellation edge (a reset answered into a timed-out read) is pinned in
 * [StreamReadCancellationTests], whose gated harness that case needs.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, and this directory is
 * `srcDir`'d into both — so one copy runs on jvm/apple/linux *and* the Android device lane, which
 * ships the JNI backend. See DitchOoM/socket#390.
 */
class StreamResetReadTests {
    private val bufferFactory = BufferFactory.deterministic()

    private val resetCode = QuicAppErrorCode(0x10c) // HTTP/3 REQUEST_CANCELLED — the code the E2E suite also uses

    private fun createTestDriver(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: these tests never move a path.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = false,
        )

    @Test
    fun aPeerResetIsReportedAsResetNotEnd() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Reset(resetCode))
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
                val result = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Reset>(
                    result,
                    "a peer RESET_STREAM was reported as $result — an abnormal, code-carrying abort " +
                        "laundered into the value that means the peer finished politely (#398)",
                )
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun theResetsApplicationErrorCodeIsRecordedTyped() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Reset(resetCode))
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = StreamSlot(QuicStreamId(0L))
                val adapter = DriverStreamAdapter(driver, slot)
                adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertEquals(
                    StreamEnd.Reset(resetCode),
                    slot.end,
                    "the peer's application error code was discarded on the read path — the write path " +
                        "has carried it since QuicStreamAbort; the read path must not lose it (#398)",
                )
            } finally {
                driver.destroy()
            }
        }

    /**
     * quiche collects a reset stream once observed, and later `stream_recv` calls answer
     * `INVALID_STREAM_STATE` (-7) — one of only two codes reachable on a live connection. The slot's
     * recorded verdict, not quiche's post-collection answer, is what later reads must report:
     * anything else turns the reset back into End one read later.
     */
    @Test
    fun readsAfterAResetKeepReportingResetEvenOnceQuicheCollectsTheStream() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Reset(resetCode))
            api.streamRecvResult = StreamRecvResult.Error(QuicheDriver.QUICHE_ERR_INVALID_STREAM_STATE)
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = StreamSlot(QuicStreamId(0L))
                val adapter = DriverStreamAdapter(driver, slot)
                assertIs<ReadResult.Reset>(adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds))
                assertIs<ReadResult.Reset>(
                    adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds),
                    "the read after a reset forgot the verdict — the abort must be sticky, exactly " +
                        "like a FIN, or it degrades to End the moment quiche collects the stream",
                )
            } finally {
                driver.destroy()
            }
        }

    /**
     * The #318/#393 rule applied to resets: bytes the transport already accepted outrank the
     * terminal verdict. A chunk sitting in [StreamSlot.pendingData] (teardown drain / salvage) is
     * handed out first; the reset only ends the stream once the data it follows is delivered.
     */
    @Test
    fun bufferedBytesOutrankTheResetVerdict() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Reset(resetCode))
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = StreamSlot(QuicStreamId(0L))
                val buffered = bufferFactory.allocate(4)
                repeat(4) { buffered.writeByte(7) }
                buffered.resetForRead()
                slot.pendingData.trySend(buffered)
                val adapter = DriverStreamAdapter(driver, slot)

                val first = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Data>(first, "buffered bytes must be delivered before any terminal verdict")
                assertEquals(4, first.buffer.remaining())
                // streamRead transfers the buffer; below the ByteStream layer the scoped read cannot
                // reach, so the release is explicit here (#538).
                first.buffer.freeIfNeeded()
                val second = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Reset>(second, "the reset must follow the data it arrived behind")
            } finally {
                driver.destroy()
            }
        }

    /**
     * `stream_recv` answering 0 bytes without a FIN is not an end of anything — treating it as End
     * fabricated a clean EOF from an unenforced "0 implies FIN" assumption. The read must wait for
     * the data signal like a Done and pick up what arrives next. (The pre-armed CONFLATED signal is
     * what lets this test terminate: a signal sent before the park is buffered, not lost.)
     */
    @Test
    fun aZeroByteChunkWithoutAFinIsAWaitNotAnEnd() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Data(0, fin = false))
            api.streamRecvSequence.addLast(StreamRecvResult.Data(3, fin = true))
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = StreamSlot(QuicStreamId(0L))
                slot.dataSignal.trySend(Unit)
                val adapter = DriverStreamAdapter(driver, slot)
                val result = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Data>(
                    result,
                    "a zero-byte, no-FIN answer was reported as a stream end instead of waiting for " +
                        "the bytes that follow — got $result",
                )
                assertEquals(3, result.buffer.remaining())
                result.buffer.freeIfNeeded()
            } finally {
                driver.destroy()
            }
        }

    /**
     * A pure-FIN answer (`0 bytes, fin = true`) was the only terminal arm that did not drain
     * [StreamSlot.pendingData] first — a latent repeat of #318: the teardown drain can queue bytes in
     * the same driver wake that answers the FIN, and End must never overtake them. The delegating
     * api double queues a chunk *inside* `connStreamRecv` — on the driver loop, after the read's own
     * entry drain already ran — which is exactly the interleaving the entry check cannot see.
     */
    @Test
    fun aPureFinStillDeliversBytesDrainedInTheSameWake() =
        runQuicTest {
            val stub = StubQuicheApi()
            val slot = StreamSlot(QuicStreamId(0L))
            val api =
                object : QuicheApi by stub {
                    override fun connStreamRecv(
                        conn: QuicheConn,
                        streamId: QuicStreamId,
                        buf: Long,
                        bufLen: Int,
                    ): StreamRecvResult {
                        val drained = BufferFactory.deterministic().allocate(2)
                        repeat(2) { drained.writeByte(9) }
                        drained.resetForRead()
                        slot.pendingData.trySend(drained)
                        return StreamRecvResult.Data(0, fin = true)
                    }
                }
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, slot)
                val first = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Data>(
                    first,
                    "a pure FIN overtook bytes drained into the slot in the same wake — End before " +
                        "the data it is supposed to follow, the #318 shape — got $first",
                )
                assertEquals(2, first.buffer.remaining())
                first.buffer.freeIfNeeded()
                assertIs<ReadResult.End>(adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds))
            } finally {
                driver.destroy()
            }
        }

    /**
     * A quiche stream error is a **failure**, not a polite end (issue #421).
     *
     * The read path used to map every [StreamRecvResult.Error] onto [ReadResult.End], so a failure was
     * indistinguishable from the peer finishing — the same defect #398 fixed for RESET_STREAM, still
     * open for every other code. `End` is a contract (*stop reading, release the stream, move on*) and
     * it is the wrong answer to an error.
     *
     * It is also undiagnosable: 30 minutes of `End` in the #393 device recording could not say whether
     * the peer had closed the stream or quiche was failing every read, and those have opposite fixes.
     *
     * The typed code is carried because renaming an unexpected quiche code would hide it — see
     * [QuicStreamReadError.Quiche]. The complete fix puts the failure in the read *result* so callers
     * are forced to handle it rather than having to catch, which needs a buffer major
     * (DitchOoM/buffer#376, v7).
     */
    @Test
    fun aQuicheStreamErrorSurfacesAsAFailureNotAnEnd() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Error(QuicheDriver.QUICHE_ERR_INVALID_STREAM_STATE)
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
                val thrown =
                    assertFailsWith<QuicStreamReadException>(
                        "a quiche stream error must not read as a clean end-of-stream",
                    ) { adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds) }
                assertEquals(QuicStreamReadError.InvalidStreamState, thrown.error)
                assertEquals(0L, thrown.streamId)
            } finally {
                driver.destroy()
            }
        }

    /** An unrecognised code arrives intact and obviously unexpected, rather than disguised as a known one. */
    @Test
    fun anUnknownQuicheCodeKeepsItsRawValue() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Error(-11) // FLOW_CONTROL: not a documented stream_recv code
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
                val thrown =
                    assertFailsWith<QuicStreamReadException> {
                        adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                    }
                assertEquals(QuicStreamReadError.Quiche(-11), thrown.error)
            } finally {
                driver.destroy()
            }
        }

    /**
     * The #318/#393 ordering rule applied to failures: bytes the transport already accepted outrank the
     * error, exactly as they outrank a reset. A failure that arrived behind buffered data must not
     * destroy it — quiche has already advanced the receive offset, so nothing re-delivers it.
     */
    @Test
    fun bufferedBytesOutrankAStreamError() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Error(QuicheDriver.QUICHE_ERR_INVALID_STREAM_STATE)
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = StreamSlot(QuicStreamId(0L))
                val buffered = bufferFactory.allocate(4)
                repeat(4) { buffered.writeByte(9) }
                buffered.resetForRead()
                slot.pendingData.trySend(buffered)
                val adapter = DriverStreamAdapter(driver, slot)

                val first = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Data>(first, "buffered bytes must be delivered before the failure")
                assertEquals(4, first.buffer.remaining())
                first.buffer.freeIfNeeded()
                assertFailsWith<QuicStreamReadException>("the failure must follow the data it arrived behind") {
                    adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                }
            } finally {
                driver.destroy()
            }
        }
}
