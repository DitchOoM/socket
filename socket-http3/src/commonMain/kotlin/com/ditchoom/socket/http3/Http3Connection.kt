package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * QPACK runs static-table-only: the client advertises `QPACK_MAX_TABLE_CAPACITY = 0` and
 * `QPACK_BLOCKED_STREAMS = 0`, so the encoder/decoder streams carry no instructions and
 * incoming QPACK/push/reserved streams are simply drained.
 *
 * This is the bootstrap half of HTTP/3; request/response (bidi HEADERS+DATA) is layered on
 * top separately.
 */
class Http3Connection private constructor(
    private val scope: QuicScope,
    private val options: ConnectionOptions,
    private val pool: BufferPool,
    private val controlStream: QuicByteStream,
    private val qpackEncoderStream: QuicByteStream,
    private val qpackDecoderStream: QuicByteStream,
) {
    private val peerSettingsDeferred = CompletableDeferred<Http3Settings>()
    private val _goAway = MutableStateFlow<Long?>(null)
    private val connectionErrorDeferred = CompletableDeferred<Http3StreamException>()

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
        val sectionSize = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
        val sectionBuffer = pool.allocate(sectionSize)
        try {
            QpackFieldSectionCodec.encode(sectionBuffer, fields, EncodeContext.Empty)
            sectionBuffer.resetForRead()
            writeFrame(stream, Http3Frame.Headers(sectionBuffer))
        } finally {
            sectionBuffer.freeIfNeeded()
        }
        return stream
    }

    /** Read a response off [stream]: the first HEADERS frame gives status + headers; body streams. */
    private suspend fun readResponse(stream: QuicByteStream): Http3Response {
        val reader = Http3StreamReader.create(stream, pool)
        try {
            while (true) {
                when (val frame = reader.nextFrame(options.readTimeout)) {
                    // Stream ended before the response message began (RFC 9114 §4.1).
                    null ->
                        throw Http3StreamException(
                            "response stream ended before a HEADERS frame",
                            Http3ErrorCode.REQUEST_INCOMPLETE,
                        )
                    is Http3Frame.Headers -> {
                        val decoded =
                            QpackFieldSectionCodec.decode(
                                frame.encodedFieldSection,
                                DecodeContext.Empty.with(QpackScratchPoolKey, pool),
                            )
                        val status = parseStatus(decoded)
                        val headers = decoded.filterNot { it.name.startsWith(":") }
                        return Http3Response(status, headers, stream, reader, pool, options.readTimeout)
                    }
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
        } catch (t: Throwable) {
            reader.release()
            if (t is Http3StreamException) reactToResponseError(stream, t)
            throw t
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
        val size = (Http3FrameCodec.wireSize(frame, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(size)
        try {
            Http3FrameCodec.encode(buffer, frame, EncodeContext.Empty)
            buffer.resetForRead()
            stream.write(buffer, options.writeTimeout)
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
        startRouter()
    }

    /** Control stream: the type prefix `0x00` immediately followed by the SETTINGS frame. */
    private suspend fun writeControlStreamHeader() {
        val settings =
            Http3Frame.Settings(
                listOf(
                    Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 0L),
                    Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0L),
                ),
            )
        val frameSize = (Http3FrameCodec.wireSize(settings, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL) + frameSize)
        try {
            VarIntCodec.encode(buffer, Http3StreamType.CONTROL, EncodeContext.Empty)
            Http3FrameCodec.encode(buffer, settings, EncodeContext.Empty)
            buffer.resetForRead()
            controlStream.write(buffer, options.writeTimeout)
        } finally {
            // Free on both paths: write() is zero-copy and does not take ownership (mirrors writeFrame).
            buffer.freeIfNeeded()
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
            stream.write(buffer, options.writeTimeout)
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
        if (!stream.streamId.isUnidirectional) {
            // Peer-initiated bidirectional streams aren't used by this client; discard.
            stream.close()
            return
        }
        val reader = Http3StreamReader.create(stream, pool)
        try {
            when (reader.nextVarInt()) {
                Http3StreamType.CONTROL -> handleControl(reader)
                // QPACK encoder/decoder, push, and reserved/GREASE streams aren't HTTP/3-framed
                // (or carry data we ignore); drain raw bytes to keep flow control flowing.
                else -> drain(stream)
            }
        } catch (e: Http3StreamException) {
            // One stream erroring must not take down the connection. The control handler has
            // already recorded any SETTINGS failure into peerSettingsDeferred.
        } finally {
            reader.release()
            stream.close()
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
                // MAX_PUSH_ID (RFC 9114 §7.2.7) / CANCEL_PUSH: this client never enables push, so
                // there is nothing to act on. Reserved/GREASE frames are ignored (RFC 9114 §9).
                is Http3Frame.MaxPushId, is Http3Frame.CancelPush, is Http3Frame.Unknown -> {}
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

    companion object {
        /**
         * Bootstraps HTTP/3 over an established [scope]: opens the client control + QPACK
         * encoder/decoder unidirectional streams, sends the type prefixes and SETTINGS, and
         * starts the peer-stream router. Returns once those are sent; [peerSettings] then
         * resolves asynchronously when the peer's control stream arrives.
         *
         * Buffer allocation (frame writes, stream readers) is routed through
         * [ConnectionOptions.bufferFactory], mirroring [com.ditchoom.socket.quic.QuicStreamMux].
         *
         * @throws UnsupportedOperationException if the platform lacks unidirectional QUIC streams.
         */
        suspend fun bootstrap(
            scope: QuicScope,
            options: ConnectionOptions = ConnectionOptions(),
        ): Http3Connection {
            // MultiThreaded: the router's per-stream coroutines allocate from this pool concurrently.
            val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = options.bufferFactory)
            val control = scope.openUniStream()
            val qpackEncoder = scope.openUniStream()
            val qpackDecoder = scope.openUniStream()
            return Http3Connection(scope, options, pool, control, qpackEncoder, qpackDecoder).also { it.start() }
        }
    }
}
