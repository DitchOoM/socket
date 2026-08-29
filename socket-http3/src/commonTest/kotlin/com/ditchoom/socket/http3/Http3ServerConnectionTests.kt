package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
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
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 2 (STEP 1) of the H3/QPACK conformance plan: a **deterministic, error-paths-first** corpus over
 * the **server** [Http3ServerConnection], the production counterpart to the client cases in
 * [Http3ConnectionTests]. Each test scripts a peer (client) control or request stream over a
 * [FakeQuicScope] and asserts the EXACT RFC 9114 §8.1 outcome the server puts on the wire — a typed
 * [Http3ErrorCode] via [QuicScope.closeWithError] (errors stay typed, never strings) — or that a
 * GREASE/legal construct is accepted with no connection error.
 *
 * RFC map: control stream framing §6.2.1 / §7.2.4; SETTINGS rules §7.2.4.1; reserved-HTTP2 frames §7.1 /
 * §11.2.1; request-stream framing §4.1; GREASE/unknown-is-ignored §9.
 */
class Http3ServerConnectionTests {
    // --- byte helpers -------------------------------------------------------

    private fun frameBytes(frame: Http3Frame): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        HandwrittenHttp3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    private fun settings(vararg entries: Http3Setting): Http3Frame.Settings = Http3Frame.Settings(entries.toList())

    private fun clientSettings(): Http3Frame.Settings =
        settings(
            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
            Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
        )

    private fun dataChunk(bytes: List<Int>): ReadResult {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return ReadResult.Data(buf)
    }

    private fun asciiBuffer(text: String): ReadBuffer {
        val buf = BufferFactory.Default.allocate(text.length.coerceAtLeast(1))
        buf.writeString(text, Charset.UTF8)
        buf.resetForRead()
        return buf
    }

    private fun encodedFieldSection(fields: List<QpackHeaderField>): ReadBuffer {
        val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
        val buf = BufferFactory.Default.allocate(size.coerceAtLeast(1))
        QpackFieldSectionCodec.encode(buf, fields, EncodeContext.Empty)
        buf.resetForRead()
        return buf
    }

    private fun rawBuffer(bytes: List<Int>): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    /** A valid GET request HEADERS frame (pseudo-headers in RFC 9114 §4.3.1 order). */
    private fun requestHeadersFrame(): Http3Frame.Headers =
        Http3Frame.Headers(
            encodedFieldSection(
                listOf(
                    QpackHeaderField(":method", "GET"),
                    QpackHeaderField(":scheme", "https"),
                    QpackHeaderField(":authority", "h.test"),
                    QpackHeaderField(":path", "/"),
                ),
            ),
        )

    /** A peer (client-initiated, unidirectional id 2) control stream: 0x00 prefix then [trailing] bytes. */
    private fun clientControl(trailing: List<Int>): QuicByteStream =
        QuicByteStream(
            QuicStreamId(2),
            RecordingByteStream(listOf(dataChunk(listOf(Http3StreamType.CONTROL.toInt()) + trailing), ReadResult.End)),
        )

    /** A peer (client-initiated, unidirectional) uni stream carrying only its [type] prefix, then FIN. */
    private fun clientUniStream(
        id: Long,
        type: Long,
    ): QuicByteStream =
        QuicByteStream(
            QuicStreamId(id),
            RecordingByteStream(listOf(dataChunk(listOf(type.toInt())), ReadResult.End)),
        )

    /** A peer (client-initiated, bidirectional id 0) request stream carrying [bytes] then FIN. */
    private fun clientRequest(bytes: List<Int>): QuicByteStream =
        QuicByteStream(QuicStreamId(0), RecordingByteStream(listOf(dataChunk(bytes), ReadResult.End)))

    // --- test doubles -------------------------------------------------------

    /** A [ByteStream] that records writes and replays a scripted read sequence. */
    private class RecordingByteStream(
        readScript: List<ReadResult> = emptyList(),
    ) : ByteStream,
        com.ditchoom.buffer.flow.Resettable {
        private val reads = ArrayDeque(readScript)
        var closed = false
            private set
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
            repeat(n) { buffer.readByte() }
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
     * A [QuicScope] test double driving [Http3ServerConnection.serve]: hands out the server's own uni
     * streams (control + optional QPACK) from [outgoing], replays the peer streams from [incoming], and
     * records the application error code of a [closeWithError] (the server's connection abort) in
     * [closeErrorCode].
     */
    private class FakeQuicScope(
        delegate: CoroutineScope,
        private val outgoing: ArrayDeque<QuicByteStream>,
        private val incoming: List<QuicByteStream>,
    ) : QuicScope,
        CoroutineScope by delegate {
        override val bufferFactory: BufferFactory = BufferFactory.Default
        val closeErrorCode = CompletableDeferred<Long>()

        override suspend fun openUniStream(): QuicByteStream = outgoing.removeFirst()

        override suspend fun openStream(): QuicByteStream = throw UnsupportedOperationException()

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException()

        override fun streams(): Flow<QuicByteStream> = incoming.asFlow()

        override suspend fun closeWithError(errorCode: Long) {
            closeErrorCode.complete(errorCode)
        }
    }

    /** The server's own outgoing uni streams (control, and QPACK enc/dec when dynamic) — server-initiated. */
    private fun serverOutgoing(): ArrayDeque<QuicByteStream> =
        ArrayDeque(
            listOf(
                QuicByteStream(QuicStreamId(3), RecordingByteStream()),
                QuicByteStream(QuicStreamId(7), RecordingByteStream()),
                QuicByteStream(QuicStreamId(11), RecordingByteStream()),
            ),
        )

    /**
     * Run [Http3ServerConnection.serve] over the scripted [incoming] streams to completion (every test
     * double's reads are finite, so the server's stream router and its per-stream handler coroutines all
     * finish on their own and the enclosing [coroutineScope] joins them), then run [assertions] against
     * the scope — at which point any connection abort has already been recorded.
     */
    private fun runServer(
        incoming: List<QuicByteStream>,
        qpackCapacity: Long = 0,
        onRequest: suspend Http3ServerExchange.() -> Unit = { response.send(200) },
        config: TransportConfig = TransportConfig(),
        webTransport: WebTransportOptions? = null,
        assertions: suspend (FakeQuicScope) -> Unit,
    ): TestResult =
        runTest {
            lateinit var scope: FakeQuicScope
            coroutineScope {
                scope = FakeQuicScope(this, serverOutgoing(), incoming)
                Http3ServerConnection(scope, config, qpackCapacity, onRequest, webTransport).serve()
            }
            assertions(scope)
        }

    private suspend fun FakeQuicScope.awaitCloseCode(): Long = withTimeout(5.seconds) { closeErrorCode.await() }

    // === Control stream framing (RFC 9114 §6.2.1 / §7.2.4) ===================

    @Test
    fun control_firstFrameNotSettings_isMissingSettings(): TestResult =
        // First control frame is GOAWAY, not SETTINGS ⇒ H3_MISSING_SETTINGS (§6.2.1).
        runServer(listOf(clientControl(frameBytes(Http3Frame.GoAway(0))))) { scope ->
            assertEquals(Http3ErrorCode.MISSING_SETTINGS, scope.awaitCloseCode())
        }

    @Test
    fun control_endsBeforeSettings_isClosedCriticalStream(): TestResult =
        // The control stream carries only its type prefix, then FINs ⇒ H3_CLOSED_CRITICAL_STREAM (§6.2.1).
        runServer(listOf(clientControl(emptyList()))) { scope ->
            assertEquals(Http3ErrorCode.CLOSED_CRITICAL_STREAM, scope.awaitCloseCode())
        }

    @Test
    fun control_validSettings_isAccepted(): TestResult =
        // A well-formed SETTINGS-first control stream is accepted — no connection error.
        runServer(listOf(clientControl(frameBytes(clientSettings())))) { scope ->
            assertFalse(scope.closeErrorCode.isCompleted, "a valid control stream must not abort the connection")
        }

    // === Critical stream creation (RFC 9114 §6.2 / RFC 9204 §4.2) ===========

    @Test
    fun control_secondInstance_isStreamCreationError(): TestResult =
        // §6.2: one control stream per peer. The first carries valid SETTINGS, so the abort can only be
        // the duplicate rather than a framing error wearing its name.
        runServer(
            listOf(
                clientControl(frameBytes(clientSettings())),
                clientUniStream(id = 6, type = Http3StreamType.CONTROL),
            ),
        ) { scope ->
            assertEquals(Http3ErrorCode.STREAM_CREATION_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun qpackEncoderStream_secondInstance_isStreamCreationError(): TestResult =
        // RFC 9204 §4.2: one QPACK encoder stream per peer. The server routes each uni stream in its own
        // coroutine, so a second one would put two of them into QpackDecoder.applyEncoderInstruction —
        // which captures the table's insert count under one lock and reports it under another, safe only
        // while a single coroutine feeds it.
        runServer(
            listOf(
                clientControl(frameBytes(clientSettings())),
                clientUniStream(id = 6, type = Http3StreamType.QPACK_ENCODER),
                clientUniStream(id = 10, type = Http3StreamType.QPACK_ENCODER),
            ),
            qpackCapacity = 4096,
        ) { scope ->
            assertEquals(Http3ErrorCode.STREAM_CREATION_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun qpackDecoderStream_secondInstance_isStreamCreationError(): TestResult =
        runServer(
            listOf(
                clientControl(frameBytes(clientSettings())),
                clientUniStream(id = 6, type = Http3StreamType.QPACK_DECODER),
                clientUniStream(id = 10, type = Http3StreamType.QPACK_DECODER),
            ),
            qpackCapacity = 4096,
        ) { scope ->
            assertEquals(Http3ErrorCode.STREAM_CREATION_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun oneOfEachCriticalStream_isAccepted(): TestResult =
        // The other half of the rule, and the regression this guard could plausibly cause: a conformant
        // peer opens exactly one of each, and all three must still be read rather than closed on.
        runServer(
            listOf(
                clientControl(frameBytes(clientSettings())),
                clientUniStream(id = 6, type = Http3StreamType.QPACK_ENCODER),
                clientUniStream(id = 10, type = Http3StreamType.QPACK_DECODER),
            ),
            qpackCapacity = 4096,
        ) { scope ->
            assertFalse(scope.closeErrorCode.isCompleted, "one of each critical stream is exactly what a peer must open")
        }

    @Test
    fun repeatedReservedUniStreams_areNotACriticalStreamDuplicate(): TestResult =
        // Only the three critical types are once-per-peer; reserved/GREASE uni streams (§6.2.3) repeat
        // legitimately and are drained. Keying the check on a raw stream type would close on a
        // conformant peer.
        runServer(
            listOf(
                clientControl(frameBytes(clientSettings())),
                clientUniStream(id = 6, type = 0x21),
                clientUniStream(id = 10, type = 0x21),
            ),
        ) { scope ->
            assertFalse(scope.closeErrorCode.isCompleted, "reserved uni streams may repeat")
        }

    @Test
    fun qpackDecoderStreamWithNoEncoderYet_isReadRatherThanSpun(): TestResult =
        runTest {
            // The decoder-stream loop used to read `serverEncoder?.process(reader.next() ?: break)`. A safe
            // call does not evaluate its argument when the receiver is null, so with no encoder the loop
            // read nothing, suspended nowhere, and spun at 100% CPU for the life of the connection —
            // measured at 641s of CPU on one coroutine before this was found. A *conformant* client gets
            // there: RFC 9204 §4.2 has both endpoints open a decoder stream, and one advertising
            // QPACK_MAX_TABLE_CAPACITY: 0 (as `clientSettings()` does) leaves the server no encoder to
            // hand the instructions to.
            //
            // Watchdog rather than a plain assertion: an uninterruptible spin cannot be cancelled, so
            // `withTimeout` around it would wait for a body that never completes, and the sibling tests
            // covering this path would hang the job rather than fail it. Awaiting a *separate* deferred
            // fails in seconds instead. The regressed loop then leaks one busy thread for the rest of the
            // run, which is the price of failing fast — and on a single-threaded platform (JS/wasm) even
            // this degrades to a hang, because there is no second thread to notice.
            val served = CompletableDeferred<Unit>()
            val detached = CoroutineScope(Dispatchers.Default)
            detached.launch {
                // coroutineScope, because `serve()` returns as soon as the stream flow completes and does
                // NOT join the per-stream handlers it launched — the spin lives in one of those, so
                // completing on `serve()` alone would report success while a handler burned a core.
                coroutineScope {
                    val scope =
                        FakeQuicScope(
                            this,
                            serverOutgoing(),
                            listOf(
                                clientControl(frameBytes(clientSettings())),
                                clientUniStream(id = 6, type = Http3StreamType.QPACK_DECODER),
                            ),
                        )
                    Http3ServerConnection(
                        scope,
                        TransportConfig(),
                        qpackCapacity = 4096,
                        onRequest = { response.send(200) },
                    ).serve()
                }
                served.complete(Unit)
            }

            // On Dispatchers.Default, not the test dispatcher: `runTest`'s clock is virtual, so a timeout
            // measured there elapses instantly and this would report a spin on every run, bug or not.
            val finished = withContext(Dispatchers.Default) { withTimeoutOrNull(10.seconds) { served.await() } }

            detached.cancel() // best-effort: a spin ignores cancellation, which is the point of the watchdog
            assertNotNull(finished, "the decoder stream was never read to end-of-stream — the loop is spinning")
        }

    @Test
    fun control_duplicateSettingIdentifier_isSettingsError(): TestResult =
        // The same setting id twice in one SETTINGS frame ⇒ H3_SETTINGS_ERROR (§7.2.4.1).
        runServer(
            listOf(
                clientControl(
                    frameBytes(
                        settings(
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096L),
                            Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                        ),
                    ),
                ),
            ),
        ) { scope ->
            assertEquals(Http3ErrorCode.SETTINGS_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun control_reservedHttp2SettingIdentifier_isSettingsError(): TestResult =
        // A reserved HTTP/2 setting id (0x02) ⇒ H3_SETTINGS_ERROR (§7.2.4.1 / §11.2.2).
        runServer(listOf(clientControl(frameBytes(settings(Http3Setting(0x02L, 1L)))))) { scope ->
            assertEquals(Http3ErrorCode.SETTINGS_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun control_secondSettingsFrame_isFrameUnexpected(): TestResult =
        // SETTINGS may appear once, as the first frame; a second is H3_FRAME_UNEXPECTED (§7.2.4).
        runServer(listOf(clientControl(frameBytes(clientSettings()) + frameBytes(clientSettings())))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun control_pushPromiseOnControlStream_isFrameUnexpected(): TestResult =
        // PUSH_PROMISE (type 0x05) is a request-stream frame; on the control stream ⇒ H3_FRAME_UNEXPECTED
        // (§7.2.5). Raw bytes: type 0x05, Length 1, body = Push ID varint 0x00 (empty field section).
        runServer(listOf(clientControl(frameBytes(clientSettings()) + listOf(0x05, 0x01, 0x00)))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun control_dataFrameOnControlStream_isFrameUnexpected(): TestResult =
        // DATA is a request-stream frame; on the control stream ⇒ H3_FRAME_UNEXPECTED (§4.1).
        runServer(listOf(clientControl(frameBytes(clientSettings()) + frameBytes(Http3Frame.Data(asciiBuffer("x")))))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun control_reservedHttp2FrameType_isFrameUnexpected(): TestResult =
        // A reserved HTTP/2 frame type (0x02 PRIORITY, empty body) after SETTINGS ⇒ H3_FRAME_UNEXPECTED
        // (§7.1) — distinct from GREASE, which is ignored.
        runServer(listOf(clientControl(frameBytes(clientSettings()) + listOf(0x02, 0x00)))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun control_greaseFrameAfterSettings_isIgnored(): TestResult =
        // A GREASE frame type (0x21) after SETTINGS is ignored (§9) — no connection error.
        runServer(listOf(clientControl(frameBytes(clientSettings()) + listOf(0x21, 0x01, 0xAA)))) { scope ->
            assertFalse(scope.closeErrorCode.isCompleted, "a GREASE control frame must be ignored, not abort")
        }

    @Test
    fun control_maxPushIdAfterSettings_isAccepted(): TestResult =
        // MAX_PUSH_ID is client→server (§7.2.7) and legal on the control stream — assert no abort. (This
        // is a no-abort acceptance guard only; the credit-sizing effect, clientMaxPushId, is private and
        // is exercised end-to-end by the server-push loopback tests, not observable here.)
        runServer(listOf(clientControl(frameBytes(clientSettings()) + frameBytes(Http3Frame.MaxPushId(8))))) { scope ->
            assertFalse(scope.closeErrorCode.isCompleted, "MAX_PUSH_ID from the client is legal on its control stream")
        }

    // === Request stream framing (RFC 9114 §4.1 / §7.1 / §9) =================

    @Test
    fun request_reservedHttp2FrameBeforeHeaders_isFrameUnexpected(): TestResult =
        // A reserved HTTP/2 frame (0x02) as the request's first frame ⇒ H3_FRAME_UNEXPECTED (§7.1).
        runServer(listOf(clientRequest(listOf(0x02, 0x00)))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun request_dataFrameBeforeHeaders_isFrameUnexpected(): TestResult =
        // DATA before any HEADERS on a request stream is an invalid sequence ⇒ H3_FRAME_UNEXPECTED (§4.1).
        runServer(listOf(clientRequest(frameBytes(Http3Frame.Data(asciiBuffer("oops")))))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun request_greaseFrameBeforeHeaders_isIgnored_andRequestHandled(): TestResult {
        // A GREASE frame (0x21) before HEADERS is ignored (§9): the request still parses + is handled.
        val handledPath = CompletableDeferred<String>()
        return runServer(
            listOf(clientRequest(listOf(0x21, 0x01, 0xAA) + frameBytes(requestHeadersFrame()))),
            onRequest = {
                handledPath.complete(request.path)
                response.send(200)
            },
        ) { scope ->
            assertTrue(handledPath.isCompleted, "GREASE-before-HEADERS must be skipped and the request handled")
            assertEquals("/", withTimeout(5.seconds) { handledPath.await() })
            assertFalse(scope.closeErrorCode.isCompleted, "a GREASE request frame must not abort the connection")
        }
    }

    @Test
    fun request_reservedHttp2FrameInBody_isFrameUnexpected(): TestResult =
        // A reserved HTTP/2 frame (0x02) in the request body ⇒ H3_FRAME_UNEXPECTED (§7.1) when the handler
        // reads the body. (Without hardening this was silently ignored as an unknown frame.)
        runServer(
            listOf(clientRequest(frameBytes(requestHeadersFrame()) + listOf(0x02, 0x00))),
            onRequest = { request.readFullBody() },
        ) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun request_reservedHttp2FrameInBody_unreadByHandler_isFrameUnexpected(): TestResult =
        // Same violation but the handler returns WITHOUT reading the body — the framework's body drain
        // must still surface it as a connection error (the drain path no longer swallows it).
        runServer(
            listOf(clientRequest(frameBytes(requestHeadersFrame()) + listOf(0x02, 0x00))),
            onRequest = { response.send(200) },
        ) { scope ->
            assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, scope.awaitCloseCode())
        }

    @Test
    fun request_malformedFrame_isFrameError(): TestResult =
        // A malformed first frame — DATA (type 0x00) with a Length varint above Int.MAX — ⇒ H3_FRAME_ERROR
        // (§7.1), a CONNECTION error (was wrongly demoted to a stream reset before reactToRequestError
        // gained the FRAME_ERROR arm).
        runServer(listOf(clientRequest(listOf(0x00, 0xC0, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00)))) { scope ->
            assertEquals(Http3ErrorCode.FRAME_ERROR, scope.awaitCloseCode())
        }

    @Test
    fun request_malformedQpackHeaders_isQpackDecompressionFailed(): TestResult =
        // A HEADERS frame whose field section is a QPACK dynamic index into an empty table (0x00 0x00 0x80)
        // ⇒ H3_QPACK_DECOMPRESSION_FAILED (RFC 9204 §2.2), a CONNECTION error. The static codec throws a
        // raw DecodeException that decodeSection now types, and reactToRequestError now aborts on it.
        runServer(listOf(clientRequest(frameBytes(Http3Frame.Headers(rawBuffer(listOf(0x00, 0x00, 0x80))))))) { scope ->
            assertEquals(Http3ErrorCode.QPACK_DECOMPRESSION_FAILED, scope.awaitCloseCode())
        }

    // === WebTransport stream demux (draft-ietf-webtrans-http3 §4.1 / §4.2) — #496 ==============

    // The server's routers hand a WebTransport-signalled stream and its StreamProcessor to the shared
    // WebTransportMux, flagging it as the mux's first so their own finally releases nothing — and, unlike
    // the client's router, they have no arm that resets a stalled stream afterwards. So on the server every
    // exit the mux takes short of handing the stream to a session must both release the processor and
    // dispose of the stream itself; the peer's view of the stream is the second witness here.

    /**
     * **A WebTransport bidirectional stream stalling inside its Session ID is reset, and its processor is
     * released (#496).** `handleRequest` peeks `0x41` under the read deadline and hands over; the mux's
     * Session ID read then expires. The prefix chunk is counted out of the pool before the server exists
     * and must be back once the deadline has run out; the peer must see RESET_STREAM/STOP_SENDING carrying
     * `H3_REQUEST_CANCELLED` (RFC 9114 §4.1.1), not a stream left open at both ends.
     */
    @Test
    fun webTransport_bidiStreamStallingInsideItsSessionId_isResetAndReleasesItsProcessor(): TestResult {
        val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
        // Signal 0x41 (varint 0x40 0x41), then 0x40 — the first byte of a two-byte Session ID — then silence.
        val peerBidi = StalledStream(prefix = listOf(0x40, 0x41, 0x40), chunks = pool)
        assertEquals(1, pool.outstanding(), "sanity: the peer stream's chunk is the one buffer out of the pool")
        return runServer(
            listOf(QuicByteStream(QuicStreamId(0), peerBidi)),
            config = TransportConfig(bufferFactory = pool), // adopted as the server's pool, not wrapped
            webTransport = WebTransportOptions(),
        ) { scope ->
            assertEquals(
                0,
                pool.outstanding(),
                "client bidi stream 0 sent 0x41 and half a Session ID, then nothing past the 15s read deadline. " +
                    "The processor the router handed the WebTransport mux was still holding that half, and the " +
                    "mux released it on no path but success (#496): a buffer is still out of the pool. " +
                    "disposition=${peerBidi.disposition}",
            )
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerBidi.disposition,
                "the mux owned the stalled stream and must reset it for the peer; Open means the deadline " +
                    "expired out of the mux with the stream untouched (#496)",
            )
            assertFalse(scope.closeErrorCode.isCompleted, "one stalled WebTransport stream must not abort the connection")
        }
    }

    /**
     * **A WebTransport unidirectional stream stalling inside its Session ID is reset, and its processor is
     * released (#496).** `handleUniStream` reads the `0x54` type and hands over; the mux's Session ID read
     * then expires. Same two witnesses as the bidirectional case, through `acceptIncomingUni`.
     */
    @Test
    fun webTransport_uniStreamStallingInsideItsSessionId_isResetAndReleasesItsProcessor(): TestResult {
        val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
        // Type 0x54 (varint 0x40 0x54), then 0x40 — the first byte of a two-byte Session ID — then silence.
        val peerUni = StalledStream(prefix = listOf(0x40, 0x54, 0x40), chunks = pool)
        assertEquals(1, pool.outstanding(), "sanity: the peer stream's chunk is the one buffer out of the pool")
        return runServer(
            listOf(QuicByteStream(QuicStreamId(6), peerUni)),
            config = TransportConfig(bufferFactory = pool),
            webTransport = WebTransportOptions(),
        ) { scope ->
            assertEquals(
                0,
                pool.outstanding(),
                "client uni stream 6 sent 0x54 and half a Session ID, then nothing past the 15s read deadline. " +
                    "The processor the router handed the WebTransport mux was still holding that half, and the " +
                    "mux released it on no path but success (#496): a buffer is still out of the pool. " +
                    "disposition=${peerUni.disposition}",
            )
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                peerUni.disposition,
                "the mux owned the stalled stream and must STOP_SENDING it; Open means the deadline expired " +
                    "out of the mux with the stream untouched (#496)",
            )
            assertFalse(scope.closeErrorCode.isCompleted, "one stalled WebTransport stream must not abort the connection")
        }
    }

    /**
     * The control for the two above: a WebTransport stream whose Session ID **does** arrive, for a session
     * this server never had, takes the mux's pre-existing reset-and-release path. It fixes the observable's
     * baseline — a stream the mux disposes of leaves nothing outstanding — independently of #496.
     */
    @Test
    fun webTransport_uniStreamForAnUnknownSession_isResetWithNothingOutstanding(): TestResult {
        val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)
        // Type 0x54 (varint 0x40 0x54), then Session ID 4 — a CONNECT stream this server never saw — then silence.
        val peerUni = StalledStream(prefix = listOf(0x40, 0x54, 0x04), chunks = pool)
        assertEquals(1, pool.outstanding(), "sanity: the peer stream's chunk is the one buffer out of the pool")
        return runServer(
            listOf(QuicByteStream(QuicStreamId(6), peerUni)),
            config = TransportConfig(bufferFactory = pool),
            webTransport = WebTransportOptions(),
        ) { scope ->
            assertEquals(Disposition.Reset(0), peerUni.disposition, "a stream for an unknown session is reset by the mux")
            assertEquals(0, pool.outstanding(), "the mux's unknown-session path releases the processor: nothing outstanding")
            assertFalse(scope.closeErrorCode.isCompleted, "an unknown-session stream must not abort the connection")
        }
    }
}
