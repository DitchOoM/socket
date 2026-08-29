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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
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
import kotlin.test.fail
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

    /** A [ByteStream] that records everything written and replays a scripted read sequence. */
    private class RecordingByteStream(
        readScript: List<ReadResult> = emptyList(),
    ) : ByteStream,
        com.ditchoom.buffer.flow.Resettable {
        val written = mutableListOf<Int>()
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
     * A [QuicScope] test double driving [Http3ServerConnection.serve]: hands out the server's own uni
     * streams (control + optional QPACK) from [outgoing], replays the peer streams from [incoming] (a
     * flow, so a test can make a stream arrive at a chosen virtual time), and records the application
     * error code of a [closeWithError] (the server's connection abort) in [closeErrorCode].
     */
    private class FakeQuicScope(
        delegate: CoroutineScope,
        private val outgoing: ArrayDeque<QuicByteStream>,
        private val incoming: Flow<QuicByteStream>,
    ) : QuicScope,
        CoroutineScope by delegate {
        override val bufferFactory: BufferFactory = BufferFactory.Default
        val closeErrorCode = CompletableDeferred<Long>()

        override suspend fun openUniStream(): QuicByteStream = outgoing.removeFirst()

        override suspend fun openStream(): QuicByteStream = throw UnsupportedOperationException()

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException()

        override fun streams(): Flow<QuicByteStream> = incoming

        override suspend fun closeWithError(errorCode: Long) {
            closeErrorCode.complete(errorCode)
        }
    }

    /** The server's own outgoing uni streams (control, and QPACK enc/dec when dynamic) — server-initiated. */
    private fun serverOutgoing(): ArrayDeque<QuicByteStream> = ServerStreams().outgoing()

    /** The three server uni streams [Http3ServerConnection.serve] opens, with recording delegates exposed. */
    private class ServerStreams {
        val control = RecordingByteStream()
        val qpackEncoder = RecordingByteStream()
        val qpackDecoder = RecordingByteStream()

        fun outgoing(): ArrayDeque<QuicByteStream> =
            ArrayDeque(
                listOf(
                    QuicByteStream(QuicStreamId(3), control),
                    QuicByteStream(QuicStreamId(7), qpackEncoder),
                    QuicByteStream(QuicStreamId(11), qpackDecoder),
                ),
            )
    }

    /**
     * Run [Http3ServerConnection.serve] over the scripted [incoming] streams to completion (every test
     * double's reads are finite, so the server's stream router and its per-stream handler coroutines all
     * finish on their own and the enclosing [coroutineScope] joins them), then run [assertions] against
     * the scope — at which point any connection abort has already been recorded.
     */
    private fun runServer(
        incoming: List<QuicByteStream>,
        qpackCapacity: Long = 0,
        config: TransportConfig = TransportConfig(),
        webTransport: WebTransportOptions? = null,
        onRequest: suspend Http3ServerExchange.() -> Unit = { response.send(200) },
        assertions: suspend (FakeQuicScope) -> Unit,
    ): TestResult = runServer(incoming.asFlow(), qpackCapacity, config, webTransport, onRequest, assertions)

    /** [runServer] over a flow of peer streams, for a test that needs a stream to arrive at a chosen virtual time. */
    private fun runServer(
        incoming: Flow<QuicByteStream>,
        qpackCapacity: Long = 0,
        config: TransportConfig = TransportConfig(),
        webTransport: WebTransportOptions? = null,
        onRequest: suspend Http3ServerExchange.() -> Unit = { response.send(200) },
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
                            ).asFlow(),
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

    // === #495: the client-side deadline fixes (#472 / #477), mirrored on the server =================

    /** Encodes one QPACK encoder instruction to the bytes a client puts on its encoder stream. */
    private fun encoderBytes(instruction: QpackEncoderInstruction): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        QpackEncoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    /** Encodes one QPACK decoder instruction to the bytes a client puts on its decoder stream. */
    private fun decoderBytes(instruction: QpackDecoderInstruction): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        QpackDecoderInstructionCodec.encode(buf, instruction)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    /** A client request stream carrying [headers] as its HEADERS frame, then FIN. */
    private fun clientRequest(
        id: Long,
        headers: Http3Frame.Headers,
    ): QuicByteStream = QuicByteStream(QuicStreamId(id), RecordingByteStream(listOf(dataChunk(frameBytes(headers)), ReadResult.End)))

    /** The pseudo-headers of a GET, in RFC 9114 §4.3.1 order. */
    private val getPseudoFields =
        listOf(
            QpackHeaderField(":method", "GET"),
            QpackHeaderField(":scheme", "https"),
            QpackHeaderField(":authority", "h.test"),
            QpackHeaderField(":path", "/"),
        )

    /**
     * **A client whose QPACK streams go quiet must not kill the server's pumps (#495, mirroring #472).**
     *
     * [Http3ServerConnection] read both client QPACK streams under `config.readPolicy.toDeadline()` —
     * [TransportConfig]'s default `ReadPolicy.Bounded(15.seconds)`. Those streams are idle *by design*: a
     * client inserts into the dynamic table only when it has a header worth reusing, and acknowledges only
     * what the server's encoder inserted. Silence there is ordinary, but the QUIC leaf raises
     * `TimeoutCancellationException` on it — a `CancellationException` — and `serve()`'s per-stream
     * `launch` child completing with one is *cancelled, not failed*: the pump vanished, nothing logged,
     * no abort, and the server's decoder table stopped following the client's encoder.
     *
     * Three witnesses, none of which reads the pump. The request the client sends AFTER the silence: its
     * HEADERS reference the entries the client inserted after the silence, so with the pump dead the
     * server's decoder parks on a Required Insert Count it will never reach and the handler never runs.
     * The Insert Count Increment the decoder emits on the server's own decoder stream when it applies an
     * insert (RFC 9204 §4.4.3). And each client QPACK stream being read through to its end-of-stream,
     * which lies on the far side of the silence. The client's control stream already read with no
     * deadline; only the two pumps armed one.
     */
    @Test
    fun aSilentClientQpackStreamPairStillDecodesTheNextRequest(): TestResult =
        runTest {
            // A client-side encoder produces the wire bytes: Set Capacity + insert instructions for the
            // server's decoder, and a request field section whose x-after-silence line (and :authority) is a
            // dynamic reference to those inserts — Required Insert Count > 0, so the decode depends on them.
            val clientInstructions = mutableListOf<QpackEncoderInstruction>()
            val clientEncoder = QpackEncoder(peerMaxCapacity = 4096, peerMaxBlockedStreams = 100) { clientInstructions += it }
            clientEncoder.setCapacity(4096)
            val section =
                clientEncoder.encodeSection(getPseudoFields + QpackHeaderField("x-after-silence", "applied"), QuicStreamId(0), BufferPool())
            val setCapacity = clientInstructions.first()
            val inserts = clientInstructions.drop(1)
            assertTrue(setCapacity is QpackEncoderInstruction.SetCapacity, "sanity: the encoder announces its capacity first")
            assertTrue(
                inserts.isNotEmpty(),
                "sanity: the section must reference dynamic-table inserts, or it cannot tell whether the pump applied them",
            )

            val clientQpackEncoder =
                SilentThenSpeakingStream(
                    first = listOf(Http3StreamType.QPACK_ENCODER.toInt()) + encoderBytes(setCapacity),
                    // Longer than the 15s default, so the deadline the pump armed expires inside the silence.
                    silence = 20.seconds,
                    afterSilence = inserts.flatMap { encoderBytes(it) },
                )
            val clientQpackDecoder =
                SilentThenSpeakingStream(
                    first = listOf(Http3StreamType.QPACK_DECODER.toInt()),
                    silence = 20.seconds,
                    // The client abandoning a section's references (§4.4.2) — nothing for the server to act on
                    // (it has no encoder: the client advertised capacity 0), but the pump must still be reading.
                    afterSilence = decoderBytes(QpackDecoderInstruction.StreamCancellation(QuicStreamId(0))),
                )
            val server = ServerStreams()
            val decodedHeaders = CompletableDeferred<List<QpackHeaderField>>()
            lateinit var scope: FakeQuicScope
            // Not runServer: with the pumps dead the request's decode parks forever, and coroutineScope would
            // wait on it. A separate job lets advanceUntilIdle run the clock out and the assertions fail by name.
            val serverJob =
                launch {
                    coroutineScope {
                        scope =
                            FakeQuicScope(
                                this,
                                server.outgoing(),
                                flow {
                                    emit(clientControl(frameBytes(clientSettings())))
                                    emit(QuicByteStream(QuicStreamId(6), clientQpackEncoder))
                                    emit(QuicByteStream(QuicStreamId(10), clientQpackDecoder))
                                    delay(25.seconds) // past the silence: the inserts have been on the wire for 5s
                                    emit(clientRequest(id = 0, headers = Http3Frame.Headers(section)))
                                },
                            )
                        Http3ServerConnection(
                            scope,
                            TransportConfig(),
                            qpackCapacity = 4096,
                            onRequest = {
                                decodedHeaders.complete(request.headers)
                                response.send(200)
                            },
                        ).serve()
                    }
                }
            try {
                testScheduler.advanceUntilIdle()

                assertTrue(
                    decodedHeaders.isCompleted,
                    "the client's QPACK encoder stream went quiet for 20s — longer than the default 15s read " +
                        "deadline — then inserted ${inserts.size} entries, and 5s after that a request arrived whose " +
                        "HEADERS reference them (Required Insert Count ${inserts.size}). The handler never ran: the " +
                        "server's decoder is parked waiting for inserts its pump will never apply, because the pump " +
                        "expired in the silence as a cancelled child (#495, #472's mechanism). " +
                        "encoderStreamReadToEnd=${clientQpackEncoder.readToEnd}, " +
                        "decoderStreamReadToEnd=${clientQpackDecoder.readToEnd}, " +
                        "serverDecoderStreamBytes=${server.qpackDecoder.written.size}, " +
                        "aborted=${scope.closeErrorCode.isCompleted}",
                )
                assertEquals(
                    "applied",
                    decodedHeaders.await().firstOrNull { it.name == "x-after-silence" }?.value,
                    "the dynamic-table reference must decode to the value the client inserted after the silence",
                )
                assertEquals(
                    listOf(Http3StreamType.QPACK_DECODER.toInt()),
                    server.qpackDecoder.written.take(1),
                    "sanity: the server's decoder stream starts with its type prefix",
                )
                assertTrue(
                    server.qpackDecoder.written.size > 1,
                    "applying the inserts must emit Insert Count Increments on the server's decoder stream " +
                        "(RFC 9204 §4.4.3), beyond its type prefix",
                )
                assertTrue(
                    clientQpackEncoder.readToEnd,
                    "the client's encoder stream must be read through to end-of-stream, past the silence",
                )
                assertTrue(
                    clientQpackDecoder.readToEnd,
                    "the client's decoder stream must be read through to end-of-stream, past the silence",
                )
                assertFalse(scope.closeErrorCode.isCompleted, "a quiet QPACK stream is not a connection error")
            } finally {
                serverJob.cancelAndJoin()
            }
        }

    /**
     * **A client bidirectional stream that stalls past the read deadline is abandoned by name, not mistaken
     * for a cancelled server (#495, mirroring #477).**
     *
     * With WebTransport enabled, `handleRequest` peeks a client bidi stream's first varint for the `0x41`
     * signal under `config.readPolicy.toDeadline()`. Unlike the QPACK pumps that deadline is right — a
     * client that opens a stream and says nothing must not be waited on forever — but the QUIC leaf raises
     * `TimeoutCancellationException`, and the handler's `catch (e: CancellationException) { throw e }` arm
     * claimed it: `serve()`'s `launch` child read as *cancelled rather than failed*, the reader was
     * released, and nothing else happened — no name, no RESET_STREAM / STOP_SENDING, the client's half of
     * the stream open until the connection ends.
     *
     * The witness is the client's view of the stream, [StalledStream.disposition]: abandoned on its
     * deadline it is **reset carrying `H3_REQUEST_CANCELLED`**, where a cancelled server leaves it
     * untouched (see [aCancelledServerLeavesAStalledStreamUnnamed]). The connection is then shown intact by
     * a request that arrives after the abandonment and is still served.
     */
    @Test
    fun aStalledClientBidiStreamIsAbandonedByName_andTheConnectionSurvives(): TestResult {
        val stalled = StalledStream(prefix = emptyList()) // opened, then silent
        val handledPath = CompletableDeferred<String>()
        return runServer(
            flow {
                emit(clientControl(frameBytes(clientSettings())))
                emit(QuicByteStream(QuicStreamId(0), stalled))
                delay(20.seconds) // the stalled peek has been out past its 15s deadline for 5s
                emit(clientRequest(id = 4, headers = requestHeadersFrame()))
            },
            webTransport = WebTransportOptions(),
            onRequest = {
                handledPath.complete(request.path)
                response.send(200)
            },
        ) { scope ->
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                stalled.disposition,
                "client bidi stream 0 sent nothing for longer than the default 15s read deadline. Abandoning it " +
                    "must reach the client as STOP_SENDING/RESET_STREAM carrying H3_REQUEST_CANCELLED (RFC 9114 " +
                    "§4.1.1). Untouched is what a *cancelled* server leaves, so it says the deadline was mistaken " +
                    "for cancellation (#495) and the client's half of the stream is still open. " +
                    "aborted=${scope.closeErrorCode.isCompleted}",
            )
            assertFalse(scope.closeErrorCode.isCompleted, "one stalled client stream must not become a connection error")
            assertTrue(
                handledPath.isCompleted,
                "the connection must still serve a request that arrives after the stalled stream was abandoned",
            )
            assertEquals("/", handledPath.await())
        }
    }

    /**
     * **The same stall one read later, with WebTransport off: a request stream that never sends HEADERS
     * (#495).** Without a mux there is no peek; the first read the deadline governs is `readRequestHeaders`'s
     * `nextFrame`, and its expiry took the same cancellation arm. The arm that names the stall wraps the
     * whole request, so every deadline-governed read on a request stream ends the same way: named, the
     * client told, the connection intact.
     */
    @Test
    fun aRequestStreamThatNeverSendsHeadersIsAbandonedByName(): TestResult {
        val stalled = StalledStream(prefix = emptyList())
        return runServer(listOf(clientControl(frameBytes(clientSettings())), QuicByteStream(QuicStreamId(0), stalled))) { scope ->
            assertEquals(
                Disposition.Reset(Http3ErrorCode.REQUEST_CANCELLED),
                stalled.disposition,
                "client request stream 0 sent nothing for longer than the default 15s read deadline and no " +
                    "HEADERS ever came. It must be abandoned by name with H3_REQUEST_CANCELLED (RFC 9114 §4.1.1); " +
                    "untouched means readRequestHeaders's deadline died as a cancellation (#495). " +
                    "aborted=${scope.closeErrorCode.isCompleted}",
            )
            assertFalse(scope.closeErrorCode.isCompleted, "one stalled request stream must not become a connection error")
        }
    }

    /**
     * The control for the two above: a **genuinely** cancelled server leaves a stalled stream untouched and
     * unnamed — the connection's teardown reclaims it. That is what makes the reset the deadline's alone: a
     * fix that caught `CancellationException` wholesale (the ordering trap #472 documents, from the other
     * side) would report cancellation as a deadline and fail here.
     */
    @Test
    fun aCancelledServerLeavesAStalledStreamUnnamed(): TestResult =
        runTest {
            val stalled = StalledStream(prefix = emptyList())
            val serverJob =
                launch {
                    coroutineScope {
                        val scope = FakeQuicScope(this, serverOutgoing(), listOf(QuicByteStream(QuicStreamId(0), stalled)).asFlow())
                        Http3ServerConnection(
                            scope,
                            TransportConfig(),
                            qpackCapacity = 0,
                            onRequest = { response.send(200) },
                            webTransport = WebTransportOptions(),
                        ).serve()
                        // coroutineScope now waits on the request handler, parked on the stalled peek.
                    }
                }
            testScheduler.advanceTimeBy(1.seconds) // the peek is parked on the stall, well inside its 15s deadline
            assertEquals(Disposition.Open, stalled.disposition, "sanity: nothing has happened to the stream yet")

            serverJob.cancelAndJoin()

            assertEquals(
                Disposition.Open,
                stalled.disposition,
                "a cancelled server must leave the stalled stream untouched: cancellation is not a deadline, " +
                    "and resetting it with H3_REQUEST_CANCELLED would be the #477 confusion in reverse",
            )
        }

    /**
     * The harder control: a **parent** `withTimeout` cancels every child *with its own*
     * `TimeoutCancellationException`, so inside the handler the exception that surfaces from the parked
     * read is indistinguishable **by type** from a read deadline. Only the job tells them apart — a read
     * deadline leaves the child active, a cancellation does not — and a fix keyed on the type alone would
     * reset this stream and report a teardown as a stall.
     */
    @Test
    fun aParentDeadlineCancellingTheServerIsNotAStreamDeadline(): TestResult =
        runTest {
            val stalled = StalledStream(prefix = emptyList())
            try {
                withTimeout(1.seconds) {
                    val scope = FakeQuicScope(this, serverOutgoing(), listOf(QuicByteStream(QuicStreamId(0), stalled)).asFlow())
                    Http3ServerConnection(
                        scope,
                        TransportConfig(),
                        qpackCapacity = 0,
                        onRequest = { response.send(200) },
                        webTransport = WebTransportOptions(),
                    ).serve()
                    // withTimeout's scope waits on its children: the handler, parked on the stalled peek.
                }
                fail("withTimeout(1s) around a server parked on a stalled client stream must expire, not return")
            } catch (e: TimeoutCancellationException) {
                // The parent's deadline — the one that is supposed to fire here.
            }

            assertEquals(
                Disposition.Open,
                stalled.disposition,
                "the server was cancelled by a parent withTimeout(1s), well inside the stream's own 15s read " +
                    "deadline, and its handler received that parent's TimeoutCancellationException. A reset with " +
                    "H3_REQUEST_CANCELLED here means the fix keyed on the exception's type and reported a " +
                    "cancellation as a stream stall — the #477 confusion in reverse",
            )
        }

    /**
     * The same control for the QPACK pumps' arm (#495, item 3 mirrored): the arm that names a pump's
     * deadline as [Http3Violation.QpackPumpDeadlineExpired] and aborts the connection must not fire when
     * the `TimeoutCancellationException` is a parent's. A pump reads with no deadline of its own, so one
     * arriving while the pump is still active can only be a read deadline; one arriving cancelled is
     * somebody else's, and aborting on it sends QPACK_ENCODER_STREAM_ERROR to a client that did nothing wrong.
     */
    @Test
    fun aParentDeadlineCancellingTheServerIsNotAQpackPumpDeadline(): TestResult =
        runTest {
            // The client's encoder stream: its type byte, then silence — the pump parks on an unbounded read.
            val clientQpackEncoder = StalledStream(prefix = listOf(Http3StreamType.QPACK_ENCODER.toInt()))
            lateinit var scope: FakeQuicScope
            try {
                withTimeout(1.seconds) {
                    scope = FakeQuicScope(this, serverOutgoing(), listOf(QuicByteStream(QuicStreamId(6), clientQpackEncoder)).asFlow())
                    Http3ServerConnection(scope, TransportConfig(), qpackCapacity = 4096, onRequest = { response.send(200) }).serve()
                    // withTimeout's scope waits on its children: the pump, parked on the stalled stream.
                }
                fail("withTimeout(1s) around a server parked in a QPACK pump must expire, not return")
            } catch (e: TimeoutCancellationException) {
                // The parent's deadline — the one that is supposed to fire here.
            }

            assertFalse(
                scope.closeErrorCode.isCompleted,
                "the server was cancelled by a parent withTimeout(1s) while its pump for the client's QPACK " +
                    "encoder stream was parked on a read with no deadline of its own. The pump received that " +
                    "parent's TimeoutCancellationException; an abort here means the pump's arm keyed on the " +
                    "exception's type alone and reported a teardown as its own deadline (#495)",
            )
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
