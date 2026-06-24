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
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertions: suspend (FakeQuicScope) -> Unit,
    ): TestResult =
        runTest {
            lateinit var scope: FakeQuicScope
            coroutineScope {
                scope = FakeQuicScope(this, serverOutgoing(), incoming)
                Http3ServerConnection(scope, TransportConfig(), qpackCapacity, onRequest).serve()
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
        // MAX_PUSH_ID is client→server (§7.2.7); the server accepts it (it sizes server-push credit).
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
}
