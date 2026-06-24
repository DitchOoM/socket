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
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        private val incoming: List<QuicByteStream>,
        private val bidi: ArrayDeque<QuicByteStream> = ArrayDeque(),
    ) : QuicScope,
        CoroutineScope by delegate {
        override val bufferFactory: BufferFactory = BufferFactory.Default
        val remainingUniStreams get() = outgoing.size

        override suspend fun openUniStream(): QuicByteStream = outgoing.removeFirst()

        override suspend fun openStream(): QuicByteStream =
            if (bidi.isEmpty()) throw UnsupportedOperationException() else bidi.removeFirst()

        override suspend fun acceptStream(): QuicByteStream = throw UnsupportedOperationException()

        override fun streams(): Flow<QuicByteStream> = incoming.asFlow()
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
