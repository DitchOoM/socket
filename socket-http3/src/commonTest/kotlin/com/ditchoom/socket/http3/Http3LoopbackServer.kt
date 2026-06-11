package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * A minimal in-process HTTP/3 **server** responder, hand-rolled from the same RFC 9114 codecs the
 * [Http3Connection] client uses. The deterministic counterpart to the client for the loopback test
 * (see [Http3LoopbackTestSuite]) and the seed of a real HTTP/3 server role.
 *
 * Per accepted QUIC connection, [serve]:
 *  1. opens the server control unidirectional stream and writes the `0x00` type prefix + a SETTINGS
 *     frame (static-table-only QPACK: capacity 0, blocked-streams 0), mirroring
 *     [Http3Connection]'s bootstrap so the client's [Http3Connection.peerSettings] resolves;
 *  2. collects every peer-initiated stream — reading each client unidirectional stream's type
 *     prefix (RFC 9114 §6.2), accepting-and-ignoring the client control stream's SETTINGS, and
 *     draining QPACK/other uni streams so flow control keeps flowing; and handling each
 *     client-initiated **bidirectional** stream as one request;
 *  3. for a request stream, reads the HEADERS frame (and any DATA frames) via [Http3StreamReader] +
 *     [QpackFieldSectionCodec], invokes [respond], then writes the response HEADERS (+ optional DATA)
 *     frame(s) and FINs the send side ([QuicByteStream.shutdownSend]).
 *
 * With [qpackCapacity] > 0 the server also speaks **dynamic QPACK** (RFC 9204): it advertises the
 * capacity, decodes requests through a [QpackDecoder] fed by the client's encoder stream, and
 * compresses responses through a [QpackEncoder] (sized to the client's advertised table) — so the
 * loopback exercises dynamic QPACK in both directions. With capacity 0 (default) it stays static-only.
 *
 * Intentionally minimal otherwise: no server push, no request validation beyond what the loopback test
 * asserts. Request/response bodies are modeled as UTF-8 strings for test ergonomics.
 */
internal class Http3LoopbackServer(
    private val options: ConnectionOptions = ConnectionOptions(),
    // When > 0 the server advertises that QPACK dynamic-table capacity, decodes requests through a
    // QpackDecoder, and dynamically compresses responses through a QpackEncoder — making the loopback
    // exercise dynamic QPACK in BOTH directions. 0 (default) keeps the original static-only behaviour.
    private val qpackCapacity: Long = 0,
    // Server push (RFC 9114 §4.6): the pushes to send for a given request. Each emits a PUSH_PROMISE on
    // the request stream plus a push stream carrying the pushed response. Only sent if the client
    // enabled push (sent MAX_PUSH_ID) and the allocated push id is within its advertised limit. The
    // promise field section is QPACK static-only (RIC=0), which the client's decoder reads either way.
    private val serverPushes: suspend (Request) -> List<Push> = { emptyList() },
    private val respond: suspend (Request) -> Response,
) {
    /** A decoded request as seen by the server: the request-line pseudo-headers, fields, and body. */
    data class Request(
        val method: String,
        val path: String,
        val scheme: String,
        val authority: String,
        val headers: List<QpackHeaderField>,
        val body: String,
    )

    /**
     * A canned response the server writes back as HEADERS (+ DATA) then a send-side FIN. The two
     * `malformed*` flags let a test drive the client's RFC 9114 §8 enforcement:
     * [omitStatus] sends a HEADERS frame with no `:status` (a malformed *message* → the client resets
     * the stream with H3_MESSAGE_ERROR), and [dataBeforeHeaders] sends a DATA frame first (an invalid
     * frame *sequence* → the client aborts the connection with H3_FRAME_UNEXPECTED).
     */
    data class Response(
        val status: Int,
        val headers: List<QpackHeaderField> = emptyList(),
        val body: String = "",
        val omitStatus: Boolean = false,
        val dataBeforeHeaders: Boolean = false,
    )

    /** A server push (RFC 9114 §4.6): the promised request line + the response carried on the push stream. */
    data class Push(
        val method: String = "GET",
        val scheme: String = "https",
        val authority: String,
        val path: String,
        val promisedHeaders: List<QpackHeaderField> = emptyList(),
        val response: Response,
    )

    // MultiThreaded: per-stream handler coroutines allocate from this pool concurrently.
    private val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = options.bufferFactory)

    // Dynamic-QPACK state (only when qpackCapacity > 0). One server instance serves one connection in
    // the tests, so per-instance state is fine. The decoder decodes requests; the encoder (created once
    // the client's SETTINGS reveal its table capacity) compresses responses.
    private val serverDecoder: QpackDecoder? =
        if (qpackCapacity > 0) QpackDecoder(qpackCapacity) { writeQpackDecoderInstruction(it) } else null

    @Volatile
    private var serverEncoder: QpackEncoder? = null
    private var qpackEncoderStream: QuicByteStream? = null
    private var qpackDecoderStream: QuicByteStream? = null
    private val encoderStreamWriteMutex = Mutex()
    private val decoderStreamWriteMutex = Mutex()

    // Push state: the client's advertised MAX_PUSH_ID (-1 until/unless it enables push), and the next
    // server-allocated push id. The server may use ids 0..clientMaxPushId.
    @Volatile
    private var clientMaxPushId: Long = -1
    private val pushIdMutex = Mutex()
    private var nextPushId = 0L

    /**
     * Run the HTTP/3 server role over [scope] — a server-accepted QUIC connection (the receiver of
     * `QuicServer.connections`). Returns when the connection closes (the [QuicScope.streams] flow
     * completes), at which point the framework tears the connection down.
     */
    suspend fun serve(scope: QuicScope) {
        writeControlStreamHeader(scope.openUniStream())
        if (qpackCapacity > 0) {
            qpackEncoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_ENCODER) }
            qpackDecoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_DECODER) }
        }
        scope.streams().collect { stream ->
            scope.launch {
                try {
                    if (stream.streamId.isUnidirectional) handleUniStream(stream) else handleRequest(scope, stream)
                } catch (_: Http3StreamException) {
                    // A single stream failing must not take the connection down.
                } catch (_: QuicStreamException) {
                    // Peer STOP_SENDING / RESET_STREAM on one stream (e.g. a client cancelling a server
                    // PUSH, RFC 9114 §7.2.3) — stream-scoped, the connection stays up for other streams.
                }
            }
        }
    }

    /**
     * Read a client unidirectional stream's type prefix (RFC 9114 §6.2) and dispatch it, mirroring
     * the client's [Http3Connection] router (sharing one [StreamProcessor] so the type-prefix read
     * doesn't drop buffered payload). The control stream's first SETTINGS frame sizes our encoder; the
     * client's QPACK encoder/decoder streams drive our decoder/encoder when dynamic, else are drained.
     */
    private suspend fun handleUniStream(stream: QuicByteStream) {
        val processor = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            when (Http3StreamReader(stream, processor).nextVarInt()) {
                Http3StreamType.CONTROL -> handleControl(Http3StreamReader(stream, processor))
                Http3StreamType.QPACK_ENCODER ->
                    serverDecoder?.let { dec ->
                        val reader = QpackInstructionReader.encoder(stream, processor, pool)
                        while (true) dec.applyEncoderInstruction(reader.next(options.readTimeout) ?: break)
                    } ?: drain(stream)
                Http3StreamType.QPACK_DECODER ->
                    if (serverDecoder != null) {
                        val reader = QpackInstructionReader.decoder(stream, processor)
                        while (true) serverEncoder?.processDecoderInstruction(reader.next(options.readTimeout) ?: break)
                    } else {
                        drain(stream)
                    }
                else -> drain(stream)
            }
        } finally {
            processor.release()
        }
    }

    /** Read the client's control stream: capture its SETTINGS (to size our encoder), then ignore the rest. */
    private suspend fun handleControl(reader: Http3StreamReader) {
        val first = reader.nextFrame()
        if (qpackCapacity > 0 && first is Http3Frame.Settings) {
            val clientMax = Http3Settings(first.entries).qpackMaxTableCapacity
            if (clientMax > 0) {
                serverEncoder =
                    QpackEncoder(clientMax) { writeQpackEncoderInstruction(it) }.also { it.setCapacity(minOf(qpackCapacity, clientMax)) }
            }
        }
        // Capture MAX_PUSH_ID so we know whether (and how much) the client lets us push; ignore the rest.
        while (true) {
            val frame = reader.nextFrame() ?: break
            if (frame is Http3Frame.MaxPushId) clientMaxPushId = frame.pushId
        }
    }

    /** Read one request off a client bidi [stream], call [respond], and write the response (+ pushes) back. */
    private suspend fun handleRequest(
        scope: QuicScope,
        stream: QuicByteStream,
    ) {
        val reader = Http3StreamReader.create(stream, pool)
        try {
            val first =
                reader.nextFrame(options.readTimeout)
                    ?: throw Http3StreamException("request stream ended before a HEADERS frame")
            if (first !is Http3Frame.Headers) {
                throw Http3StreamException("request's first frame was ${first::class.simpleName}, expected HEADERS")
            }
            val decoder = serverDecoder
            val fields =
                if (decoder != null) {
                    decoder.decodeSection(first.encodedFieldSection, stream.streamId.id, pool)
                } else {
                    QpackFieldSectionCodec.decode(
                        first.encodedFieldSection,
                        com.ditchoom.buffer.codec.DecodeContext.Empty
                            .with(QpackScratchPoolKey, pool),
                    )
                }
            // Drain request DATA frames (the body) until the client's FIN ends the stream.
            val body = StringBuilder()
            while (true) {
                val frame = reader.nextFrame(options.readTimeout) ?: break
                if (frame is Http3Frame.Data) {
                    body.append(frame.payload.readString(frame.payload.remaining(), Charset.UTF8))
                }
            }
            val request =
                Request(
                    method = pseudo(fields, ":method"),
                    path = pseudo(fields, ":path"),
                    scheme = pseudo(fields, ":scheme"),
                    authority = pseudo(fields, ":authority"),
                    headers = fields.filterNot { it.name.startsWith(":") },
                    body = body.toString(),
                )
            val response = respond(request)
            // Allocate push ids within the client's limit, emit a PUSH_PROMISE for each on the request
            // stream BEFORE the response (RFC 9114 §7.2.5), write the response, then open the push streams.
            val allocated = allocatePushes(if (clientMaxPushId >= 0) serverPushes(request) else emptyList())
            for ((pushId, push) in allocated) writePushPromise(stream, pushId, push)
            writeResponse(stream, response)
            for ((pushId, push) in allocated) {
                try {
                    writePushStream(scope, pushId, push)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: QuicStreamException) {
                    // Client cancelled this push (STOP_SENDING) — abandon just this push stream and
                    // continue emitting the others; the request response was already written above.
                }
            }
        } finally {
            reader.release()
        }
    }

    /** Assign sequential push ids (0,1,…) to [pushes], stopping at the client's advertised maximum. */
    private suspend fun allocatePushes(pushes: List<Push>): List<Pair<Long, Push>> {
        val allocated = mutableListOf<Pair<Long, Push>>()
        for (push in pushes) {
            val id = pushIdMutex.withLock { if (nextPushId > clientMaxPushId) -1L else nextPushId++ }
            if (id < 0) break
            allocated += id to push
        }
        return allocated
    }

    /** Write a PUSH_PROMISE frame (RFC 9114 §7.2.5) on the request [stream] — promised request, static QPACK. */
    private suspend fun writePushPromise(
        stream: QuicByteStream,
        pushId: Long,
        push: Push,
    ) {
        val section = encodePromisedFields(push)
        try {
            writeFrame(stream, Http3Frame.PushPromise(pushId, section))
        } finally {
            section.freeIfNeeded()
        }
    }

    /** Open a push stream (type 0x01 + Push ID, RFC 9114 §4.6) and write the pushed response on it. */
    private suspend fun writePushStream(
        scope: QuicScope,
        pushId: Long,
        push: Push,
    ) {
        val pushStream = scope.openUniStream()
        val header = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.PUSH) + VarIntCodec.encodedLength(pushId))
        try {
            VarIntCodec.encode(header, Http3StreamType.PUSH, EncodeContext.Empty)
            VarIntCodec.encode(header, pushId, EncodeContext.Empty)
            header.resetForRead()
            pushStream.write(header, options.writeTimeout)
        } finally {
            header.freeIfNeeded()
        }
        writeResponse(pushStream, push.response)
    }

    /** Encode a promised request's pseudo-headers + headers as a QPACK static field section. */
    private fun encodePromisedFields(push: Push): ReadBuffer {
        val fields =
            buildList {
                add(QpackHeaderField(":method", push.method))
                add(QpackHeaderField(":scheme", push.scheme))
                add(QpackHeaderField(":authority", push.authority))
                add(QpackHeaderField(":path", push.path))
                addAll(push.promisedHeaders)
            }
        val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
        return pool.allocate(size).also {
            QpackFieldSectionCodec.encode(it, fields, EncodeContext.Empty)
            it.resetForRead()
        }
    }

    /** Write the response HEADERS (`:status` first) + an optional DATA frame, then FIN the send side. */
    private suspend fun writeResponse(
        stream: QuicByteStream,
        response: Response,
    ) {
        // Malformed-sequence injection: a DATA frame before any HEADERS is an invalid request-stream
        // sequence (RFC 9114 §4.1) the client must treat as a connection error.
        if (response.dataBeforeHeaders) {
            val stray = options.bufferFactory.allocate(4)
            stray.writeString("oops", Charset.UTF8)
            stray.resetForRead()
            try {
                writeFrame(stream, Http3Frame.Data(stray))
            } finally {
                stray.freeIfNeeded()
            }
        }
        val fields =
            buildList {
                if (!response.omitStatus) add(QpackHeaderField(":status", response.status.toString()))
                addAll(response.headers)
            }
        val encoder = serverEncoder
        val sectionBuffer =
            if (encoder != null) {
                encoder.encodeSection(fields, stream.streamId.id, pool)
            } else {
                val sectionSize = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
                pool.allocate(sectionSize).also {
                    QpackFieldSectionCodec.encode(it, fields, EncodeContext.Empty)
                    it.resetForRead()
                }
            }
        try {
            writeFrame(stream, Http3Frame.Headers(sectionBuffer))
        } finally {
            sectionBuffer.freeIfNeeded()
        }
        if (response.body.isNotEmpty()) {
            // Test bodies are ASCII, so byte length == char length; allocate exactly that.
            val bodyBuffer = options.bufferFactory.allocate(response.body.length)
            try {
                bodyBuffer.writeString(response.body, Charset.UTF8)
                bodyBuffer.resetForRead()
                writeFrame(stream, Http3Frame.Data(bodyBuffer))
            } finally {
                bodyBuffer.freeIfNeeded()
            }
        }
        stream.shutdownSend()
    }

    /** Control stream: the type prefix `0x00` immediately followed by the SETTINGS frame. */
    private suspend fun writeControlStreamHeader(control: QuicByteStream) {
        val settings =
            Http3Frame.Settings(
                listOf(
                    Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                    Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
                ),
            )
        val frameSize = (HandwrittenHttp3FrameCodec.wireSize(settings, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL) + frameSize)
        try {
            VarIntCodec.encode(buffer, Http3StreamType.CONTROL, EncodeContext.Empty)
            HandwrittenHttp3FrameCodec.encode(buffer, settings, EncodeContext.Empty)
            buffer.resetForRead()
            control.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Encode [frame] into a pooled buffer and write the whole frame to [stream]. */
    private suspend fun writeFrame(
        stream: QuicByteStream,
        frame: Http3Frame,
    ) {
        val size = (HandwrittenHttp3FrameCodec.wireSize(frame, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(size)
        try {
            HandwrittenHttp3FrameCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Writes a bare unidirectional stream-type prefix (RFC 9114 §6.2). */
    private suspend fun writeStreamType(
        stream: QuicByteStream,
        type: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(type))
        try {
            VarIntCodec.encode(buffer, type, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun writeQpackEncoderInstruction(instruction: QpackEncoderInstruction) {
        val stream = qpackEncoderStream ?: return
        val capacity =
            when (instruction) {
                is QpackEncoderInstruction.InsertWithNameRef -> 32 + qpackUtf8ByteLength(instruction.value)
                is QpackEncoderInstruction.InsertWithLiteralName ->
                    32 + qpackUtf8ByteLength(instruction.name) + qpackUtf8ByteLength(instruction.value)
                else -> 32
            }
        val buffer = pool.allocate(capacity)
        try {
            QpackEncoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            encoderStreamWriteMutex.withLock { stream.write(buffer, options.writeTimeout) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun writeQpackDecoderInstruction(instruction: QpackDecoderInstruction) {
        val stream = qpackDecoderStream ?: return
        val buffer = pool.allocate(16)
        try {
            QpackDecoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            decoderStreamWriteMutex.withLock { stream.write(buffer, options.writeTimeout) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Reads and discards a unidirectional stream's bytes until end-of-stream or reset. */
    private suspend fun drain(stream: QuicByteStream) {
        while (true) {
            when (val result = stream.read(options.readTimeout)) {
                is ReadResult.Data -> result.buffer.freeIfNeeded()
                ReadResult.End, ReadResult.Reset -> return
            }
        }
    }

    private fun pseudo(
        fields: List<QpackHeaderField>,
        name: String,
    ): String =
        fields.firstOrNull { it.name == name }?.value
            ?: throw Http3StreamException("request HEADERS missing the $name pseudo-header")
}
