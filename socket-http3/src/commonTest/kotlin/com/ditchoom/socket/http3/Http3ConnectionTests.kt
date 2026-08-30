package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamAbort
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Scripted-stream unit tests for [Http3Connection.bootstrap] (RFC 9114 §3.2/§6.2/§7.2.4): the
 * client's control/QPACK uni-stream setup and the peer-stream router that resolves SETTINGS.
 * Every test double's reads are finite, so the router completes on its own and the enclosing
 * [coroutineScope] joins it — no manual teardown. Live H3 is covered by a gated interop test.
 */
class Http3ConnectionTests {
    // --- bytes helpers ------------------------------------------------------

    private fun frameBytes(frame: Http3Frame): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        HandwrittenHttp3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    private fun clientSettings() =
        Http3Frame.Settings(
            listOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
            ),
        )

    private fun dataChunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    /** A peer control stream: type prefix 0x00, then [settings], then end-of-stream. */
    private fun peerControlStream(settings: Http3Frame.Settings): QuicByteStream =
        QuicByteStream(
            QuicStreamId(3), // server-initiated unidirectional
            RecordingByteStream(
                listOf(dataChunk(listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(settings)), ReadResult.End),
            ),
        )

    // --- test doubles -------------------------------------------------------

    /** A [ByteStream] that records everything written and replays a scripted read sequence. */
    private class RecordingByteStream(
        readScript: List<ReadResult> = emptyList(),
    ) : ByteStream,
        com.ditchoom.buffer.flow.Resettable {
        val written = mutableListOf<Int>()
        private val reads = ArrayDeque(readScript)
        var closed = false
            private set

        /** The application error code from a [reset], or null if the stream was never reset. */
        var resetCode: Long? = null
            private set

        override val isOpen: Boolean get() = !closed
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

        override suspend fun read(deadline: Duration): ReadResult = if (reads.isEmpty()) ReadResult.End else reads.removeFirst()

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            val n = buffer.remaining()
            repeat(n) { written += buffer.readByte().toInt() and 0xFF }
            return BytesWritten(n)
        }

        override suspend fun close() {
            closed = true
        }

        override suspend fun reset(errorCode: Long) {
            resetCode = errorCode
            closed = true
        }
    }

    /**
     * A [QuicScope] test double: hands out [outgoing] uni streams in order from [openUniStream]
     * and replays [incoming] from [streams]. [openStream]/[acceptStream] are unused here, and
     * [migrate]/[pathState]/datagram methods keep the interface's no-op defaults.
     */
    private class FakeQuicScope(
        delegate: CoroutineScope,
        private val outgoing: ArrayDeque<QuicByteStream>,
        private val incoming: Flow<QuicByteStream>,
        private val bidi: ArrayDeque<QuicByteStream> = ArrayDeque(),
    ) : QuicScope,
        CoroutineScope by delegate {
        /**
         * A finite list of peer streams — the ordinary case, and the one that models a **closed
         * connection**: [QuicScope.streams] completes when the connection closes (its contract), so a
         * list flow that runs out is this double saying the connection has ended. A test that needs the
         * connection to still be live while a stream misbehaves passes a [Flow] that has not completed
         * yet (see the #530 cases).
         */
        constructor(
            delegate: CoroutineScope,
            outgoing: ArrayDeque<QuicByteStream>,
            incoming: List<QuicByteStream>,
            bidi: ArrayDeque<QuicByteStream> = ArrayDeque(),
        ) : this(delegate, outgoing, incoming.asFlow(), bidi)

        override val bufferFactory: BufferFactory = BufferFactory.Default
        val remainingUniStreams get() = outgoing.size

        /** The application error code of the client's CONNECTION_CLOSE (RFC 9114 §8), once it aborts. */
        val closeErrorCode = CompletableDeferred<Long>()

        override suspend fun openUniStream(): QuicByteStream = outgoing.removeFirst()

        override suspend fun openStream(): QuicByteStream =
            if (bidi.isEmpty()) throw UnsupportedOperationException() else bidi.removeFirst()

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException()

        override fun streams(): Flow<QuicByteStream> = incoming

        override suspend fun closeWithError(errorCode: Long) {
            closeErrorCode.complete(errorCode)
        }
    }

    /** The three client uni streams [bootstrap] opens, with recording delegates exposed. */
    private class ClientStreams {
        val control = RecordingByteStream()
        val qpackEncoder = RecordingByteStream()
        val qpackDecoder = RecordingByteStream()

        fun outgoing(): ArrayDeque<QuicByteStream> =
            ArrayDeque(
                listOf(
                    QuicByteStream(QuicStreamId(2), control),
                    QuicByteStream(QuicStreamId(6), qpackEncoder),
                    QuicByteStream(QuicStreamId(10), qpackDecoder),
                ),
            )
    }

    /**
     * **A peer QPACK encoder stream that goes quiet must not kill the pump (#472).**
     *
     * The peer's encoder stream is idle by design between header-block insertions, but the pump armed
     * `config.readPolicy.toDeadline()` on every read — [TransportConfig]'s default
     * `ReadPolicy.Bounded(15.seconds)`. `QuicheDriver` implements that as `withTimeout`, which raises
     * `TimeoutCancellationException`; that **is** a `CancellationException`, so `readCriticalQpackStream`
     * took its rethrow arm, and a `launch` child completing with a cancellation is *cancelled, not
     * failed* — the parent is never told. The pump exited, nothing logged, the connection stayed
     * "healthy", and every later encoder instruction was silently never applied until a HEADERS block
     * referencing an un-inserted entry failed to decode, presenting as the peer sending garbage.
     *
     * The witness is external and never reads the pump: applying an insert makes [QpackDecoder] report
     * an Insert Count Increment on **our** decoder stream (§4.4.3), so the bytes the client wrote there
     * say whether the instruction after the silence was applied. [SilentThenSpeakingStream] enforces the
     * deadline the way the real QUIC leaf does, rather than ignoring it as [RecordingByteStream] would.
     *
     * The peer control stream already read with no deadline; only the two QPACK pumps armed one.
     */
    @Test
    fun aSilentPeerEncoderStreamStillAppliesTheNextInstruction() =
        runTest {
            val client = ClientStreams()
            val insert = QpackEncoderInstruction.InsertWithLiteralName("x-after-silence", "applied")
            val peerEncoder =
                QuicByteStream(
                    QuicStreamId(7),
                    SilentThenSpeakingStream(
                        first = listOf(Http3StreamType.QPACK_ENCODER.toInt()) + encoderBytes(QpackEncoderInstruction.SetCapacity(4096)),
                        // Longer than the 15s default, so the pre-#472 deadline expires inside the silence.
                        silence = 20.seconds,
                        afterSilence = encoderBytes(insert),
                    ),
                )
            val scope =
                FakeQuicScope(
                    this,
                    client.outgoing(),
                    incoming = listOf(peerEncoder, peerControlStream(clientSettings())),
                )

            val connection = Http3Connection.bootstrap(scope, TransportConfig())
            connection.peerSettings()
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(Http3StreamType.QPACK_DECODER.toInt()),
                client.qpackDecoder.written.take(1),
                "sanity: the decoder stream starts with its type prefix",
            )
            assertTrue(
                client.qpackDecoder.written.size > 1,
                "the peer's encoder stream went quiet for ${20.seconds} — longer than the default " +
                    "15s read deadline — and then inserted an entry. Applying it must emit an Insert " +
                    "Count Increment on our decoder stream (RFC 9204 §4.4.3), but the decoder stream " +
                    "holds only its type prefix, so the instruction was never applied: the pump died " +
                    "in the silence and took the decoder's dynamic table out of sync with the peer's " +
                    "(#472). Nothing threw, and connectionError is ${connection.connectionError}.",
            )
        }

    /** Encodes one QPACK encoder instruction to the bytes a peer would put on the wire. */
    private fun encoderBytes(instruction: QpackEncoderInstruction): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        QpackEncoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    // --- #477: a router child's read deadline is a per-stream failure, not a cancellation ----------

    /** A 200 response with no body: HEADERS(:status 200) then end-of-stream. */
    private fun okResponseBytes(): List<Int> =
        frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "200")))))

    /**
     * **A peer bidirectional stream that stalls past the read deadline is abandoned by name, not mistaken
     * for a cancelled router (#477).**
     *
     * With WebTransport enabled, `route()` peeks a peer-initiated bidi stream's first varint for the
     * `0x41` signal under `config.readPolicy.toDeadline()`. Unlike the QPACK pumps of #472 that deadline
     * is right — a peer that opens a stream and says nothing must not be waited on forever — but the QUIC
     * leaf raises `TimeoutCancellationException`, which neither of `route()`'s catch arms matched, and as
     * a `CancellationException` it made the `launch` child read as *cancelled rather than failed*: the
     * finally gave the stream a bare `close()` — exactly what a genuinely cancelled router does — and
     * nothing else happened. On QUIC `close()` is a send-side FIN only, so the peer's half stayed open.
     *
     * The witness is the peer's view of the stream, [StalledStream.disposition]: a stream abandoned on
     * its deadline is **reset carrying `H3_REQUEST_CANCELLED`** — the code RFC 9114 §4.1.1 gives a client
     * for "data no longer needed" — where a cancelled router leaves a bare close (see
     * [aCancelledRouterClosesAStalledStreamWithoutNamingIt]). The connection is then shown intact by a
     * request that still round-trips.
     */
    @Test
    fun aStalledPeerBidiStreamIsAbandonedByName_andTheConnectionSurvives() =
        runTest {
            val peerBidi = StalledStream(prefix = emptyList()) // opened, then silent
            val response = RecordingByteStream(listOf(dataChunk(okResponseBytes()), ReadResult.End))
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi), peerControlStream(clientSettings())),
                    bidi = ArrayDeque(listOf(QuicByteStream(QuicStreamId(0), response))),
                )

            val connection = Http3Connection.bootstrap(scope, TransportConfig(), webTransport = WebTransportOptions())
            connection.peerSettings()
            testScheduler.advanceUntilIdle() // runs the stalled peek out past its 15s deadline

            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerBidi.disposition,
                "peer bidi stream 1 sent nothing for longer than the default 15s read deadline. Abandoning " +
                    "it must reach the peer as STOP_SENDING/RESET_STREAM carrying H3_REQUEST_CANCELLED " +
                    "(RFC 9114 §4.1.1). A bare close is what a *cancelled* router leaves, so it says the " +
                    "deadline was mistaken for cancellation (#477) and the peer's half of the stream is " +
                    "still open. connectionError=${connection.connectionError}",
            )
            assertNull(connection.connectionError, "one stalled peer stream must not become a connection error")
            val ok = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
            assertEquals(200, ok.status, "the connection must still serve a request after abandoning the stalled stream")
            ok.close()
        }

    /**
     * **A push stream that stalls before its Push ID is abandoned by name (#477).**
     *
     * `handlePushStream` reads the Push ID under the deadline. Its own `catch (e: CancellationException)
     * { throw e }` arm claimed the `TimeoutCancellationException` ahead of the `Throwable` arm that names
     * push failures, `route()` had no arm for it either, and so the child died as "cancelled": a bare
     * close, and — the Push ID never having arrived — no push to fail. RFC 9114 §4.6 has the client abort
     * reading a push stream it gives up on with `H3_REQUEST_CANCELLED`, so the server stops committing
     * data to it; that reset is the witness here.
     */
    @Test
    fun aStalledPushStreamIsAbandonedByName_andTheConnectionSurvives() =
        runTest {
            val peerPush = StalledStream(prefix = listOf(Http3StreamType.PUSH.toInt())) // the type, then silence
            val response = RecordingByteStream(listOf(dataChunk(okResponseBytes()), ReadResult.End))
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(11), peerPush), peerControlStream(clientSettings())),
                    bidi = ArrayDeque(listOf(QuicByteStream(QuicStreamId(0), response))),
                )

            val connection = Http3Connection.bootstrap(scope, TransportConfig(), maxPushId = 8)
            connection.peerSettings()
            testScheduler.advanceUntilIdle() // runs the stalled Push ID read out past its 15s deadline

            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerPush.disposition,
                "push stream 11 sent its type byte and then nothing for longer than the default 15s read " +
                    "deadline. RFC 9114 §4.6: abort reading it with H3_REQUEST_CANCELLED so the server " +
                    "stops committing data. A bare close is what a *cancelled* router leaves — the " +
                    "deadline was mistaken for cancellation (#477), the peer's half is still open, and no " +
                    "error went anywhere. connectionError=${connection.connectionError}",
            )
            assertNull(connection.connectionError, "one stalled push stream must not become a connection error")
            val ok = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
            assertEquals(200, ok.status, "the connection must still serve a request after abandoning the stalled push stream")
            ok.close()
        }

    /**
     * **A push stream that stalls after its Push ID fails the promised response by name (#477).**
     *
     * One read further along the same path: the Push ID arrived, so a push entry exists and the server's
     * PUSH_PROMISE has handed the caller an [Http3ServerPush] — then the response HEADERS never come. This
     * is the acceptance criterion's "the pushed response never arrives with no error anywhere": before
     * the fix [Http3ServerPush.response] waited forever. It must instead fail with a typed
     * [Http3Violation.PeerStreamDeadlineExpired] naming the stream, and the stream is reset like the others.
     */
    @Test
    fun aPushStreamStallingAfterItsPushIdFailsThePromisedResponseByName() =
        runTest {
            val pushId = 2L
            val peerPush = StalledStream(prefix = listOf(Http3StreamType.PUSH.toInt(), pushId.toInt())) // type + Push ID, then silence
            val promise =
                Http3Frame.PushPromise(
                    pushId,
                    encodedFieldSection(
                        listOf(
                            QpackHeaderField(":method", "GET"),
                            QpackHeaderField(":scheme", "https"),
                            QpackHeaderField(":authority", "h.test"),
                            QpackHeaderField(":path", "/pushed"),
                        ),
                    ),
                )
            val response = RecordingByteStream(listOf(dataChunk(frameBytes(promise) + okResponseBytes()), ReadResult.End))
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(11), peerPush), peerControlStream(clientSettings())),
                    bidi = ArrayDeque(listOf(QuicByteStream(QuicStreamId(0), response))),
                )

            val connection = Http3Connection.bootstrap(scope, TransportConfig(), maxPushId = 8)
            connection.request(Http3Request(method = "GET", authority = "h.test", path = "/")).close()
            val push = connection.pushes.first()
            assertEquals(pushId, push.pushId, "sanity: the PUSH_PROMISE on the request stream was surfaced")

            val failure =
                try {
                    val unexpected = withTimeout(60.seconds) { push.response() }
                    fail("push $pushId completed with a response (status ${unexpected.status}) though its stream never sent HEADERS")
                } catch (e: TimeoutCancellationException) {
                    fail(
                        "push $pushId's stream sent its Push ID and then nothing for 60s of virtual time — four times " +
                            "the 15s read deadline — yet response() is still waiting. The deadline killed the router " +
                            "child as a cancellation instead of failing this push (#477); the stream's disposition " +
                            "is ${peerPush.disposition}, connectionError=${connection.connectionError}",
                    )
                } catch (e: Http3StreamException) {
                    e
                }
            val violation =
                assertIs<Http3Violation.PeerStreamDeadlineExpired>(
                    failure.violation,
                    "the push must fail by the deadline's name, not ${failure.violation}",
                )
            assertEquals(QuicStreamId(11), violation.streamId, "the violation must name the stalled push stream")
            assertEquals(15.seconds, violation.deadline, "the violation must carry the deadline that expired")
            assertEquals(Http3ErrorCode.REQUEST_CANCELLED, failure.errorCode)
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerPush.disposition,
                "the stalled push stream must also be reset for the peer (RFC 9114 §4.6), not merely closed",
            )
            assertNull(connection.connectionError, "one stalled push stream must not become a connection error")
        }

    /**
     * The control for the three above: a **genuinely** cancelled router leaves a stalled stream with a bare
     * close and no name. That is what makes the reset the deadline's alone — a fix that caught
     * `CancellationException` wholesale (the ordering trap #472 documents, from the other side) would
     * report cancellation as a deadline and fail here.
     */
    @Test
    fun aCancelledRouterClosesAStalledStreamWithoutNamingIt() =
        runTest {
            val peerBidi = StalledStream(prefix = emptyList())
            val connectionJob =
                launch {
                    coroutineScope {
                        val scope =
                            FakeQuicScope(
                                this,
                                ClientStreams().outgoing(),
                                incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi)),
                            )
                        Http3Connection.bootstrap(scope, TransportConfig(), webTransport = WebTransportOptions())
                        // coroutineScope now waits on the router, which waits on the stalled peek.
                    }
                }
            testScheduler.advanceTimeBy(1.seconds) // the peek is parked on the stall, well inside its 15s deadline
            assertEquals(Disposition.Open, peerBidi.disposition, "sanity: nothing has happened to the stream yet")

            connectionJob.cancelAndJoin()

            assertEquals(
                Disposition.Closed,
                peerBidi.disposition,
                "a cancelled router must leave the stalled stream with a bare close: cancellation is not a " +
                    "deadline, and resetting it with H3_REQUEST_CANCELLED would be the #477 confusion in reverse",
            )
        }

    /**
     * The harder control: a **parent** `withTimeout` cancels every child *with its own*
     * `TimeoutCancellationException` (`JobSupport.getChildJobCancellationCause` passes a
     * `CancellationException` root cause through unchanged), so inside the router child the exception
     * that surfaces from the parked read is indistinguishable **by type** from a read deadline. Only the
     * job tells them apart — a read deadline leaves the child active, a cancellation does not — and a fix
     * keyed on the type alone would reset this stream and report a teardown as a stall.
     */
    @Test
    fun aParentDeadlineCancellingTheRouterIsNotAStreamDeadline() =
        runTest {
            val peerBidi = StalledStream(prefix = emptyList())
            try {
                withTimeout(1.seconds) {
                    val scope =
                        FakeQuicScope(
                            this,
                            ClientStreams().outgoing(),
                            incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi)),
                        )
                    Http3Connection.bootstrap(scope, TransportConfig(), webTransport = WebTransportOptions())
                    // withTimeout's scope waits on its children: the router, parked on the stalled peek.
                }
                fail("withTimeout(1s) around a router parked on a stalled peer stream must expire, not return")
            } catch (e: TimeoutCancellationException) {
                // The parent's deadline — the one that is supposed to fire here.
            }

            assertEquals(
                Disposition.Closed,
                peerBidi.disposition,
                "the router was cancelled by a parent withTimeout(1s), well inside the stream's own 15s read " +
                    "deadline, and its child received that parent's TimeoutCancellationException. A reset with " +
                    "H3_REQUEST_CANCELLED here means the fix keyed on the exception's type and reported a " +
                    "cancellation as a stream stall — the #477 confusion in reverse",
            )
        }

    /**
     * **The same control for #476's arm (#495, item 3): a parent deadline cancelling the connection is
     * not the QPACK pump's own expiry.**
     *
     * `readCriticalQpackStream` names a `TimeoutCancellationException` out of a pump as
     * [Http3Violation.QpackPumpDeadlineExpired] and aborts the connection with it — a critical stream
     * dying must be loud (RFC 9204 §4.2). But a parent `withTimeout` cancels the router child WITH its
     * own `TimeoutCancellationException` (see [aParentDeadlineCancellingTheRouterIsNotAStreamDeadline]),
     * so keyed on the type alone that arm reported a teardown as the pump's deadline: `connectionError`
     * set, and `closeWithError(QPACK_ENCODER_STREAM_ERROR)` sent to a peer that did nothing wrong. Only
     * the job tells the two apart — the pump reads with no deadline, so a `TimeoutCancellationException`
     * arriving while this coroutine is still active can only be a read deadline; one arriving cancelled
     * is somebody else's.
     */
    @Test
    fun aParentDeadlineCancellingTheConnectionIsNotAQpackPumpDeadline() =
        runTest {
            // The peer's encoder stream: its type byte, then silence — the pump parks on an unbounded read.
            val peerEncoder = StalledStream(prefix = listOf(Http3StreamType.QPACK_ENCODER.toInt()))
            lateinit var connection: Http3Connection
            try {
                withTimeout(1.seconds) {
                    val scope =
                        FakeQuicScope(
                            this,
                            ClientStreams().outgoing(),
                            incoming = listOf(QuicByteStream(QuicStreamId(7), peerEncoder)),
                        )
                    connection = Http3Connection.bootstrap(scope, TransportConfig())
                    // withTimeout's scope waits on its children: the router, parked in the encoder pump.
                }
                fail("withTimeout(1s) around a router parked in a QPACK pump must expire, not return")
            } catch (e: TimeoutCancellationException) {
                // The parent's deadline — the one that is supposed to fire here.
            }

            assertNull(
                connection.connectionError,
                "the connection scope was cancelled by a parent withTimeout(1s) while the peer encoder pump " +
                    "was parked on a read with no deadline of its own. The pump received that parent's " +
                    "TimeoutCancellationException, and a connection error here means readCriticalQpackStream " +
                    "keyed on the exception's type alone and reported a teardown as the pump's own deadline " +
                    "(#495): the QPACK_ENCODER_STREAM_ERROR it sends blames a peer that did nothing wrong",
            )
        }

    // --- #513: one read-deadline policy for both roles --------------------------------------------

    /**
     * **An idle reserved/GREASE stream is drained without a deadline (#513, the client half).**
     *
     * `route()` drains a stream type it does not implement (RFC 9114 §6.2/§9: reserved and GREASE stream
     * types must be tolerated) purely to keep the peer's flow-control window for it open. The drain read
     * with no explicit deadline — which is *not* the same as no deadline: the no-arg
     * [com.ditchoom.buffer.flow.ByteSource.read] consults the **stream's** [ReadPolicy], and a QUIC stream
     * carries the transport's `ReadPolicy.Bounded(15.seconds)`. A reserved stream that sends nothing is
     * exactly what §9 describes, so at 15s the read raised `TimeoutCancellationException`, `route()`'s
     * stalled-stream arm reported an idle-by-design stream as a stall, and nothing read it again.
     *
     * Same rule as the QPACK pumps (#472) and the server's drain (#513): a read that is waiting on
     * something the peer already owes gets [TransportConfig.readPolicy]; a stream that is idle by design
     * gets no deadline and leaves liveness to the connection's idle timeout.
     */
    @Test
    fun anIdleGreaseStreamIsDrainedWithoutADeadline() =
        runTest {
            // 0x21 — a reserved stream type of the form 0x1f * N + 0x21 (§6.2.3), one byte on the wire.
            val greaseStream =
                SilentThenSpeakingStream(
                    first = listOf(0x21),
                    // Longer than the 15s default, so a deadline armed on the drain expires inside the silence.
                    silence = 20.seconds,
                    afterSilence = listOf(0xDE, 0xAD),
                )
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(11), greaseStream), peerControlStream(clientSettings())),
                )

            val connection = Http3Connection.bootstrap(scope, TransportConfig())
            connection.peerSettings()
            testScheduler.advanceUntilIdle()

            assertTrue(
                greaseStream.readToEnd,
                "the peer opened a reserved (GREASE) unidirectional stream, said nothing on it for 20s — longer " +
                    "than the default 15s read deadline — and then wrote. The drain keeping its flow-control " +
                    "window open must still have been reading: false means the drain's read carried the stream's " +
                    "own 15s ReadPolicy and expired in ordinary silence (#513). " +
                    "connectionError=${connection.connectionError}",
            )
            assertNull(connection.connectionError, "a quiet reserved stream is not a connection error")
        }

    /**
     * **A peer unidirectional stream that never sends its type is abandoned by name (#513, the client
     * half).** The server bounds this read (#511/#509) while `route()` read it with no bound at all, so a
     * peer that opened a unidirectional stream and said nothing was waited on forever — a router child
     * parked for the life of the connection with no name, nothing told to the peer, and no way for the
     * two roles to be reasoned about together.
     *
     * #513 settles that: the *head* of a peer-initiated stream — the read waiting for what the peer
     * already owes, here its type prefix (RFC 9114 §6.2) — is bounded by [TransportConfig.readPolicy] on
     * both roles, and giving up on it is named and reaches the peer, exactly as
     * [aStalledPeerBidiStreamIsAbandonedByName_andTheConnectionSurvives] requires of a bidirectional one.
     */
    @Test
    fun aPeerUniStreamThatNeverSendsItsTypeIsAbandonedByName() =
        runTest {
            val stalled = StalledStream(prefix = emptyList()) // opened, then silent: not even a type
            lateinit var connection: Http3Connection
            // Not the plain in-scope bootstrap: with the type read unbounded the router parks forever and
            // coroutineScope would wait on it. A separate job lets the clock be run out past the deadline.
            val connectionJob =
                launch {
                    coroutineScope {
                        val scope =
                            FakeQuicScope(
                                this,
                                ClientStreams().outgoing(),
                                incoming = listOf(QuicByteStream(QuicStreamId(11), stalled), peerControlStream(clientSettings())),
                            )
                        connection = Http3Connection.bootstrap(scope, TransportConfig())
                    }
                }
            try {
                testScheduler.advanceTimeBy(20.seconds) // past the 15s default read deadline
                testScheduler.runCurrent()

                assertEquals(
                    Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                    stalled.disposition,
                    "peer uni stream 11 sent nothing — not even its type — for 20s, past the default 15s read " +
                        "deadline. It must be abandoned by name with H3_REQUEST_CANCELLED (RFC 9114 §4.1.1), the " +
                        "way the server has abandoned the same stall since #511; Open means this router is still " +
                        "waiting on it and will wait for the life of the connection (#513). " +
                        "connectionError=${connection.connectionError}",
                )
                assertNull(connection.connectionError, "one stalled peer stream must not become a connection error")
            } finally {
                connectionJob.cancelAndJoin()
            }
        }

    // --- #530: a peer that closes a critical stream ends the connection ---------------------------

    /**
     * A peer critical stream that delivers [prefix] and then **FINs** — the RFC 9114 §6.2.1 /
     * RFC 9204 §4.2 violation itself: "The sender MUST NOT close the control stream […] If either
     * control stream is closed at any point, this MUST be treated as a connection error of type
     * H3_CLOSED_CRITICAL_STREAM", and, for the QPACK instruction streams, "Closure of either
     * unidirectional stream type MUST be treated as a connection error of type
     * H3_CLOSED_CRITICAL_STREAM".
     */
    private fun peerStreamThatFins(
        id: Long,
        prefix: List<Int>,
    ): QuicByteStream = QuicByteStream(QuicStreamId(id), RecordingByteStream(listOf(dataChunk(prefix), ReadResult.End)))

    /**
     * Peer streams delivered on a connection that is still **live** afterwards — the distinction #530
     * turns on. [QuicScope.streams] completes when the connection closes, so a list of peer streams is a
     * connection that has *already ended* by the time its streams are read, and an end-of-stream there is
     * teardown rather than the peer closing anything. Emitting and then staying open for [liveFor] models
     * the other case: the peer FINs a critical stream while the connection carries on.
     */
    private fun liveStreams(
        vararg streams: QuicByteStream,
        liveFor: Duration = 1.seconds,
    ): Flow<QuicByteStream> =
        flow {
            for (stream in streams) emit(stream)
            delay(liveFor)
        }

    /**
     * **A peer that FINs its control stream ends the connection with H3_CLOSED_CRITICAL_STREAM (#530).**
     *
     * RFC 9114 §6.2.1 is unconditional: "The sender MUST NOT close the control stream, and the receiver
     * MUST NOT request that the sender close the control stream. If either control stream is closed at
     * any point, this MUST be treated as a connection error of type H3_CLOSED_CRITICAL_STREAM." *Either*
     * — so a client closing its own and a server closing its own are the same violation, and this is the
     * client observing the server's half of it.
     *
     * The control stream here does everything right (type prefix `0x00`, SETTINGS as its first frame) and
     * then FINs while the connection is live. `readControlFrames` simply `break`ed on that end-of-stream:
     * the handler returned, the router child completed, nothing was recorded, and the connection carried
     * on with no way to learn a GOAWAY, a MAX_PUSH_ID, or a CANCEL_PUSH ever again — the machinery §6.2.1
     * calls critical, gone, with the connection reporting itself healthy.
     */
    @Test
    fun aPeerThatClosesItsControlStreamEndsTheConnection() =
        runTest {
            lateinit var connection: Http3Connection
            lateinit var scope: FakeQuicScope
            coroutineScope {
                scope = FakeQuicScope(this, ClientStreams().outgoing(), liveStreams(peerControlStream(clientSettings())))
                connection = Http3Connection.bootstrap(scope, TransportConfig())
            }

            val error =
                assertNotNull(
                    connection.connectionError,
                    "the peer sent valid SETTINGS on its control stream and then FINed it while the connection was " +
                        "live. RFC 9114 §6.2.1 makes that a connection error of type H3_CLOSED_CRITICAL_STREAM; null " +
                        "means the control handler returned on end-of-stream and the connection kept going without " +
                        "the stream it depends on (#530)",
                )
            assertEquals(
                Http3Violation.ClosedCriticalStream(CriticalStreamType.CONTROL),
                error.violation,
                "the reason must name the stream that was closed, typed — not a generic control-stream failure",
            )
            assertEquals(
                Http3ErrorCode.CLOSED_CRITICAL_STREAM,
                withTimeout(5.seconds) { scope.closeErrorCode.await() },
                "the CONNECTION_CLOSE must carry H3_CLOSED_CRITICAL_STREAM (RFC 9114 §8.1)",
            )
        }

    /**
     * **The same for the peer's QPACK encoder stream (#530).** RFC 9204 §4.2: "The sender MUST NOT close
     * either of these streams, and the receiver MUST NOT request that the sender close either of these
     * streams. Closure of either unidirectional stream type MUST be treated as a connection error of type
     * H3_CLOSED_CRITICAL_STREAM."
     *
     * The stream carries a valid Set Dynamic Table Capacity and then FINs. The pump's `reader.next() ?:
     * break` treated that exactly as it treats a healthy exit, so the decoder's dynamic table stopped
     * tracking the peer's encoder table with nothing recorded — and the next response whose field section
     * references the table blocks on a Required Insert Count that can never be raised (#472's symptom
     * reached from a different cause).
     */
    @Test
    fun aPeerThatClosesItsQpackEncoderStreamEndsTheConnection() =
        runTest {
            lateinit var connection: Http3Connection
            lateinit var scope: FakeQuicScope
            coroutineScope {
                scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        liveStreams(
                            peerStreamThatFins(
                                id = 7,
                                prefix =
                                    listOf(Http3StreamType.QPACK_ENCODER.toInt()) +
                                        encoderBytes(QpackEncoderInstruction.SetCapacity(0)),
                            ),
                        ),
                    )
                connection = Http3Connection.bootstrap(scope, TransportConfig())
            }

            val error =
                assertNotNull(
                    connection.connectionError,
                    "the peer opened its QPACK encoder stream, sent a valid instruction, and FINed it while the " +
                        "connection was live. RFC 9204 §4.2 makes that a connection error of type " +
                        "H3_CLOSED_CRITICAL_STREAM; null means the pump returned on end-of-stream and this " +
                        "decoder's table silently stopped tracking the peer's (#530)",
                )
            assertEquals(
                Http3Violation.ClosedCriticalStream(CriticalStreamType.QPACK_ENCODER),
                error.violation,
                "the reason must name the QPACK encoder stream",
            )
            assertEquals(Http3ErrorCode.CLOSED_CRITICAL_STREAM, withTimeout(5.seconds) { scope.closeErrorCode.await() })
        }

    /**
     * **The same for the peer's QPACK decoder stream (#530).** §4.2 names *both* instruction streams, and
     * this one is what acknowledges our encoder's insertions — closing it strands every dynamic entry we
     * ever insert as un-acknowledged, so the encoder can never evict and eventually refuses to insert.
     */
    @Test
    fun aPeerThatClosesItsQpackDecoderStreamEndsTheConnection() =
        runTest {
            lateinit var connection: Http3Connection
            coroutineScope {
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        liveStreams(
                            peerStreamThatFins(
                                id = 11,
                                prefix =
                                    listOf(Http3StreamType.QPACK_DECODER.toInt()) +
                                        decoderBytes(QpackDecoderInstruction.StreamCancellation(QuicStreamId(0))),
                            ),
                        ),
                    )
                connection = Http3Connection.bootstrap(scope, TransportConfig())
            }

            assertEquals(
                Http3Violation.ClosedCriticalStream(CriticalStreamType.QPACK_DECODER),
                connection.connectionError?.violation,
                "the peer FINed its QPACK decoder stream while the connection was live — RFC 9204 §4.2 makes that " +
                    "a connection error of type H3_CLOSED_CRITICAL_STREAM (#530)",
            )
        }

    /**
     * **A clean connection shutdown is not a closed critical stream (#530's control).**
     *
     * The escalation above must not fire on the ordinary end of a connection, and it very nearly would:
     * `QuicheDriver` answers a read parked on a stream of a connection that has gone away with
     * `ReadResult.End` — the same value a peer's FIN produces (its `StreamRecvResult.ConnectionGone` arm;
     * a typed connection-gone read result needs `ReadResult` to gain a case, DitchOoM/buffer#376). So
     * end-of-stream alone cannot say which happened, and a fix reading it as the peer's FIN would report
     * every clean close as a protocol violation the peer never committed.
     *
     * [QuicScope.streams] is what tells them apart: it "completes when the connection closes". Here the
     * peer's control and QPACK encoder streams end *after* that flow has completed — the shape of a
     * connection going away — and the connection must record nothing.
     */
    @Test
    fun aCleanShutdownEndsTheCriticalStreamsWithoutAViolation() =
        runTest {
            lateinit var connection: Http3Connection
            lateinit var scope: FakeQuicScope
            coroutineScope {
                scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming =
                            listOf(
                                peerControlStream(clientSettings()),
                                peerStreamThatFins(
                                    id = 7,
                                    prefix =
                                        listOf(Http3StreamType.QPACK_ENCODER.toInt()) +
                                            encoderBytes(QpackEncoderInstruction.SetCapacity(0)),
                                ),
                            ),
                    )
                connection = Http3Connection.bootstrap(scope, TransportConfig())
            }

            assertNull(
                connection.connectionError,
                "the connection closed: its peer-stream flow completed, and the reads parked on the control and " +
                    "QPACK streams ended with it. That is a clean shutdown, not the peer closing a critical " +
                    "stream — reporting H3_CLOSED_CRITICAL_STREAM here would blame the peer for our own close (#530)",
            )
            assertFalse(
                scope.closeErrorCode.isCompleted,
                "and no CONNECTION_CLOSE carrying an HTTP/3 error code may be sent for an ordinary shutdown",
            )
        }

    /** Encodes one QPACK decoder instruction to the bytes a peer would put on the wire. */
    private fun decoderBytes(instruction: QpackDecoderInstruction): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        QpackDecoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    // --- #496: the WebTransport mux owns the processor and stream the router handed it ------------

    /**
     * **A WebTransport bidirectional stream that stalls inside its Session ID leaks nothing (#496).**
     *
     * `route()` peeks the `0x41` signal and hands the stream *and its `StreamProcessor`* to
     * `WebTransportMux.acceptIncomingBidi`, flagging `handlerOwnsStream` first so its own `finally`
     * releases neither — the mux's contract is "always consumes the processor". But every release in
     * the mux sat on the success path: the Session ID read expiring its deadline skipped them all, so
     * whatever the processor was still buffering never went back to the pool. Here that is the first
     * byte of a two-byte Session ID varint — the stall splits the varint — and on QUIC it is a pool leaf
     * per stalled stream.
     *
     * The witness is the pool's own accounting, taken outside the mux: the peer stream's chunk is
     * counted out of [BufferPool] before the connection exists, and [outstanding] must be back to zero
     * once the deadline has expired. The stream is also reset for the peer — on the client the router's
     * #477 arm already did that, so the reset is not what this test is for — and the connection survives.
     */
    @Test
    fun aWebTransportBidiStreamStallingInsideItsSessionIdReleasesTheProcessor() =
        runTest {
            val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
            // Signal 0x41 (varint 0x40 0x41), then 0x40 — the first byte of a two-byte Session ID — then silence.
            val peerBidi = StalledStream(prefix = listOf(0x40, 0x41, 0x40), chunks = pool)
            assertEquals(1, pool.outstanding(), "sanity: the peer stream's chunk is the one buffer out of the pool")
            val response = RecordingByteStream(listOf(dataChunk(okResponseBytes()), ReadResult.End))
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi), peerControlStream(clientSettings())),
                    bidi = ArrayDeque(listOf(QuicByteStream(QuicStreamId(0), response))),
                )

            // The connection adopts `pool` as its own (BufferPool never nests a pool inside a pool), so the
            // router's processors draw from — and must give back to — the pool this test can count.
            val connection =
                Http3Connection.bootstrap(scope, TransportConfig(bufferFactory = pool), webTransport = WebTransportOptions())
            connection.peerSettings()
            testScheduler.advanceUntilIdle() // runs the Session ID read out past its 15s deadline

            assertEquals(
                0,
                pool.outstanding(),
                "peer bidi stream 1 sent 0x41 and half a Session ID, then nothing past the 15s read deadline. " +
                    "The processor the router handed the WebTransport mux was still holding that half, and " +
                    "the mux released it on no path but success (#496): a buffer is still out of the pool. " +
                    "disposition=${peerBidi.disposition}, connectionError=${connection.connectionError}",
            )
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerBidi.disposition,
                "the stalled stream must be reset for the peer with H3_REQUEST_CANCELLED (RFC 9114 §4.1.1)",
            )
            assertNull(connection.connectionError, "one stalled WebTransport stream must not become a connection error")
            val ok = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
            assertEquals(200, ok.status, "the connection must still serve a request after abandoning the stalled stream")
            ok.close()
        }

    /**
     * **A peer that resets a WebTransport bidirectional stream mid-Session-ID leaks nothing either, and
     * our half of the stream is disposed of (#496).**
     *
     * The other exit from the same read: the QUIC leaf throws [QuicStreamException] when the peer sends
     * RESET_STREAM. `route()` swallows that as stream-scoped and — the stream being the mux's — touches
     * nothing, so the processor's buffered byte leaked exactly as on the deadline, and our send half of
     * the bidirectional stream stayed open: a peer's RESET_STREAM covers only the direction it sends on.
     */
    @Test
    fun aPeerResettingAWebTransportBidiStreamMidSessionIdReleasesTheProcessor() =
        runTest {
            val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
            val peerReset = QuicStreamException(1, QuicStreamAbort.ResetStream(QuicAppErrorCode(0)), "peer sent RESET_STREAM")
            val peerBidi = StalledStream(prefix = listOf(0x40, 0x41, 0x40), chunks = pool, then = ThenPeer.Aborts(peerReset))
            assertEquals(1, pool.outstanding(), "sanity: the peer stream's chunk is the one buffer out of the pool")
            val scope =
                FakeQuicScope(
                    this,
                    ClientStreams().outgoing(),
                    incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi), peerControlStream(clientSettings())),
                )

            val connection =
                Http3Connection.bootstrap(scope, TransportConfig(bufferFactory = pool), webTransport = WebTransportOptions())
            connection.peerSettings()
            testScheduler.advanceUntilIdle()

            assertEquals(
                0,
                pool.outstanding(),
                "the peer reset bidi stream 1 while the WebTransport mux was reading its Session ID; the " +
                    "processor's buffered byte was never released (#496). disposition=${peerBidi.disposition}",
            )
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerBidi.disposition,
                "our send half of the stream must be reset too; Open means route() swallowed the peer's " +
                    "reset and the mux never disposed of the stream it owned",
            )
            assertNull(connection.connectionError, "one peer-reset WebTransport stream must not become a connection error")
        }

    // --- tests --------------------------------------------------------------

    @Test
    fun bootstrap_opensThreeUniStreams_andWritesControlPrefixAndSettings() =
        runTest {
            coroutineScope {
                val client = ClientStreams()
                val scope = FakeQuicScope(this, client.outgoing(), incoming = emptyList())

                Http3Connection.bootstrap(scope, TransportConfig())

                assertEquals(0, scope.remainingUniStreams, "bootstrap should open exactly three uni streams")
                // The client now advertises a usable QPACK dynamic table (capacity 4096, 100 blocked streams).
                val advertised =
                    Http3Frame.Settings(
                        listOf(
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096L),
                            Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 100L),
                        ),
                    )
                assertEquals(
                    listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(advertised),
                    client.control.written,
                    "control stream = type prefix 0x00 then the client SETTINGS frame",
                )
                assertEquals(listOf(Http3StreamType.QPACK_ENCODER.toInt()), client.qpackEncoder.written)
                assertEquals(listOf(Http3StreamType.QPACK_DECODER.toInt()), client.qpackDecoder.written)
            }
        }

    @Test
    fun peerSettings_resolvesFromPeerControlStream() =
        runTest {
            coroutineScope {
                val peerSettings =
                    Http3Frame.Settings(
                        listOf(
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                            Http3Setting(Http3SettingId.MAX_FIELD_SECTION_SIZE, 16384L),
                            Http3Setting(Http3SettingId.ENABLE_CONNECT_PROTOCOL, 1L),
                        ),
                    )
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), listOf(peerControlStream(peerSettings)))

                val settings = Http3Connection.bootstrap(scope, TransportConfig()).peerSettings()

                assertEquals(0L, settings.qpackMaxTableCapacity)
                assertEquals(16384L, settings.maxFieldSectionSize)
                assertTrue(settings.enableConnectProtocol, "ENABLE_CONNECT_PROTOCOL=1 should parse as true")
            }
        }

    @Test
    fun peerSettings_resolvesWithQpackAndPushStreamsPresent() =
        runTest {
            coroutineScope {
                // Peer QPACK encoder (id 7) carrying one benign instruction (Set Dynamic Table Capacity 0
                // = 0x20), then drained, and a push stream (id 11) with push ENABLED (maxPushId = 8). The
                // push id (1) is within the limit, so the push stream is accepted and processed
                // concurrently; its truncated body just fails that one push. Neither stream blocks the peer
                // control stream from resolving SETTINGS. (A *malformed* encoder instruction would instead
                // be a connection error of type QPACK_ENCODER_STREAM_ERROR — covered in the corpus.)
                val peerQpackEnc =
                    QuicByteStream(
                        QuicStreamId(7),
                        RecordingByteStream(
                            listOf(dataChunk(listOf(Http3StreamType.QPACK_ENCODER.toInt(), 0x20)), ReadResult.End),
                        ),
                    )
                val peerPush =
                    QuicByteStream(
                        QuicStreamId(11),
                        RecordingByteStream(listOf(dataChunk(listOf(Http3StreamType.PUSH.toInt(), 0x01, 0x02)), ReadResult.End)),
                    )
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming = listOf(peerQpackEnc, peerPush, peerControlStream(clientSettings())),
                    )

                val connection = Http3Connection.bootstrap(scope, TransportConfig(), maxPushId = 8)
                val settings = connection.peerSettings()

                assertEquals(0L, settings.qpackBlockedStreams)
                assertTrue(peerQpackEnc.isOpen.not(), "peer QPACK stream should be drained then closed")
                assertNull(connection.connectionError, "a within-limit push stream must not abort the connection")
            }
        }

    @Test
    fun pushStreamWhenPushDisabled_abortsConnectionWithIdError() =
        runTest {
            coroutineScope {
                // A server push when the client never sent MAX_PUSH_ID (push disabled, the default) is a
                // connection error of type H3_ID_ERROR (RFC 9114 §4.6).
                val peerPush =
                    QuicByteStream(
                        QuicStreamId(11),
                        RecordingByteStream(listOf(dataChunk(listOf(Http3StreamType.PUSH.toInt(), 0x00)), ReadResult.End)),
                    )
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming = listOf(peerControlStream(clientSettings()), peerPush),
                    )

                val connection = Http3Connection.bootstrap(scope, TransportConfig()) // push disabled
                val error = connection.awaitConnectionError()
                assertEquals(Http3ErrorCode.ID_ERROR, error.errorCode, "push when disabled ⇒ H3_ID_ERROR")
            }
        }

    @Test
    fun peerSettings_firstControlFrameNotSettings_throws() =
        runTest {
            coroutineScope {
                // Control stream whose first frame is DATA — a protocol violation.
                val data =
                    Http3Frame.Data(
                        BufferFactory.Default.allocate(1).also {
                            it.writeByte(0x41)
                            it.resetForRead()
                        },
                    )
                val controlBytes = listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(data)
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = assertFailsWith<Http3StreamException> { connection.peerSettings() }
                assertEquals(Http3ErrorCode.MISSING_SETTINGS, e.errorCode, "first non-SETTINGS control frame ⇒ H3_MISSING_SETTINGS")
            }
        }

    @Test
    fun peerSettings_connectionClosesBeforeSettings_throws() =
        runTest {
            coroutineScope {
                // No incoming streams → the streams flow completes before any SETTINGS arrive.
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = emptyList())
                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                assertFailsWith<Http3StreamException> { connection.peerSettings() }
            }
        }

    @Test
    fun router_ignoresPeerBidirectionalStream() =
        runTest {
            coroutineScope {
                // id 1 = server-initiated bidirectional — closed, not parsed.
                val peerBidi = RecordingByteStream(listOf(dataChunk(listOf(0x00, 0x00)), ReadResult.End))
                val scope =
                    FakeQuicScope(
                        this,
                        ClientStreams().outgoing(),
                        incoming = listOf(QuicByteStream(QuicStreamId(1), peerBidi), peerControlStream(clientSettings())),
                    )

                Http3Connection.bootstrap(scope, TransportConfig()).peerSettings() // resolves despite the bidi stream

                assertTrue(peerBidi.closed, "a peer bidirectional stream should be closed, not parsed")
            }
        }

    /** Encode one QPACK encoder-stream instruction (RFC 9204 §4.3) to its wire bytes. */
    private fun encoderInstructionBytes(instruction: QpackEncoderInstruction): List<Int> {
        val buf = BufferFactory.Default.allocate(64)
        QpackEncoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    /**
     * A decoder uni stream whose first write (bootstrap's QPACK_DECODER type prefix) succeeds, but every
     * later write throws [QuicCloseException] — modelling the connection tearing down underneath an
     * in-flight decoder-stream write.
     */
    private class ClosedAfterFirstWriteByteStream :
        ByteStream,
        com.ditchoom.buffer.flow.Resettable {
        var writeAttempts = 0
            private set
        private var closed = false

        override val isOpen: Boolean get() = !closed
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            writeAttempts++
            if (writeAttempts > 1) {
                throw QuicCloseException(QuicCloseReason.Unspecified, "connection closed")
            }
            val n = buffer.remaining()
            repeat(n) { buffer.readByte() }
            return BytesWritten(n)
        }

        override suspend fun close() {
            closed = true
        }

        override suspend fun reset(errorCode: Long) {
            closed = true
        }
    }

    @Test
    fun decoderInstructionWrite_racingConnectionClose_isSwallowed_notPropagated() =
        runTest {
            // RFC 9204 §4.4.3: a peer encoder insert drives our decoder to emit an Insert Count Increment
            // on our decoder stream. Here that decoder-stream write lands as the connection tears down, so
            // the driver throws QuicCloseException. The ack/ICI is moot once the connection is gone, so
            // writeDecoderInstruction must swallow it — it must NOT escape route() and cancel the router
            // (the productionServerRole_dynamicQpackRoundTrip CI flake). The peer encoder stream first sets
            // a non-zero table capacity, then inserts an entry that triggers the increment.
            val encoderBytes =
                encoderInstructionBytes(QpackEncoderInstruction.SetCapacity(4096)) +
                    encoderInstructionBytes(QpackEncoderInstruction.InsertWithLiteralName("x-token", "v"))
            val peerEncoder =
                QuicByteStream(
                    QuicStreamId(7), // server-initiated unidirectional
                    RecordingByteStream(
                        listOf(
                            dataChunk(listOf(Http3StreamType.QPACK_ENCODER.toInt()) + encoderBytes),
                            ReadResult.End,
                        ),
                    ),
                )
            val throwingDecoder = ClosedAfterFirstWriteByteStream()
            val outgoing =
                ArrayDeque(
                    listOf(
                        QuicByteStream(QuicStreamId(2), RecordingByteStream()),
                        QuicByteStream(QuicStreamId(6), RecordingByteStream()),
                        QuicByteStream(QuicStreamId(10), throwingDecoder),
                    ),
                )

            // The whole connection lifecycle runs inside this scope; it joins the router (incoming is finite)
            // before returning, so a QuicCloseException escaping route() would surface here as a test failure.
            val connection =
                coroutineScope {
                    val scope =
                        FakeQuicScope(
                            this,
                            outgoing,
                            incoming = listOf(peerEncoder, peerControlStream(clientSettings())),
                        )
                    val conn = Http3Connection.bootstrap(scope, TransportConfig())
                    withTimeout(5.seconds) { conn.peerSettings() }
                    conn
                }

            assertTrue(throwingDecoder.writeAttempts >= 2, "the Insert Count Increment write must have been attempted")
            assertNull(connection.connectionError, "a moot decoder ack racing close must not become a connection error")
        }

    // --- critical stream creation (RFC 9114 §6.2 / RFC 9204 §4.2) -----------

    /** A peer uni stream carrying only its [type] prefix, then end-of-stream. */
    private fun peerUniStream(
        id: Long,
        type: Long,
    ): QuicByteStream =
        QuicByteStream(
            QuicStreamId(id),
            RecordingByteStream(listOf(dataChunk(listOf(type.toInt())), ReadResult.End)),
        )

    /** Runs a connection whose peer opens [incoming], joins the router, and returns the connection. */
    private suspend fun connectionAfterRouting(incoming: List<QuicByteStream>): Http3Connection =
        coroutineScope {
            val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = incoming)
            Http3Connection.bootstrap(scope, TransportConfig())
        }

    @Test
    fun secondPeerQpackEncoderStream_isAStreamCreationConnectionError() =
        runTest {
            // RFC 9204 §4.2: one QPACK encoder stream per peer; a second instance MUST be a connection
            // error of type H3_STREAM_CREATION_ERROR. Not bookkeeping — each critical stream is read by
            // its own router coroutine, so a second encoder stream puts two of them into
            // QpackDecoder.applyEncoderInstruction, which is written for a single feeder: the insert
            // count one captured under the table lock can be overtaken before it reports under the
            // acknowledgment lock.
            val connection =
                connectionAfterRouting(
                    listOf(
                        peerUniStream(id = 7, type = Http3StreamType.QPACK_ENCODER),
                        peerUniStream(id = 11, type = Http3StreamType.QPACK_ENCODER),
                    ),
                )

            val error = connection.connectionError
            assertEquals(Http3Violation.DuplicateCriticalStream(CriticalStreamType.QPACK_ENCODER), error?.violation)
            assertEquals(Http3ErrorCode.STREAM_CREATION_ERROR, error?.errorCode)
        }

    @Test
    fun secondPeerControlStream_isAStreamCreationConnectionError() =
        runTest {
            // RFC 9114 §6.2, same rule for the control stream. The first one carries valid SETTINGS, so
            // the abort can only be the duplicate — not a malformed-control-stream error wearing its name.
            val connection =
                connectionAfterRouting(
                    listOf(
                        peerControlStream(clientSettings()),
                        peerUniStream(id = 11, type = Http3StreamType.CONTROL),
                    ),
                )

            val error = connection.connectionError
            assertEquals(Http3Violation.DuplicateCriticalStream(CriticalStreamType.CONTROL), error?.violation)
            assertEquals(Http3ErrorCode.STREAM_CREATION_ERROR, error?.errorCode)
        }

    @Test
    fun repeatedReservedUniStreams_areNotACriticalStreamDuplicate() =
        runTest {
            // The other half of the rule: only the three critical types are once-per-peer. Reserved/GREASE
            // streams (§6.2.3) may repeat and are drained, so keying the duplicate check on a raw stream
            // type — rather than the closed set of critical ones — would close the connection on a
            // perfectly conformant peer.
            val connection =
                connectionAfterRouting(
                    listOf(
                        peerControlStream(clientSettings()),
                        peerUniStream(id = 11, type = 0x21),
                        peerUniStream(id = 15, type = 0x21),
                    ),
                )

            assertNull(connection.connectionError, "reserved uni streams may repeat")
        }

    // --- request/response (RFC 9114 §4) -------------------------------------

    private fun encodedFieldSection(fields: List<QpackHeaderField>): ReadBuffer {
        val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
        val buf = BufferFactory.Default.allocate(size.coerceAtLeast(1))
        QpackFieldSectionCodec.encode(buf, fields, EncodeContext.Empty)
        buf.resetForRead()
        return buf
    }

    private fun asciiBuffer(text: String): ReadBuffer {
        val buf = BufferFactory.Default.allocate(text.length.coerceAtLeast(1))
        buf.writeString(text, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    private fun bufferOf(bytes: List<Int>): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    private fun fakeScopeWithBidi(
        delegate: CoroutineScope,
        bidi: QuicByteStream,
    ): FakeQuicScope = FakeQuicScope(delegate, ClientStreams().outgoing(), incoming = emptyList(), bidi = ArrayDeque(listOf(bidi)))

    @Test
    fun request_writesRequestHeaders_andDecodesResponse() =
        runTest {
            coroutineScope {
                val responseHeaders =
                    listOf(
                        QpackHeaderField(":status", "200"),
                        QpackHeaderField("content-type", "text/plain"),
                    )
                val responseBytes =
                    frameBytes(Http3Frame.Headers(encodedFieldSection(responseHeaders))) +
                        frameBytes(Http3Frame.Data(asciiBuffer("hello")))
                val recording = RecordingByteStream(listOf(dataChunk(responseBytes), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val response = connection.request(Http3Request(method = "GET", authority = "example.com", path = "/"))

                assertEquals(200, response.status)
                assertEquals(listOf(QpackHeaderField("content-type", "text/plain")), response.headers)
                val body = response.readFullBody()
                assertEquals("hello", body.readString(body.remaining(), Charset.UTF8))
                response.close()

                // The request stream carried exactly a HEADERS frame whose field section is the
                // pseudo-headers in RFC 9114 §4.3.1 order.
                val requestFrame = HandwrittenHttp3FrameCodec.decode(bufferOf(recording.written), DecodeContext.Empty)
                assertTrue(requestFrame is Http3Frame.Headers)
                assertEquals(
                    listOf(
                        QpackHeaderField(":method", "GET"),
                        QpackHeaderField(":scheme", "https"),
                        QpackHeaderField(":authority", "example.com"),
                        QpackHeaderField(":path", "/"),
                    ),
                    QpackFieldSectionCodec.decode((requestFrame as Http3Frame.Headers).encodedFieldSection, DecodeContext.Empty),
                )
            }
        }

    @Test
    fun request_withBody_sendsHeadersThenDataFrame() =
        runTest {
            coroutineScope {
                val recording =
                    RecordingByteStream(
                        listOf(
                            dataChunk(frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "204")))))),
                            ReadResult.End,
                        ),
                    )
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val response =
                    connection.request(
                        Http3Request(method = "POST", authority = "h.test", path = "/upload", body = asciiBuffer("body!")),
                    )

                assertEquals(204, response.status)
                assertEquals(emptyList<QpackHeaderField>(), response.headers)
                response.close()

                // Request stream = HEADERS frame, then a DATA frame carrying the body.
                val written = bufferOf(recording.written)
                assertTrue(HandwrittenHttp3FrameCodec.decode(written, DecodeContext.Empty) is Http3Frame.Headers)
                val dataFrame = HandwrittenHttp3FrameCodec.decode(written, DecodeContext.Empty)
                assertTrue(dataFrame is Http3Frame.Data)
                assertEquals("body!", (dataFrame as Http3Frame.Data).payload.let { it.readString(it.remaining(), Charset.UTF8) })
            }
        }

    @Test
    fun request_responseMissingStatus_throws() =
        runTest {
            coroutineScope {
                val recording =
                    RecordingByteStream(
                        listOf(
                            dataChunk(
                                frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField("content-type", "text/plain"))))),
                            ),
                            ReadResult.End,
                        ),
                    )
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                // A missing :status is a malformed *message* (RFC 9114 §4.1.2): stream-scoped, so the
                // request stream is reset with H3_MESSAGE_ERROR — not a connection error.
                val e =
                    assertFailsWith<Http3StreamException> {
                        connection.request(Http3Request(method = "GET", authority = "example.com", path = "/"))
                    }
                assertEquals(Http3ErrorCode.MESSAGE_ERROR, e.errorCode)
                assertEquals(Http3ErrorCode.MESSAGE_ERROR, recording.resetCode, "malformed message ⇒ stream reset, not connection close")
                assertEquals(null, connection.connectionError, "a stream-scoped error must not abort the connection")
            }
        }

    @Test
    fun request_surfacesTrailers() =
        runTest {
            coroutineScope {
                // Response: HEADERS(:status 200), DATA("hi"), then a trailing HEADERS section.
                val responseBytes =
                    frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "200"))))) +
                        frameBytes(Http3Frame.Data(asciiBuffer("hi"))) +
                        frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField("x-trailer", "done")))))
                val recording = RecordingByteStream(listOf(dataChunk(responseBytes), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val response = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
                assertEquals(200, response.status)
                val body = response.readFullBody()
                assertEquals("hi", body.readString(body.remaining(), com.ditchoom.buffer.Charset.UTF8))
                assertEquals(listOf(QpackHeaderField("x-trailer", "done")), response.trailers)
                response.close()
            }
        }

    // --- GOAWAY surfacing (RFC 9114 §7.2.6) ---------------------------------

    @Test
    fun goAway_isSurfacedFromControlStream() =
        runTest {
            coroutineScope {
                // Peer control stream: SETTINGS then GOAWAY(last-stream-id = 8).
                val controlBytes =
                    listOf(Http3StreamType.CONTROL.toInt()) +
                        frameBytes(clientSettings()) +
                        frameBytes(Http3Frame.GoAway(8))
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                // Deterministic: the router reads the control stream's GOAWAY after SETTINGS and
                // updates the StateFlow; await the non-null value.
                assertEquals(8L, connection.goAway.filterNotNull().first())
            }
        }

    @Test
    fun request_streamingBody_writesHeadersThenEachDataFrame() =
        runTest {
            coroutineScope {
                val recording =
                    RecordingByteStream(
                        listOf(
                            dataChunk(frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "200")))))),
                            ReadResult.End,
                        ),
                    )
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                val response =
                    connection.request(method = "POST", authority = "h.test", path = "/upload") {
                        write(asciiBuffer("chunk-1"))
                        write(asciiBuffer("chunk-2"))
                    }
                assertEquals(200, response.status)
                response.close()

                // Request stream = HEADERS, then one DATA frame per write() call, in order.
                val written = bufferOf(recording.written)
                assertTrue(HandwrittenHttp3FrameCodec.decode(written, DecodeContext.Empty) is Http3Frame.Headers)
                val first = HandwrittenHttp3FrameCodec.decode(written, DecodeContext.Empty)
                val second = HandwrittenHttp3FrameCodec.decode(written, DecodeContext.Empty)
                assertTrue(first is Http3Frame.Data && second is Http3Frame.Data)
                assertEquals("chunk-1", (first as Http3Frame.Data).payload.let { it.readString(it.remaining(), Charset.UTF8) })
                assertEquals("chunk-2", (second as Http3Frame.Data).payload.let { it.readString(it.remaining(), Charset.UTF8) })
            }
        }

    // --- RFC 9114 §8.1 frame/stream-validation enforcement ------------------

    @Test
    fun request_dataFrameBeforeHeaders_throwsFrameUnexpected() =
        runTest {
            coroutineScope {
                // Response stream's first frame is DATA — invalid frame sequence (RFC 9114 §4.1).
                val recording = RecordingByteStream(listOf(dataChunk(frameBytes(Http3Frame.Data(asciiBuffer("oops")))), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                val e =
                    assertFailsWith<Http3StreamException> {
                        connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
                    }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
            }
        }

    @Test
    fun request_settingsOnRequestStream_throwsFrameUnexpected() =
        runTest {
            coroutineScope {
                // SETTINGS is a control-stream frame; on a request stream it's H3_FRAME_UNEXPECTED.
                val recording = RecordingByteStream(listOf(dataChunk(frameBytes(clientSettings())), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                val e =
                    assertFailsWith<Http3StreamException> {
                        connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
                    }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
            }
        }

    @Test
    fun response_unexpectedFrameInBody_throwsFrameUnexpected() =
        runTest {
            coroutineScope {
                // Valid HEADERS, then a stray SETTINGS in the body — H3_FRAME_UNEXPECTED on read.
                val responseBytes =
                    frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "200"))))) +
                        frameBytes(clientSettings())
                val recording = RecordingByteStream(listOf(dataChunk(responseBytes), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                val response = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
                assertEquals(200, response.status)
                val e = assertFailsWith<Http3StreamException> { response.readFullBody() }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
                response.close()
            }
        }

    @Test
    fun control_duplicateSettings_abortsConnectionWithFrameUnexpected() =
        runTest {
            coroutineScope {
                // Control stream: SETTINGS then a second SETTINGS — H3_FRAME_UNEXPECTED (RFC 9114 §7.2.4).
                val controlBytes =
                    listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(clientSettings()) + frameBytes(clientSettings())
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
            }
        }

    @Test
    fun control_dataFrameOnControlStream_abortsConnectionWithFrameUnexpected() =
        runTest {
            coroutineScope {
                // Control stream: SETTINGS then a DATA frame — DATA is never valid on the control stream.
                val controlBytes =
                    listOf(Http3StreamType.CONTROL.toInt()) +
                        frameBytes(clientSettings()) +
                        frameBytes(Http3Frame.Data(asciiBuffer("x")))
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
            }
        }

    @Test
    fun response_cancel_resetsStreamWithRequestCancelled() =
        runTest {
            coroutineScope {
                // Caller aborts the response instead of draining it — the request stream is reset
                // with H3_REQUEST_CANCELLED (RFC 9114 §4.1) rather than gracefully closed.
                val responseBytes =
                    frameBytes(Http3Frame.Headers(encodedFieldSection(listOf(QpackHeaderField(":status", "200"))))) +
                        frameBytes(Http3Frame.Data(asciiBuffer("partial")))
                val recording = RecordingByteStream(listOf(dataChunk(responseBytes), ReadResult.End))
                val scope = fakeScopeWithBidi(this, QuicByteStream(QuicStreamId(0), recording))
                val connection = Http3Connection.bootstrap(scope, TransportConfig())

                val response = connection.request(Http3Request(method = "GET", authority = "h.test", path = "/"))
                assertEquals(200, response.status)
                response.cancel()
                assertEquals(Http3ErrorCode.REQUEST_CANCELLED, recording.resetCode)
            }
        }

    @Test
    fun control_endsBeforeSettings_abortsWithClosedCriticalStream() =
        runTest {
            coroutineScope {
                // Control stream carries only its type prefix, then ends — the critical stream closed.
                val peerControl =
                    QuicByteStream(
                        QuicStreamId(3),
                        RecordingByteStream(listOf(dataChunk(listOf(Http3StreamType.CONTROL.toInt())), ReadResult.End)),
                    )
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.CLOSED_CRITICAL_STREAM, e.errorCode)
            }
        }

    @Test
    fun control_duplicateSettingIdentifier_abortsWithSettingsError() =
        runTest {
            coroutineScope {
                // A single SETTINGS frame repeating one identifier — H3_SETTINGS_ERROR (RFC 9114 §7.2.4.1).
                val badSettings =
                    Http3Frame.Settings(
                        listOf(
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096L),
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                        ),
                    )
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControlStream(badSettings)))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.SETTINGS_ERROR, e.errorCode)
            }
        }

    @Test
    fun control_reservedHttp2SettingIdentifier_abortsWithSettingsError() =
        runTest {
            coroutineScope {
                // A reserved HTTP/2 setting id (0x02) — receipt MUST be H3_SETTINGS_ERROR (§7.2.4.1 / §11.2.2).
                val badSettings = Http3Frame.Settings(listOf(Http3Setting(0x02L, 1L)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControlStream(badSettings)))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.SETTINGS_ERROR, e.errorCode)
            }
        }

    @Test
    fun control_reservedHttp2FrameType_abortsWithFrameUnexpected() =
        runTest {
            coroutineScope {
                // Control stream: SETTINGS then a reserved HTTP/2 frame type 0x02 (PRIORITY) with empty
                // body — receipt MUST be H3_FRAME_UNEXPECTED (RFC 9114 §7.1), not silently ignored.
                val controlBytes =
                    listOf(Http3StreamType.CONTROL.toInt()) + frameBytes(clientSettings()) + listOf(0x02, 0x00)
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val e = withTimeout(5.seconds) { connection.awaitConnectionError() }
                assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, e.errorCode)
            }
        }

    @Test
    fun control_greaseFrameType_isIgnored_andSettingsResolve() =
        runTest {
            coroutineScope {
                // Control stream: SETTINGS then a GREASE frame (type 0x21) — ignored (RFC 9114 §9), so
                // peerSettings still resolves and no connection error is raised.
                val controlBytes =
                    listOf(Http3StreamType.CONTROL.toInt()) +
                        frameBytes(clientSettings()) +
                        listOf(0x21, 0x01, 0xAA) // GREASE frame type 0x21, 1-byte payload
                val peerControl =
                    QuicByteStream(QuicStreamId(3), RecordingByteStream(listOf(dataChunk(controlBytes), ReadResult.End)))
                val scope = FakeQuicScope(this, ClientStreams().outgoing(), incoming = listOf(peerControl))

                val connection = Http3Connection.bootstrap(scope, TransportConfig())
                val settings = withTimeout(5.seconds) { connection.peerSettings() }
                assertEquals(0L, settings.qpackMaxTableCapacity, "GREASE frame ignored; SETTINGS parsed normally")
            }
        }
}
