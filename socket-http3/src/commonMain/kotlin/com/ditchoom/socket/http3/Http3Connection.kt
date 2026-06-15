package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Parsed view of an HTTP/3 peer SETTINGS frame (RFC 9114 §7.2.4 / RFC 9204 §5).
 *
 * Wraps the raw [entries] and surfaces the settings this client cares about. A setting
 * the peer omits takes its protocol default: QPACK capacity and blocked-streams default
 * to 0 (RFC 9204 §5), [maxFieldSectionSize] is unbounded (`null`), and the Extended
 * CONNECT protocol (RFC 9220, needed for WebTransport) is off unless advertised as 1.
 */
data class Http3Settings(
    val entries: List<Http3Setting>,
) {
    private fun value(id: Long): Long? = entries.firstOrNull { it.identifier == id }?.value

    val qpackMaxTableCapacity: Long get() = value(Http3SettingId.QPACK_MAX_TABLE_CAPACITY) ?: 0L
    val qpackBlockedStreams: Long get() = value(Http3SettingId.QPACK_BLOCKED_STREAMS) ?: 0L
    val maxFieldSectionSize: Long? get() = value(Http3SettingId.MAX_FIELD_SECTION_SIZE)
    val enableConnectProtocol: Boolean get() = value(Http3SettingId.ENABLE_CONNECT_PROTOCOL) == 1L

    /** RFC 9297 — the peer enabled HTTP Datagrams (`H3_DATAGRAM = 1`). Required for WebTransport. */
    val h3DatagramEnabled: Boolean get() = value(Http3SettingId.H3_DATAGRAM) == 1L

    /**
     * draft-ietf-webtrans-http3 — the number of concurrent WebTransport sessions the peer will
     * accept (`WEBTRANSPORT_MAX_SESSIONS`); 0 / absent means it accepts none.
     */
    val wtMaxSessions: Long get() = value(Http3SettingId.WEBTRANSPORT_MAX_SESSIONS) ?: 0L

    /**
     * Legacy draft-02 WebTransport toggle (`SETTINGS_ENABLE_WEBTRANSPORT = 1`). Superseded by
     * [wtMaxSessions], but still the *only* WebTransport setting some widely-deployed reference stacks
     * advertise (e.g. aioquic 1.3.0). We send both (see [webTransportSettings]); to interop with those
     * peers we must also *accept* the legacy one — hence its place in [webTransportSupported].
     */
    val enableWebTransportLegacy: Boolean get() = value(Http3SettingId.ENABLE_WEBTRANSPORT) == 1L

    /**
     * The peer can accept WebTransport sessions we initiate: it advertised Extended CONNECT
     * (RFC 9220), HTTP Datagrams (RFC 9297), and a session limit. `WEBTRANSPORT_MAX_SESSIONS` is
     * **authoritative when present** — an explicit `0` means the peer accepts none, so a legacy
     * `ENABLE_WEBTRANSPORT = 1` advertised alongside it does NOT override that (an endpoint sends both
     * but `0` is the real limit; cf. our own initiate-only `webTransportSettings`). The legacy toggle is
     * the gate only when `WEBTRANSPORT_MAX_SESSIONS` is **absent** — a true draft-02 peer (e.g. aioquic
     * 1.3.0) sends no count, so a bare enable means "at least one". Gate `connectWebTransport` on this.
     */
    val webTransportSupported: Boolean
        get() {
            if (!enableConnectProtocol || !h3DatagramEnabled) return false
            val maxSessions = value(Http3SettingId.WEBTRANSPORT_MAX_SESSIONS)
            return if (maxSessions != null) maxSessions > 0 else enableWebTransportLegacy
        }
}

/**
 * A bootstrapped HTTP/3 client connection layered over a [QuicScope] (RFC 9114 §3.2).
 *
 * [bootstrap] opens the three client-initiated unidirectional streams HTTP/3 requires —
 * the control stream (carrying the client's SETTINGS as its first frame, RFC 9114 §6.2.1)
 * and the QPACK encoder/decoder streams (RFC 9204 §4.2) — and launches a router over
 * [QuicScope.streams] that reads each peer-initiated uni stream's type prefix and dispatches
 * it. The peer's control stream resolves [peerSettings].
 *
 * The connection's lifetime is the enclosing [QuicScope] block: the router runs as a child
 * coroutine, the control/QPACK streams stay open (sending FIN on the control stream is a
 * protocol error), and the scope force-closes everything when its block returns.
 *
 * QPACK is fully dynamic (RFC 9204): the client advertises a non-zero `QPACK_MAX_TABLE_CAPACITY` /
 * `QPACK_BLOCKED_STREAMS`, decodes the peer's dynamically-compressed responses via a [QpackDecoder]
 * fed by the peer's encoder stream (blocking on the Required Insert Count when needed), and — once
 * the peer's SETTINGS reveal a usable table — compresses its own requests via a [QpackEncoder] that
 * inserts on the client encoder stream. Acks flow on the client decoder stream. Against a capacity-0
 * peer (or before SETTINGS arrive) the encoder degrades to static/literal, which is always legal.
 *
 * This is the bootstrap half of HTTP/3; request/response (bidi HEADERS+DATA) is layered on
 * top separately.
 */
class Http3Connection private constructor(
    private val scope: QuicScope,
    private val config: TransportConfig,
    private val pool: BufferPool,
    private val controlStream: QuicByteStream,
    private val qpackEncoderStream: QuicByteStream,
    private val qpackDecoderStream: QuicByteStream,
    // The maximum Push ID the client allows the server to use (RFC 9114 §7.2.7), or -1 when server
    // push is disabled. When >= 0 a MAX_PUSH_ID frame is sent at bootstrap and the server may push
    // ids 0..maxPushId; when -1 no MAX_PUSH_ID is sent and any PUSH_PROMISE / push stream is an
    // H3_ID_ERROR connection violation (RFC 9114 §7.2.5 / §4.6).
    private val maxPushId: Long,
    // WebTransport participation (RFC 9220 + RFC 9297). Null disables it: no WT SETTINGS are
    // advertised, so neither role establishes WebTransport sessions. Non-null advertises the WT
    // SETTINGS at bootstrap; [WebTransportOptions.maxSessions] is the inbound accept limit.
    private val webTransport: WebTransportOptions?,
) {
    /**
     * The [BufferFactory] this connection allocates from — the underlying QUIC connection's factory
     * (see [com.ditchoom.socket.quic.QuicScope.bufferFactory] /
     * [com.ditchoom.socket.quic.network]). Allocate request/response and WebTransport buffers from
     * here, paired with `use { }`, so they match the connection's native-memory allocation strategy.
     */
    val bufferFactory: BufferFactory get() = scope.bufferFactory

    private val peerSettingsDeferred = CompletableDeferred<Http3Settings>()
    private val _goAway = MutableStateFlow<Long?>(null)
    private val connectionErrorDeferred = CompletableDeferred<Http3StreamException>()

    // --- Server push (RFC 9114 §4.6) ---
    // pushEntries correlates a PUSH_PROMISE (promise half, on a request stream) with its push stream
    // (response half), which may arrive in either order. Each entry's responseDeferred is completed
    // by whichever push stream carries the matching Push ID. A push is emitted to `pushes` as soon as
    // its promise is seen. Both halves and cancellation are serialized by pushMutex.
    private val pushMutex = Mutex()
    private val pushEntries = mutableMapOf<Long, PushEntry>()
    private val pushChannel = Channel<Http3ServerPush>(Channel.UNLIMITED)
    private val controlStreamWriteMutex = Mutex()

    // --- WebTransport (RFC 9220 + draft-ietf-webtrans-http3) ---
    // The per-connection WebTransport engine: session table, stream/datagram demux, capsule protocol.
    // Null when WebTransport was not enabled at bootstrap (no WT SETTINGS advertised).
    private val webTransportMux: WebTransportMux? =
        if (webTransport != null) WebTransportMux(scope, pool, config) else null

    // The live push-id limit (RFC 9114 §7.2.7). Starts at the advertised maxPushId and is re-issued
    // upward (never down) as pushes are observed, so the server keeps a rolling window of credit
    // rather than a hard lifetime cap of maxPushId+1 pushes. -1 when push is disabled. Guarded by pushMutex.
    @Volatile
    private var currentMaxPushId: Long = maxPushId

    private class PushEntry {
        val responseDeferred = CompletableDeferred<Http3Response>()
        var promised = false

        @Volatile
        var pushStream: QuicByteStream? = null

        @Volatile
        var cancelled = false
    }

    /**
     * Server pushes (RFC 9114 §4.6), each emitted when its PUSH_PROMISE is received. Empty unless
     * push was enabled at [bootstrap] (a non-negative `maxPushId`). Consume an [Http3ServerPush] by
     * awaiting [Http3ServerPush.response] (then reading/closing it) or declining via
     * [Http3ServerPush.cancel]. The flow completes when the connection closes.
     */
    val pushes: Flow<Http3ServerPush> get() = pushChannel.receiveAsFlow()

    @Volatile
    private var connectionErrorOrNull: Http3StreamException? = null

    /**
     * The first fatal connection-level protocol error detected on a critical stream (the peer's
     * control stream — RFC 9114 §8), or null if none. Carries the [Http3ErrorCode] the endpoint
     * would put on a CONNECTION_CLOSE. Once non-null the connection is unusable; callers should stop
     * issuing [request]s. See [awaitConnectionError] to suspend until one occurs.
     */
    val connectionError: Http3StreamException? get() = connectionErrorOrNull

    /** Suspends until a fatal connection-level protocol error is detected, then returns it. */
    suspend fun awaitConnectionError(): Http3StreamException = connectionErrorDeferred.await()

    // --- QPACK (RFC 9204) ---
    // Our decoder is live from bootstrap (the peer's encoder stream populates it; it decodes responses
    // — static RIC=0 sections too). Our encoder is created only once the peer's SETTINGS reveal a
    // non-zero QPACK_MAX_TABLE_CAPACITY; until then (and against a capacity-0 peer) requests encode
    // statically. The write mutexes serialize bytes on each single-writer QPACK uni stream, since
    // multiple coroutines (the router + per-request encodes/decodes) emit on them concurrently.
    private val decoder = QpackDecoder(QPACK_MAX_TABLE_CAPACITY) { writeDecoderInstruction(it) }

    @Volatile
    private var encoder: QpackEncoder? = null
    private val encoderStreamWriteMutex = Mutex()
    private val decoderStreamWriteMutex = Mutex()

    /**
     * The last client-initiated stream id the peer will process, from a server GOAWAY frame
     * (RFC 9114 §7.2.6), or null until one is received. Once non-null, callers should stop
     * issuing [request]s — the server is shutting the connection down gracefully. Holds the
     * lowest id seen across repeated GOAWAYs (ids are non-increasing).
     */
    val goAway: StateFlow<Long?> get() = _goAway.asStateFlow()

    /**
     * Suspends until the peer's control stream has delivered its SETTINGS frame (RFC 9114
     * §7.2.4), then returns the parsed [Http3Settings]. Throws [Http3StreamException] if the
     * connection closes, or the control stream errors, before SETTINGS arrive — or whatever
     * the first frame was, if it wasn't SETTINGS (a protocol violation).
     */
    suspend fun peerSettings(): Http3Settings = peerSettingsDeferred.await()

    /**
     * Issues an HTTP/3 request on a fresh client-initiated bidirectional stream (RFC 9114 §4.1)
     * and returns once the response's HEADERS frame has been read and decoded; the body streams
     * lazily via the returned [Http3Response].
     *
     * Sends the request HEADERS frame (pseudo-headers first, QPACK static-table-only), an
     * optional single DATA frame for [Http3Request.body], then a send-side FIN (half-close) so
     * the read side stays open for the response. Deliberately does **not** wait on
     * [peerSettings] — a plain request depends on no peer setting (WebTransport's Extended
     * CONNECT, which does, runs its own check).
     *
     * The caller must [Http3Response.close] the response (or consume it via
     * [Http3Response.readFullBody] then close) to release stream-reader buffers.
     */
    suspend fun request(request: Http3Request): Http3Response {
        val stream = openRequestStream(request.method, request.scheme, request.authority, request.path, request.headers)
        request.body?.let { writeFrame(stream, Http3Frame.Data(it)) }
        stream.shutdownSend()
        return readResponse(stream)
    }

    /**
     * Streaming-body variant of [request]: writes the request HEADERS, then hands an
     * [Http3RequestBody] to [body] so the caller can write the request body as one or more DATA
     * frames (e.g. a large upload, or a body produced incrementally), and finally FINs the send
     * side and reads the response. Pseudo-headers are sent first (RFC 9114 §4.3.1), exactly as in
     * the buffered overload.
     */
    suspend fun request(
        method: String,
        authority: String,
        path: String,
        scheme: String = "https",
        headers: List<QpackHeaderField> = emptyList(),
        body: suspend Http3RequestBody.() -> Unit,
    ): Http3Response {
        val stream = openRequestStream(method, scheme, authority, path, headers)
        Http3RequestBody(stream).body()
        stream.shutdownSend()
        return readResponse(stream)
    }

    /**
     * Open a WebTransport session (RFC 9220 Extended CONNECT) to [authority]/[path] over this HTTP/3
     * connection, returning once the server's 2xx response confirms it. Multiple sessions may share
     * the connection (each on its own CONNECT stream); the session id is the CONNECT stream id.
     *
     * Requires that this connection was bootstrapped with a [WebTransportOptions] (so we advertised
     * Extended CONNECT + HTTP Datagrams) and that the peer advertised WebTransport support — this
     * suspends on [peerSettings] to check [Http3Settings.webTransportSupported] first.
     *
     * @throws WebTransportException if WebTransport isn't enabled locally, the peer doesn't support
     *   it, or the server rejects the CONNECT (any non-2xx status).
     */
    suspend fun connectWebTransport(
        authority: String,
        path: String,
        headers: List<QpackHeaderField> = emptyList(),
    ): WebTransportSession {
        val mux =
            webTransportMux ?: throw WebTransportException(
                "WebTransport is not enabled on this connection — pass WebTransportOptions to withHttp3Connection()/bootstrap()",
            )
        if (!peerSettings().webTransportSupported) {
            throw WebTransportException("the peer did not advertise WebTransport support (Extended CONNECT + H3_DATAGRAM + sessions)")
        }
        val stream = openExtendedConnectStream(WEBTRANSPORT_PROTOCOL, authority, path, headers)
        // Table the session by its CONNECT stream id immediately, so a WebTransport stream/datagram the
        // server sends the instant it accepts can't race ahead of registration. Abandoned if rejected.
        val session = mux.preRegister(stream)
        val reader = Http3StreamReader.create(stream, pool)
        val status =
            try {
                readConnectStatus(stream, reader)
            } catch (t: Throwable) {
                reader.release()
                mux.abandon(session)
                resetStreamQuietly(stream, Http3ErrorCode.REQUEST_CANCELLED)
                throw t
            }
        if (status !in 200..299) {
            reader.release()
            mux.abandon(session)
            resetStreamQuietly(stream, Http3ErrorCode.REQUEST_CANCELLED)
            throw WebTransportException("WebTransport CONNECT to $authority$path was rejected with status $status")
        }
        // Confirmed: hand the CONNECT-stream reader to the session's capsule loop.
        mux.activate(session, reader)
        return session
    }

    /** Open a client bidi stream and write an Extended CONNECT HEADERS frame (`:protocol` included). */
    private suspend fun openExtendedConnectStream(
        protocol: String,
        authority: String,
        path: String,
        headers: List<QpackHeaderField>,
    ): QuicByteStream {
        val stream = scope.openStream()
        // Extended CONNECT (RFC 9220 §4): :method=CONNECT, :protocol, plus :scheme/:authority/:path.
        val fields =
            buildList {
                add(QpackHeaderField(":method", "CONNECT"))
                add(QpackHeaderField(":protocol", protocol))
                add(QpackHeaderField(":scheme", "https"))
                add(QpackHeaderField(":authority", authority))
                add(QpackHeaderField(":path", path))
                addAll(headers)
            }
        writeHeadersFrame(stream, fields)
        return stream
    }

    /** Read frames off the CONNECT [reader] until the response HEADERS, returning its :status. */
    private suspend fun readConnectStatus(
        stream: QuicByteStream,
        reader: Http3StreamReader,
    ): Int {
        while (true) {
            when (val frame = reader.nextFrame(config.readPolicy.toDeadline())) {
                null ->
                    throw Http3StreamException(
                        "CONNECT stream ended before a response HEADERS frame",
                        Http3ErrorCode.REQUEST_INCOMPLETE,
                    )
                is Http3Frame.Headers ->
                    return parseStatus(decoder.decodeSection(frame.encodedFieldSection, stream.streamId.id, pool))
                // Unknown/reserved frames are ignored (RFC 9114 §9); anything else before HEADERS is invalid.
                is Http3Frame.Unknown -> {}
                else ->
                    throw Http3StreamException(
                        "unexpected ${frame::class.simpleName} before the CONNECT response HEADERS",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
            }
        }
    }

    /** Encode [fields] as a HEADERS field section (dynamic encoder when available) and write the frame. */
    private suspend fun writeHeadersFrame(
        stream: QuicByteStream,
        fields: List<QpackHeaderField>,
    ) {
        val activeEncoder = encoder
        val sectionBuffer =
            if (activeEncoder != null) {
                activeEncoder.encodeSection(fields, stream.streamId.id, pool)
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
    }

    /** Open a client bidi stream and write the request HEADERS frame (pseudo-headers first). */
    private suspend fun openRequestStream(
        method: String,
        scheme: String,
        authority: String,
        path: String,
        headers: List<QpackHeaderField>,
    ): QuicByteStream {
        val stream = scope.openStream()
        val fields =
            buildList {
                add(QpackHeaderField(":method", method))
                add(QpackHeaderField(":scheme", scheme))
                add(QpackHeaderField(":authority", authority))
                add(QpackHeaderField(":path", path))
                addAll(headers)
            }
        writeHeadersFrame(stream, fields)
        return stream
    }

    /** Read a response off a request [stream]: the first HEADERS frame gives status + headers; body streams. */
    private suspend fun readResponse(stream: QuicByteStream): Http3Response {
        val reader = Http3StreamReader.create(stream, pool)
        // PUSH_PROMISE on a request stream is decoded against this stream's id (RFC 9204 stream context).
        val onPushPromise: suspend (Http3Frame.PushPromise) -> Unit = { onPushPromiseFrame(it, stream.streamId.id) }
        try {
            return readResponseHead(stream, reader, onPushPromise)
        } catch (t: Throwable) {
            reader.release()
            if (t is Http3StreamException) reactToResponseError(stream, t)
            throw t
        }
    }

    /**
     * Reads frames off [reader] until the response's first HEADERS frame, then builds the
     * [Http3Response] (carrying [onPushPromise] for body-interleaved promises). PUSH_PROMISE frames
     * before the HEADERS are routed to [onPushPromise]; null (a push stream) rejects them as
     * H3_FRAME_UNEXPECTED. Does no cleanup — the caller's try/finally owns reader/stream lifetime.
     */
    private suspend fun readResponseHead(
        stream: QuicByteStream,
        reader: Http3StreamReader,
        onPushPromise: (suspend (Http3Frame.PushPromise) -> Unit)?,
    ): Http3Response {
        while (true) {
            when (val frame = reader.nextFrame(config.readPolicy.toDeadline())) {
                // Stream ended before the response message began (RFC 9114 §4.1).
                null ->
                    throw Http3StreamException(
                        "response stream ended before a HEADERS frame",
                        Http3ErrorCode.REQUEST_INCOMPLETE,
                    )
                is Http3Frame.Headers -> {
                    val streamId = stream.streamId.id
                    val decoded = decoder.decodeSection(frame.encodedFieldSection, streamId, pool)
                    val status = parseStatus(decoded)
                    val headers = decoded.filterNot { it.name.startsWith(":") }
                    // Trailers (a later field section on this stream) decode through the same decoder.
                    return Http3Response(
                        status,
                        headers,
                        stream,
                        reader,
                        pool,
                        config.readPolicy.toDeadline(),
                        onPushPromise,
                    ) { trailerSection ->
                        decoder.decodeSection(trailerSection, streamId, pool)
                    }
                }
                // PUSH_PROMISE may appear on a request stream before/among the response frames
                // (RFC 9114 §7.2.5): register the promise + emit the push, then keep reading. On a
                // push stream (onPushPromise == null) it is an invalid sequence (§7.2.5).
                is Http3Frame.PushPromise ->
                    (
                        onPushPromise ?: throw Http3StreamException(
                            "PUSH_PROMISE on a push stream",
                            Http3ErrorCode.FRAME_UNEXPECTED,
                        )
                    )(frame)
                // Unknown/reserved frame types MUST be ignored (RFC 9114 §9).
                is Http3Frame.Unknown -> {}
                // Any other frame before the response's first HEADERS — DATA, SETTINGS, GOAWAY,
                // … — is an invalid sequence on a request stream: H3_FRAME_UNEXPECTED (§4.1).
                else ->
                    throw Http3StreamException(
                        "unexpected ${frame::class.simpleName} before the response HEADERS frame",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
            }
        }
    }

    /**
     * React to a violation detected while reading a response, per its scope (RFC 9114 §8): an
     * invalid frame *sequence* ([Http3ErrorCode.FRAME_UNEXPECTED]) is a connection error, so the
     * whole connection is [aborted][abortConnection]; a malformed *message*
     * ([Http3ErrorCode.MESSAGE_ERROR]) is stream-scoped (§4.1.2), so only this request stream is
     * reset. Other errors (a clean [Http3ErrorCode.REQUEST_INCOMPLETE], a peer reset) need no action.
     */
    private suspend fun reactToResponseError(
        stream: QuicByteStream,
        error: Http3StreamException,
    ) {
        when (error.errorCode) {
            Http3ErrorCode.FRAME_UNEXPECTED -> abortConnection(error)
            Http3ErrorCode.MESSAGE_ERROR -> resetStreamQuietly(stream, Http3ErrorCode.MESSAGE_ERROR)
        }
    }

    /** Reset [stream] with [errorCode], ignoring a failure if the connection/stream is already gone. */
    private suspend fun resetStreamQuietly(
        stream: QuicByteStream,
        errorCode: Long,
    ) {
        try {
            stream.reset(errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Already torn down — nothing to reset.
        }
    }

    /**
     * Sink for streaming a request body as DATA frames (RFC 9114 §4.1), handed to the
     * streaming [request] overload. Each [write] emits one DATA frame; the framework FINs the
     * stream after the body lambda returns.
     */
    inner class Http3RequestBody internal constructor(
        private val stream: QuicByteStream,
    ) {
        /** Send [chunk]'s remaining bytes as a single DATA frame. */
        suspend fun write(chunk: ReadBuffer) {
            if (chunk.remaining() > 0) writeFrame(stream, Http3Frame.Data(chunk))
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

    /** Encode and write one QPACK encoder-stream instruction (RFC 9204 §4.3) on our encoder uni stream. */
    private suspend fun writeEncoderInstruction(instruction: QpackEncoderInstruction) {
        val capacity =
            when (instruction) {
                is QpackEncoderInstruction.InsertWithNameRef -> 32 + qpackUtf8ByteLength(instruction.value)
                is QpackEncoderInstruction.InsertWithLiteralName ->
                    32 + qpackUtf8ByteLength(instruction.name) + qpackUtf8ByteLength(instruction.value)
                else -> 32 // SetCapacity / Duplicate: a single prefixed integer
            }
        val buffer = pool.allocate(capacity)
        try {
            QpackEncoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            encoderStreamWriteMutex.withLock { qpackEncoderStream.write(buffer, config.writePolicy.toDeadline()) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Encode and write one QPACK decoder-stream instruction (RFC 9204 §4.4) on our decoder uni stream. */
    private suspend fun writeDecoderInstruction(instruction: QpackDecoderInstruction) {
        val buffer = pool.allocate(16) // a single prefixed integer (≤ ~9 bytes)
        try {
            QpackDecoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            decoderStreamWriteMutex.withLock { qpackDecoderStream.write(buffer, config.writePolicy.toDeadline()) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private fun parseStatus(fields: List<QpackHeaderField>): Int {
        val raw =
            fields.firstOrNull { it.name == ":status" }?.value
                ?: throw Http3StreamException(
                    "response HEADERS missing the :status pseudo-header",
                    Http3ErrorCode.MESSAGE_ERROR,
                )
        return raw.toIntOrNull()
            ?: throw Http3StreamException("response :status was not a number: \"$raw\"", Http3ErrorCode.MESSAGE_ERROR)
    }

    /**
     * Writes the client's stream-type prefixes and control-stream SETTINGS, then launches the
     * peer-stream router. Run once, by [bootstrap], before the connection is handed out.
     */
    private suspend fun start() {
        writeControlStreamHeader()
        writeStreamType(qpackEncoderStream, Http3StreamType.QPACK_ENCODER)
        writeStreamType(qpackDecoderStream, Http3StreamType.QPACK_DECODER)
        // Enable server push (RFC 9114 §7.2.7): tell the server the highest push id it may use.
        if (maxPushId >= 0) writeControlFrame(Http3Frame.MaxPushId(maxPushId))
        startRouter()
        startEncoderSetup()
        // WebTransport datagram demux (RFC 9297): a no-op when QUIC datagrams aren't enabled.
        webTransportMux?.startDatagramLoop()
    }

    /** Write a frame on the client control stream after its header (MAX_PUSH_ID / CANCEL_PUSH). */
    private suspend fun writeControlFrame(frame: Http3Frame) {
        controlStreamWriteMutex.withLock { writeFrame(controlStream, frame) }
    }

    /**
     * Once the peer's SETTINGS arrive, create our QPACK encoder sized to the peer's advertised
     * `QPACK_MAX_TABLE_CAPACITY` (capped by ours) and raise its capacity (a Set Dynamic Table Capacity
     * on our encoder stream). A capacity-0 peer leaves [encoder] null — requests then encode statically.
     */
    private fun startEncoderSetup() {
        scope.launch {
            val settings =
                try {
                    peerSettings()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    return@launch // SETTINGS never arrived / connection failed — stay static-only
                }
            val peerMax = settings.qpackMaxTableCapacity
            if (peerMax <= 0) return@launch
            val capacity = minOf(QPACK_MAX_TABLE_CAPACITY, peerMax)
            val newEncoder = QpackEncoder(peerMax, settings.qpackBlockedStreams) { writeEncoderInstruction(it) }
            newEncoder.setCapacity(capacity)
            encoder = newEncoder
        }
    }

    /** Control stream: the type prefix `0x00` immediately followed by the SETTINGS frame. */
    private suspend fun writeControlStreamHeader() {
        val entries =
            mutableListOf(
                Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, QPACK_MAX_TABLE_CAPACITY),
                Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, QPACK_BLOCKED_STREAMS),
            )
        webTransport?.let { entries += webTransportSettings(it) }
        val settings = Http3Frame.Settings(entries)
        val prefix = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL))
        try {
            VarIntCodec.encode(prefix, Http3StreamType.CONTROL, EncodeContext.Empty)
            prefix.resetForRead()
            controlStream.write(prefix, config.writePolicy.toDeadline())
        } finally {
            // Free on both paths: write() is zero-copy and does not take ownership (mirrors writeFrame).
            prefix.freeIfNeeded()
        }
        val frame = Http3FrameCodec.encode(settings, EncodeContext.Empty, pool)
        try {
            controlStream.write(frame, config.writePolicy.toDeadline())
        } finally {
            frame.freeIfNeeded()
        }
    }

    /** Writes a bare unidirectional stream-type prefix (RFC 9114 §6.2) as the stream's first bytes. */
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
            // Free on both paths: write() is zero-copy and does not take ownership (mirrors writeFrame).
            buffer.freeIfNeeded()
        }
    }

    private fun startRouter() {
        scope.launch {
            val routes = mutableListOf<Job>()
            try {
                scope.streams().collect { stream -> routes += launch { route(stream) } }
            } finally {
                // The streams flow only completes when the connection closes. Let any in-flight
                // route() finish first (one may be resolving SETTINGS), then — if the peer's
                // control stream never delivered SETTINGS — unblock any awaiter.
                routes.joinAll()
                // No more pushes can arrive; complete the pushes flow for any collector.
                pushChannel.close()
                if (!peerSettingsDeferred.isCompleted) {
                    peerSettingsDeferred.completeExceptionally(
                        Http3StreamException("connection closed before the peer's SETTINGS were received"),
                    )
                }
            }
        }
    }

    /**
     * Reads a peer-initiated stream's unidirectional type prefix and dispatches it. Only the
     * control stream is parsed; QPACK/push/reserved streams are drained so the peer isn't
     * flow-control stalled. A single stream's failure is swallowed — it must not cancel the
     * connection scope — and the control handler resolves [peerSettings] on its own error path.
     */
    private suspend fun route(stream: QuicByteStream) {
        // One processor, shared so bytes buffered while reading the type prefix carry into the
        // control/QPACK handler that continues on the same stream.
        val processor = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        // A push stream or an accepted WebTransport stream outlives this router call — the handler/mux
        // owns the processor + stream lifetime, so the generic finally must not touch them.
        var handlerOwnsStream = false
        try {
            if (!stream.streamId.isUnidirectional) {
                // The only peer-initiated bidirectional stream this client expects is a WebTransport
                // bidirectional stream (draft-ietf-webtrans-http3 §4.2); anything else is discarded.
                val mux = webTransportMux
                if (mux != null &&
                    Http3StreamReader(stream, processor).peekVarInt(config.readPolicy.toDeadline()) ==
                    WebTransportWire.WT_BIDI_STREAM_SIGNAL
                ) {
                    handlerOwnsStream = true
                    mux.acceptIncomingBidi(stream, processor)
                }
                return
            }
            when (Http3StreamReader(stream, processor).nextVarInt()) {
                Http3StreamType.CONTROL -> handleControl(Http3StreamReader(stream, processor))
                // The peer's QPACK encoder stream drives our decoder's dynamic table (RFC 9204 §4.3).
                Http3StreamType.QPACK_ENCODER -> readPeerEncoderInstructions(stream, processor)
                // The peer's QPACK decoder stream acks our encoder's inserts/sections (§4.4).
                Http3StreamType.QPACK_DECODER -> readPeerDecoderInstructions(stream, processor)
                // A server-initiated push stream (RFC 9114 §4.6): the handler reads it and parks until
                // the pushed response is consumed, releasing the processor + closing the stream itself.
                Http3StreamType.PUSH -> {
                    handlerOwnsStream = true
                    handlePushStream(stream, processor)
                }
                // A peer-initiated unidirectional WebTransport stream (draft-ietf-webtrans-http3 §4.1):
                // the mux reads the Session ID and routes it to the owning session.
                WebTransportWire.WT_UNI_STREAM_TYPE ->
                    webTransportMux?.let {
                        handlerOwnsStream = true
                        it.acceptIncomingUni(stream, processor)
                    } ?: drain(stream)
                // Reserved/GREASE streams carry data we ignore; drain to keep flow control flowing.
                else -> drain(stream)
            }
        } catch (e: Http3StreamException) {
            // One stream erroring must not take down the connection. The control handler has
            // already recorded any SETTINGS failure into peerSettingsDeferred.
        } catch (e: QuicStreamException) {
            // Peer STOP_SENDING / RESET_STREAM on this one stream (e.g. cancelling a server PUSH this
            // router was reading) — stream-scoped, not connection loss. A genuine connection-close still
            // surfaces as QuicCloseException and propagates to tear the connection down.
        } finally {
            if (!handlerOwnsStream) {
                processor.release()
                stream.close()
            }
        }
    }

    /** Feed the peer's QPACK encoder-stream instructions into our decoder until the stream ends. */
    private suspend fun readPeerEncoderInstructions(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        val reader = QpackInstructionReader.encoder(stream, processor, pool)
        while (true) {
            val instruction = reader.next(config.readPolicy.toDeadline()) ?: break
            decoder.applyEncoderInstruction(instruction)
        }
    }

    /** Feed the peer's QPACK decoder-stream instructions into our encoder until the stream ends. */
    private suspend fun readPeerDecoderInstructions(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        val reader = QpackInstructionReader.decoder(stream, processor)
        while (true) {
            val instruction = reader.next(config.readPolicy.toDeadline()) ?: break
            // The peer only acks entries our encoder inserted, so the encoder exists by now; guard anyway.
            encoder?.processDecoderInstruction(instruction)
        }
    }

    /**
     * Reads the peer's control stream and enforces its framing rules (RFC 9114 §6.2.1 / §7.2.4):
     * the first frame MUST be SETTINGS (else [Http3ErrorCode.MISSING_SETTINGS], or
     * [Http3ErrorCode.CLOSED_CRITICAL_STREAM] if the stream ends first); SETTINGS resolves
     * [peerSettings]. Subsequent control frames (GOAWAY, MAX_PUSH_ID, …) are read and acted on or
     * ignored; a duplicate SETTINGS or a request-stream frame (DATA/HEADERS) on the control stream
     * is [Http3ErrorCode.FRAME_UNEXPECTED]. Any violation [aborts the connection][abortConnection].
     */
    private suspend fun handleControl(reader: Http3StreamReader) {
        try {
            when (val first = reader.nextFrame()) {
                // The control stream is critical; the peer ending it before SETTINGS is fatal.
                null ->
                    abortConnection(
                        Http3StreamException(
                            "peer control stream ended before SETTINGS",
                            Http3ErrorCode.CLOSED_CRITICAL_STREAM,
                        ),
                    )
                !is Http3Frame.Settings ->
                    abortConnection(
                        Http3StreamException(
                            "control stream's first frame was ${first::class.simpleName}, expected SETTINGS",
                            Http3ErrorCode.MISSING_SETTINGS,
                        ),
                    )
                else -> {
                    peerSettingsDeferred.complete(Http3Settings(first.entries))
                    readControlFrames(reader)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Http3StreamException) {
            abortConnection(e)
        } catch (e: Throwable) {
            abortConnection(Http3StreamException(e.message ?: "control stream error"))
        }
    }

    /** Reads control frames after SETTINGS until end-of-stream, enforcing §7.2.4 frame rules. */
    private suspend fun readControlFrames(reader: Http3StreamReader) {
        while (true) {
            when (val frame = reader.nextFrame()) {
                null -> break // control stream ended
                // GOAWAY (RFC 9114 §7.2.6): the server is going away; surface the last processable
                // stream id. Ids are non-increasing across repeated GOAWAYs — keep the lowest.
                is Http3Frame.GoAway ->
                    _goAway.update { current -> if (current == null) frame.id else minOf(current, frame.id) }
                // CANCEL_PUSH from the server withdraws a push it promised (RFC 9114 §7.2.3): fail the
                // matching push so an awaiting Http3ServerPush.response() unblocks.
                is Http3Frame.CancelPush -> onServerCancelPush(frame.pushId)
                // MAX_PUSH_ID is client→server only; a server sending one is a violation (RFC 9114 §7.2.7).
                is Http3Frame.MaxPushId ->
                    throw Http3StreamException(
                        "MAX_PUSH_ID received from the server",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
                // Reserved/GREASE frames are ignored (RFC 9114 §9).
                is Http3Frame.Unknown -> {}
                // PUSH_PROMISE is a request-stream frame and never valid on the control stream (§7.2.5).
                is Http3Frame.PushPromise ->
                    throw Http3StreamException(
                        "PUSH_PROMISE on the control stream",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
                // SETTINGS may appear only once, as the first frame; a second is a violation (§7.2.4).
                is Http3Frame.Settings ->
                    throw Http3StreamException("a second SETTINGS frame on the control stream", Http3ErrorCode.FRAME_UNEXPECTED)
                // DATA/HEADERS are request-stream frames and are never valid on the control stream (§4.1).
                is Http3Frame.Data, is Http3Frame.Headers ->
                    throw Http3StreamException(
                        "unexpected ${frame::class.simpleName} on the control stream",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
            }
        }
    }

    /**
     * Record a fatal connection-level protocol violation (RFC 9114 §8) and close the connection.
     * Completes [connectionError] / [awaitConnectionError], unblocks any [peerSettings] awaiter with
     * the cause, and sends a CONNECTION_CLOSE carrying [Http3StreamException.errorCode] as the
     * application error code ([QuicScope.closeWithError]). Idempotent — only the first error is kept;
     * a close that fails because the connection is already gone is ignored (the error is recorded
     * regardless).
     */
    private suspend fun abortConnection(error: Http3StreamException) {
        if (!connectionErrorDeferred.complete(error)) return
        connectionErrorOrNull = error
        if (!peerSettingsDeferred.isCompleted) peerSettingsDeferred.completeExceptionally(error)
        try {
            scope.closeWithError(error.errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Connection already torn down (or the platform can't send an app-coded close) — the
            // protocol error is still recorded and surfaced to callers.
        }
    }

    /** Reads and discards a stream's bytes until end-of-stream or reset. */
    private suspend fun drain(stream: ByteStream) {
        while (true) {
            when (val result = stream.read()) {
                is ReadResult.Data -> result.buffer.freeIfNeeded()
                ReadResult.End, ReadResult.Reset -> return
            }
        }
    }

    // --- Server push handlers (RFC 9114 §4.6) ---

    /**
     * Validate a Push ID against the limit this client advertised (RFC 9114 §7.2.5 / §4.6). When push
     * was never enabled (no MAX_PUSH_ID sent), or the id exceeds the maximum, this is a connection
     * error of type H3_ID_ERROR: abort the connection (sending CONNECTION_CLOSE with the code) and
     * throw so the local read unwinds. Aborting here means the violation is signalled regardless of
     * which path — request stream or push stream — detected it.
     */
    private suspend fun validatePushId(pushId: Long) {
        if (maxPushId >= 0 && pushId in 0..currentMaxPushId) return
        val error =
            Http3StreamException(
                if (maxPushId < 0) {
                    "received a push but MAX_PUSH_ID was never sent (push disabled)"
                } else {
                    "push id $pushId exceeds the current maximum ($currentMaxPushId)"
                },
                Http3ErrorCode.ID_ERROR,
            )
        abortConnection(error)
        throw error
    }

    /**
     * Keep a rolling push window open (RFC 9114 §7.2.7): once an observed push id consumes more than
     * half the credit above the current maximum, re-issue MAX_PUSH_ID to `observed + window` (window =
     * the initially advertised maxPushId + 1). MAX_PUSH_ID only ever increases, as the RFC requires.
     * No-op when push is disabled. Call after [validatePushId], outside any pushMutex section.
     */
    private suspend fun maybeExtendMaxPushId(observedPushId: Long) {
        if (maxPushId < 0) return
        val window = maxPushId + 1
        val newMax =
            pushMutex.withLock {
                if (currentMaxPushId - observedPushId > window / 2) return // ample headroom remains
                val target = observedPushId + window
                if (target <= currentMaxPushId) return
                currentMaxPushId = target
                target
            }
        writeControlFrame(Http3Frame.MaxPushId(newMax))
    }

    /**
     * Handle a PUSH_PROMISE (RFC 9114 §7.2.5) received on a request stream [requestStreamId]: validate
     * the push id, decode the promised request (QPACK, against the request stream's context), and — the
     * first time this id is promised — emit an [Http3ServerPush] on [pushes]. Duplicate promises for the
     * same id (permitted by §4.6) are ignored after the first.
     */
    private suspend fun onPushPromiseFrame(
        frame: Http3Frame.PushPromise,
        requestStreamId: Long,
    ) {
        validatePushId(frame.pushId)
        maybeExtendMaxPushId(frame.pushId)
        val promised = decodePromisedRequest(frame.encodedFieldSection, requestStreamId)
        pushMutex.withLock {
            val entry = pushEntries.getOrPut(frame.pushId) { PushEntry() }
            if (entry.promised) return // already emitted for this id
            entry.promised = true
            val push = Http3ServerPush(frame.pushId, promised, entry.responseDeferred, ::cancelPush)
            pushChannel.trySend(push) // UNLIMITED — only fails once the channel is closed at teardown
        }
    }

    /** Decode a PUSH_PROMISE field section into the promised request's pseudo-headers + headers. */
    private suspend fun decodePromisedRequest(
        section: ReadBuffer,
        streamId: Long,
    ): Http3PromisedRequest {
        val fields = decoder.decodeSection(section, streamId, pool)

        fun pseudo(name: String): String =
            fields.firstOrNull { it.name == name }?.value
                ?: throw Http3StreamException("PUSH_PROMISE missing the $name pseudo-header", Http3ErrorCode.MESSAGE_ERROR)
        return Http3PromisedRequest(
            method = pseudo(":method"),
            scheme = pseudo(":scheme"),
            authority = pseudo(":authority"),
            path = pseudo(":path"),
            headers = fields.filterNot { it.name.startsWith(":") },
        )
    }

    /**
     * Read a server-initiated push stream (RFC 9114 §4.6). The 0x01 type prefix was consumed by the
     * router; the stream header's remaining field is the Push ID. After validating it and reading the
     * pushed response's HEADERS, complete the matching push's response, then park until that response
     * is closed — keeping the stream open so its body streams lazily. This coroutine (not the router)
     * owns releasing the [processor] and closing the [stream]; ownership passes to the [Http3Response]
     * once it is built (it releases the shared processor on close), so we only release it ourselves on
     * an early failure.
     */
    private suspend fun handlePushStream(
        stream: QuicByteStream,
        processor: StreamProcessor,
    ) {
        var entry: PushEntry? = null
        var responseOwnsProcessor = false
        try {
            val pushId = Http3StreamReader(stream, processor).nextVarInt(config.readPolicy.toDeadline())
            validatePushId(pushId)
            maybeExtendMaxPushId(pushId)
            entry = pushMutex.withLock { pushEntries.getOrPut(pushId) { PushEntry() }.also { it.pushStream = stream } }
            if (entry.cancelled) {
                resetStreamQuietly(stream, Http3ErrorCode.REQUEST_CANCELLED)
                return
            }
            // A push stream carries a response message but never a PUSH_PROMISE (onPushPromise = null).
            val response = readResponseHead(stream, Http3StreamReader(stream, processor), onPushPromise = null)
            responseOwnsProcessor = true
            entry.responseDeferred.complete(response)
            response.awaitClosed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val ex = e as? Http3StreamException ?: Http3StreamException(e.message ?: "push stream error")
            entry?.let { if (!it.responseDeferred.isCompleted) it.responseDeferred.completeExceptionally(ex) }
        } finally {
            if (!responseOwnsProcessor) processor.release()
            stream.close()
        }
    }

    /**
     * Decline a server push (RFC 9114 §7.2.3), driven by [Http3ServerPush.cancel]: mark it cancelled,
     * send CANCEL_PUSH on the control stream, reset the push stream if it has arrived, and fail an
     * awaiting [Http3ServerPush.response]. Works whether the push stream has arrived yet or not.
     */
    private suspend fun cancelPush(pushId: Long) {
        val entry =
            pushMutex.withLock {
                pushEntries.getOrPut(pushId) { PushEntry() }.also { it.cancelled = true }
            }
        try {
            writeControlFrame(Http3Frame.CancelPush(pushId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Connection already gone — the local cancellation below still applies.
        }
        entry.pushStream?.let { resetStreamQuietly(it, Http3ErrorCode.REQUEST_CANCELLED) }
        if (!entry.responseDeferred.isCompleted) {
            entry.responseDeferred.completeExceptionally(
                Http3StreamException("server push $pushId cancelled by the client", Http3ErrorCode.REQUEST_CANCELLED),
            )
        }
    }

    /** A server CANCEL_PUSH (RFC 9114 §7.2.3): the server withdrew a promise. Fail any waiter. */
    private suspend fun onServerCancelPush(pushId: Long) {
        val entry = pushMutex.withLock { pushEntries[pushId] } ?: return
        entry.cancelled = true
        if (!entry.responseDeferred.isCompleted) {
            entry.responseDeferred.completeExceptionally(
                Http3StreamException("server cancelled push $pushId", Http3ErrorCode.REQUEST_CANCELLED),
            )
        }
        entry.pushStream?.let { resetStreamQuietly(it, Http3ErrorCode.REQUEST_CANCELLED) }
    }

    companion object {
        /** The QPACK dynamic-table capacity we advertise (and cap our encoder at), in octets. */
        private const val QPACK_MAX_TABLE_CAPACITY: Long = 4096

        /** The number of blocked streams we permit the peer's encoder to create (RFC 9204 §5). */
        private const val QPACK_BLOCKED_STREAMS: Long = 100

        /**
         * Bootstraps HTTP/3 over an established [scope]: opens the client control + QPACK
         * encoder/decoder unidirectional streams, sends the type prefixes and SETTINGS, and
         * starts the peer-stream router. Returns once those are sent; [peerSettings] then
         * resolves asynchronously when the peer's control stream arrives.
         *
         * Buffer allocation (frame writes, stream readers) is routed through
         * [TransportConfig.bufferFactory], mirroring [com.ditchoom.socket.quic.QuicStreamMux].
         *
         * Server push (RFC 9114 §4.6) is opt-in via [maxPushId]: the default -1 disables it (no
         * MAX_PUSH_ID is sent, so the server may not push and any push is an H3_ID_ERROR). A value
         * >= 0 sends MAX_PUSH_ID and lets the server push ids `0..maxPushId`; consume the pushes via
         * [pushes]. Re-issuing MAX_PUSH_ID to extend the window as pushes complete is a future tune.
         *
         * @throws UnsupportedOperationException if the platform lacks unidirectional QUIC streams.
         */
        suspend fun bootstrap(
            scope: QuicScope,
            options: TransportConfig = TransportConfig(),
            maxPushId: Long = -1,
            webTransport: WebTransportOptions? = null,
        ): Http3Connection {
            // MultiThreaded: the router's per-stream coroutines allocate from this pool concurrently.
            val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = options.bufferFactory)
            val control = scope.openUniStream()
            val qpackEncoder = scope.openUniStream()
            val qpackDecoder = scope.openUniStream()
            return Http3Connection(scope, options, pool, control, qpackEncoder, qpackDecoder, maxPushId, webTransport)
                .also { it.start() }
        }
    }
}
