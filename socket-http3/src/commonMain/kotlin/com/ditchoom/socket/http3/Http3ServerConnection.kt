package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import com.ditchoom.socket.quic.QuicStreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * The **server** side of an HTTP/3 connection (RFC 9114 §3.2) — the production counterpart to the
 * client [Http3Connection], run once per accepted QUIC connection by [withHttp3Server].
 *
 * [serve] opens the server control unidirectional stream (its first frame is the server SETTINGS),
 * optionally the QPACK encoder/decoder streams when [qpackCapacity] > 0, then routes peer-initiated
 * streams: the client control stream's SETTINGS sizes the QPACK encoder; the client QPACK streams
 * drive the decoder/encoder; and each client **bidirectional** stream is one request — decoded into an
 * [Http3ServerRequest], paired with an [Http3ServerResponse], and handed to [onRequest]. After the
 * handler returns, any unread request body is drained and the response is FIN'd.
 *
 * With [qpackCapacity] > 0 the server speaks dynamic QPACK (RFC 9204) in both directions: it decodes
 * requests through a [QpackDecoder] fed by the client's encoder stream and compresses responses through
 * a [QpackEncoder]. With capacity 0 (default) it stays static-table-only, which is always legal.
 *
 * This server does not initiate server push (RFC 9114 §4.6) — that is the inverse of the client push
 * support and a tracked follow-up; the client push API in [Http3Connection.pushes] is independent.
 */
class Http3ServerConnection internal constructor(
    private val scope: QuicScope,
    private val config: TransportConfig,
    private val qpackCapacity: Long,
    private val onRequest: suspend Http3ServerExchange.() -> Unit,
    // WebTransport participation (RFC 9220 + RFC 9297). Null disables it; non-null advertises the WT
    // SETTINGS on the server control stream so clients see [Http3Settings.webTransportSupported].
    private val webTransport: WebTransportOptions? = null,
    // Handles incoming Extended CONNECT (`:protocol=webtransport`) requests when [webTransport] is
    // enabled — accept() or reject() the session inside. Null ⇒ every WebTransport CONNECT is refused.
    private val onWebTransport: (suspend WebTransportServerExchange.() -> Unit)? = null,
) {
    // MultiThreaded: per-stream handler coroutines allocate from this pool concurrently.
    private val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = config.bufferFactory)

    // The per-connection WebTransport engine (session table, stream/datagram demux, capsule protocol).
    // Null when WebTransport was not enabled for this server.
    private val webTransportMux: WebTransportMux? =
        if (webTransport != null) WebTransportMux(scope, pool, config) else null

    private val serverDecoder: QpackDecoder? =
        if (qpackCapacity > 0) QpackDecoder(qpackCapacity) { writeQpackDecoderInstruction(it) } else null

    @Volatile
    private var serverEncoder: QpackEncoder? = null
    private var qpackEncoderStream: QuicByteStream? = null
    private var qpackDecoderStream: QuicByteStream? = null
    private val encoderStreamWriteMutex = Mutex()
    private val decoderStreamWriteMutex = Mutex()

    // Server-push state (RFC 9114 §4.6). clientMaxPushId is the largest Push ID the client will accept,
    // learned from its MAX_PUSH_ID frame(s) on the control stream (-1 = push not enabled). The server
    // allocates push ids 0,1,… up to that limit. Guarded by pushIdMutex.
    @Volatile
    private var clientMaxPushId: Long = -1
    private val pushIdMutex = Mutex()
    private var nextPushId: Long = 0

    /** Run the HTTP/3 server role over [scope] (one accepted QUIC connection). Returns when it closes. */
    suspend fun serve() {
        writeControlStreamHeader(scope.openUniStream())
        if (qpackCapacity > 0) {
            qpackEncoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_ENCODER) }
            qpackDecoderStream = scope.openUniStream().also { writeStreamType(it, Http3StreamType.QPACK_DECODER) }
        }
        // WebTransport datagram demux (RFC 9297): a no-op when QUIC datagrams aren't enabled.
        webTransportMux?.startDatagramLoop()
        scope.streams().collect { stream ->
            scope.launch {
                try {
                    if (stream.streamId.isUnidirectional) handleUniStream(stream) else handleRequest(stream)
                } catch (_: Http3StreamException) {
                    // A single stream failing must not take the connection down.
                } catch (_: QuicStreamException) {
                    // Peer STOP_SENDING / RESET_STREAM on this one stream (e.g. a client cancelling a
                    // request mid-response) — stream-scoped; the connection keeps serving other streams.
                }
            }
        }
    }

    private suspend fun handleUniStream(stream: QuicByteStream) {
        val processor = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        // A peer-initiated unidirectional WebTransport stream outlives this call — the mux owns the
        // processor + stream lifetime, so the generic finally must not release the processor.
        var muxOwnsStream = false
        try {
            when (Http3StreamReader(stream, processor).nextVarInt()) {
                Http3StreamType.CONTROL -> handleControl(Http3StreamReader(stream, processor))
                Http3StreamType.QPACK_ENCODER ->
                    serverDecoder?.let { dec ->
                        val reader = QpackInstructionReader.encoder(stream, processor, pool)
                        while (true) dec.applyEncoderInstruction(reader.next(config.readPolicy.toDeadline()) ?: break)
                    } ?: drain(stream)
                Http3StreamType.QPACK_DECODER ->
                    if (serverDecoder != null) {
                        val reader = QpackInstructionReader.decoder(stream, processor)
                        while (true) serverEncoder?.processDecoderInstruction(reader.next(config.readPolicy.toDeadline()) ?: break)
                    } else {
                        drain(stream)
                    }
                // A peer-initiated unidirectional WebTransport stream (draft-ietf-webtrans-http3 §4.1).
                WebTransportWire.WT_UNI_STREAM_TYPE ->
                    webTransportMux?.let {
                        muxOwnsStream = true
                        it.acceptIncomingUni(stream, processor)
                    } ?: drain(stream)
                else -> drain(stream)
            }
        } finally {
            if (!muxOwnsStream) processor.release()
        }
    }

    /** Read the client control stream: its SETTINGS sizes our encoder; ignore subsequent frames. */
    private suspend fun handleControl(reader: Http3StreamReader) {
        val first = reader.nextFrame()
        if (qpackCapacity > 0 && first is Http3Frame.Settings) {
            val clientSettings = Http3Settings(first.entries)
            val clientMax = clientSettings.qpackMaxTableCapacity
            if (clientMax > 0) {
                serverEncoder =
                    QpackEncoder(clientMax, clientSettings.qpackBlockedStreams) { writeQpackEncoderInstruction(it) }
                        .also { it.setCapacity(minOf(qpackCapacity, clientMax)) }
            }
        }
        // Capture MAX_PUSH_ID so the server knows whether (and how much) it may push (RFC 9114 §7.2.7);
        // it is non-decreasing, so just take the latest. Other control frames are accepted + ignored.
        while (true) {
            val frame = reader.nextFrame() ?: break
            if (frame is Http3Frame.MaxPushId) clientMaxPushId = frame.pushId
        }
    }

    /** Read one request off a client bidi [stream], run [onRequest], then drain the body + FIN. */
    private suspend fun handleRequest(stream: QuicByteStream) {
        // Explicit processor so a WebTransport bidirectional stream's buffered prefix can be handed to
        // the mux (Http3StreamReader.create would hide the processor).
        val processor = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        val reader = Http3StreamReader(stream, processor)
        // An accepted WebTransport session (CONNECT stream) or a demultiplexed WebTransport bidirectional
        // stream keeps the reader/processor alive past this call, so the generic finally must not release it.
        var handlerOwnsReader = false
        try {
            // A peer-initiated WebTransport bidirectional stream (draft-ietf-webtrans-http3 §4.2) starts
            // with the 0x41 signal, not a HEADERS frame — demultiplex it before request parsing.
            val mux = webTransportMux
            if (mux != null &&
                reader.peekVarInt(config.readPolicy.toDeadline()) == WebTransportWire.WT_BIDI_STREAM_SIGNAL
            ) {
                handlerOwnsReader = true
                mux.acceptIncomingBidi(stream, processor)
                return
            }
            val first =
                reader.nextFrame(config.readPolicy.toDeadline())
                    ?: throw Http3StreamException("request stream ended before a HEADERS frame", Http3ErrorCode.REQUEST_INCOMPLETE)
            if (first !is Http3Frame.Headers) {
                throw Http3StreamException(
                    "request's first frame was ${first::class.simpleName}, expected HEADERS",
                    Http3ErrorCode.FRAME_UNEXPECTED,
                )
            }
            val streamId = stream.streamId.id
            val fields = decodeSection(first.encodedFieldSection, streamId)
            // Extended CONNECT (RFC 9220): a CONNECT carrying `:protocol` is routed to WebTransport,
            // not the normal request handler.
            if (pseudoOrNull(fields, ":method") == "CONNECT" && pseudoOrNull(fields, ":protocol") != null) {
                handlerOwnsReader = handleWebTransport(stream, reader, fields)
                return
            }
            val request =
                Http3ServerRequest(
                    method = pseudo(fields, ":method"),
                    scheme = pseudo(fields, ":scheme"),
                    authority = pseudo(fields, ":authority"),
                    path = pseudo(fields, ":path"),
                    headers = fields.filterNot { it.name.startsWith(":") },
                    reader = reader,
                    pool = pool,
                    readTimeout = config.readPolicy.toDeadline(),
                    decodeFields = { decodeSection(it, streamId) },
                )
            val response =
                Http3ServerResponse(stream, pool, config, streamId) { sectionFields, sid -> encodeSection(sectionFields, sid) }
            val exchange =
                Http3ServerExchange(request, response) { spec, respond -> pushResource(stream, spec, respond) }
            try {
                exchange.onRequest()
            } finally {
                runCatching { request.drain() }
                runCatching { response.finish() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Http3StreamException) {
            reactToRequestError(stream, e)
            throw e
        } finally {
            if (!handlerOwnsReader) reader.release()
        }
    }

    /**
     * Handle an Extended CONNECT for WebTransport (RFC 9220): refuse unless WebTransport is enabled,
     * the registered [onWebTransport] handler accepts it, and the session limit isn't exceeded.
     * Returns true if a session was accepted (the caller must keep the stream reader alive); false
     * if the CONNECT was rejected (a response was sent and the reader can be released).
     */
    private suspend fun handleWebTransport(
        stream: QuicByteStream,
        reader: Http3StreamReader,
        fields: List<QpackHeaderField>,
    ): Boolean {
        val handler = onWebTransport
        val limit = webTransport?.maxSessions ?: 0L
        if (handler == null || limit <= 0 || pseudoOrNull(fields, ":protocol") != WEBTRANSPORT_PROTOCOL) {
            sendWebTransportResponse(stream, 501, fin = true)
            return false
        }
        if ((webTransportMux?.activeCount()?.toLong() ?: 0L) >= limit) {
            // RFC 9220: refuse beyond the advertised WEBTRANSPORT_MAX_SESSIONS.
            sendWebTransportResponse(stream, 429, fin = true)
            return false
        }
        var acceptedSession: WebTransportSession? = null
        var decided = false
        val exchange =
            WebTransportServerExchange(
                authority = pseudoOrNull(fields, ":authority") ?: "",
                path = pseudoOrNull(fields, ":path") ?: "",
                headers = fields.filterNot { it.name.startsWith(":") },
                doAccept = {
                    decided = true
                    acceptWebTransport(stream, reader).also { acceptedSession = it }
                },
                doReject = { status ->
                    decided = true
                    sendWebTransportResponse(stream, status, fin = true)
                },
            )
        try {
            handler.invoke(exchange)
        } finally {
            // A handler that returns without deciding implicitly rejects.
            if (!decided) runCatching { sendWebTransportResponse(stream, 404, fin = true) }
        }
        return acceptedSession != null
    }

    /** Accept a WebTransport session: register it, then send 200 (no FIN — the CONNECT stream stays open). */
    private suspend fun acceptWebTransport(
        stream: QuicByteStream,
        reader: Http3StreamReader,
    ): WebTransportSession {
        val mux = webTransportMux ?: error("WebTransport accepted without an enabled mux")
        // Table the session BEFORE the 200 can be observed by the client. The client may open a
        // WebTransport stream the instant it sees the 200 (draft-ietf-webtrans-http3 §4.2); if that
        // stream reaches our demux before the session is registered, the demux treats it as orphaned and
        // resets it. Registering first makes the session visible no later than the response itself.
        val session = mux.preRegister(stream)
        try {
            sendWebTransportResponse(stream, 200, fin = false)
        } catch (e: Throwable) {
            mux.abandon(session) // CONNECT response never reached the peer — untable the half-open session.
            throw e
        }
        // The CONNECT stream stays open; its capsule loop owns the reader and ends the session on FIN
        // or a WT_CLOSE_SESSION capsule.
        mux.activate(session, reader)
        return session
    }

    /** Send a CONNECT response carrying just `:status`; [fin] half-closes the send side (reject path). */
    private suspend fun sendWebTransportResponse(
        stream: QuicByteStream,
        status: Int,
        fin: Boolean,
    ) {
        val section = encodeSection(listOf(QpackHeaderField(":status", status.toString())), stream.streamId.id)
        try {
            writeFrame(stream, Http3Frame.Headers(section))
        } finally {
            section.freeIfNeeded()
        }
        if (fin) stream.shutdownSend()
    }

    /**
     * React to a violation while reading a request (RFC 9114 §8): a malformed *message*
     * ([Http3ErrorCode.MESSAGE_ERROR]) resets just this stream; an invalid frame *sequence*
     * ([Http3ErrorCode.FRAME_UNEXPECTED]) is a connection error, so the whole connection is closed
     * with the code. Best-effort — a failure because the stream/connection is already gone is ignored.
     */
    private suspend fun reactToRequestError(
        stream: QuicByteStream,
        error: Http3StreamException,
    ) {
        try {
            when (error.errorCode) {
                Http3ErrorCode.FRAME_UNEXPECTED -> scope.closeWithError(error.errorCode)
                else -> stream.reset(error.errorCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already torn down.
        }
    }

    /** Encode a field section through the dynamic encoder (when present) or the static codec. */
    private suspend fun encodeSection(
        fields: List<QpackHeaderField>,
        streamId: Long,
    ): ReadBuffer {
        val encoder = serverEncoder
        return if (encoder != null) {
            encoder.encodeSection(fields, streamId, pool)
        } else {
            val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
            pool.allocate(size).also {
                QpackFieldSectionCodec.encode(it, fields, EncodeContext.Empty)
                it.resetForRead()
            }
        }
    }

    /** Decode a field section through the dynamic decoder (when present) or the static codec. */
    private suspend fun decodeSection(
        section: ReadBuffer,
        streamId: Long,
    ): List<QpackHeaderField> {
        val decoder = serverDecoder
        return if (decoder != null) {
            decoder.decodeSection(section, streamId, pool)
        } else {
            QpackFieldSectionCodec.decode(section, DecodeContext.Empty.with(QpackScratchPoolKey, pool))
        }
    }

    // --- Server push (RFC 9114 §4.6), driven by Http3ServerExchange.push ---

    /**
     * Push a resource for the request on [requestStream]: allocate a Push ID within the client's
     * MAX_PUSH_ID limit, send a PUSH_PROMISE (the promised request fields) on the request stream, then
     * write the pushed response on a fresh push stream (`0x01` + Push ID). Returns false — without
     * sending anything — when push is disabled or out of credit.
     *
     * The PUSH_PROMISE is sent synchronously (it rides the request stream, so it must precede that
     * stream's FIN and keep promise order), but the **push stream's body is written concurrently** in a
     * child coroutine: this returns as soon as the promise is on the wire, so a handler can push several
     * resources and write its main response without blocking on the pushed bodies. The push-write
     * inherits the connection scope, so it is cancelled if the connection closes first; a failure on it
     * never takes the connection down. [QpackEncoder.encodeSection] is internally serialized, so the
     * concurrent push bodies and the main response share the dynamic encoder safely.
     */
    private suspend fun pushResource(
        requestStream: QuicByteStream,
        spec: PushPromiseSpec,
        respond: suspend Http3ServerResponse.() -> Unit,
    ): Boolean {
        val pushId =
            pushIdMutex.withLock {
                if (clientMaxPushId < 0 || nextPushId > clientMaxPushId) return false
                nextPushId++
            }
        val promiseFields =
            buildList {
                add(QpackHeaderField(":method", spec.method))
                add(QpackHeaderField(":scheme", spec.scheme))
                add(QpackHeaderField(":authority", spec.authority))
                add(QpackHeaderField(":path", spec.path))
                addAll(spec.promisedHeaders)
            }
        // PUSH_PROMISE rides the request stream; its field section uses that stream's QPACK context.
        val section = encodeSection(promiseFields, requestStream.streamId.id)
        try {
            writeFrame(requestStream, Http3Frame.PushPromise(pushId, section))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            section.freeIfNeeded()
            return false // request stream already finished/reset — nothing was promised
        }
        section.freeIfNeeded()

        scope.launch {
            try {
                val pushStream = scope.openUniStream()
                writePushStreamHeader(pushStream, pushId)
                val pushResponse =
                    Http3ServerResponse(pushStream, pool, config, pushStream.streamId.id) { fields, sid -> encodeSection(fields, sid) }
                pushResponse.respond()
                pushResponse.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The pushed response failed (peer STOP_SENDING, connection gone) — the connection survives.
            }
        }
        return true
    }

    /** Push stream header (RFC 9114 §4.6): the `0x01` stream-type prefix followed by the Push ID. */
    private suspend fun writePushStreamHeader(
        stream: QuicByteStream,
        pushId: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.PUSH) + VarIntCodec.encodedLength(pushId))
        try {
            VarIntCodec.encode(buffer, Http3StreamType.PUSH, EncodeContext.Empty)
            VarIntCodec.encode(buffer, pushId, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, config.writePolicy.toDeadline())
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Encode [frame] into a pooled buffer and write the whole frame to [stream]. */
    private suspend fun writeFrame(
        stream: QuicByteStream,
        frame: Http3Frame,
    ) {
        // The generated framed encode owns allocation (slicing scheme over the
        // pool) and returns a ReadBuffer spanning exactly the frame's wire bytes.
        val buffer = Http3FrameCodec.encode(frame, EncodeContext.Empty, pool)
        try {
            stream.write(buffer, config.writePolicy.toDeadline())
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Control stream: the type prefix `0x00` immediately followed by the SETTINGS frame. */
    private suspend fun writeControlStreamHeader(control: QuicByteStream) {
        val entries =
            mutableListOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, qpackCapacity),
                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, if (qpackCapacity > 0) BLOCKED_STREAMS else 0L),
            )
        webTransport?.let { entries += webTransportSettings(it) }
        val settings = Http3Frame.Settings(entries)
        val prefix = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL))
        try {
            VarIntCodec.encode(prefix, Http3StreamType.CONTROL, EncodeContext.Empty)
            prefix.resetForRead()
            control.write(prefix, config.writePolicy.toDeadline())
        } finally {
            prefix.freeIfNeeded()
        }
        val frame = Http3FrameCodec.encode(settings, EncodeContext.Empty, pool)
        try {
            control.write(frame, config.writePolicy.toDeadline())
        } finally {
            frame.freeIfNeeded()
        }
    }

    private suspend fun writeStreamType(
        stream: QuicByteStream,
        type: Long,
    ) {
        val buffer = pool.allocate(VarIntCodec.encodedLength(type))
        try {
            VarIntCodec.encode(buffer, type, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, config.writePolicy.toDeadline())
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
            encoderStreamWriteMutex.withLock { stream.write(buffer, config.writePolicy.toDeadline()) }
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
            decoderStreamWriteMutex.withLock { stream.write(buffer, config.writePolicy.toDeadline()) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private suspend fun drain(stream: QuicByteStream) {
        while (true) {
            when (val result = stream.read(config.readPolicy.toDeadline())) {
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
            ?: throw Http3StreamException("request HEADERS missing the $name pseudo-header", Http3ErrorCode.MESSAGE_ERROR)

    /** Like [pseudo] but returns null instead of throwing when the pseudo-header is absent. */
    private fun pseudoOrNull(
        fields: List<QpackHeaderField>,
        name: String,
    ): String? = fields.firstOrNull { it.name == name }?.value

    companion object {
        /** Blocked-streams we permit the client's encoder to create when dynamic QPACK is on. */
        private const val BLOCKED_STREAMS: Long = 100
    }
}
