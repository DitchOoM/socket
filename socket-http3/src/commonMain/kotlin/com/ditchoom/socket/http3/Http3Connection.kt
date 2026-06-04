package com.ditchoom.socket.http3

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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

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
        val stream = scope.openStream()
        val fields =
            buildList {
                add(QpackHeaderField(":method", request.method))
                add(QpackHeaderField(":scheme", request.scheme))
                add(QpackHeaderField(":authority", request.authority))
                add(QpackHeaderField(":path", request.path))
                addAll(request.headers)
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
        request.body?.let { writeFrame(stream, Http3Frame.Data(it)) }

        // Half-close: finish the request's send side, keep reading the response (RFC 9114 §4).
        stream.shutdownSend()

        val reader = Http3StreamReader.create(stream, pool)
        try {
            while (true) {
                val frame =
                    reader.nextFrame(options.readTimeout)
                        ?: throw Http3StreamException("response stream ended before a HEADERS frame")
                if (frame is Http3Frame.Headers) {
                    val decoded =
                        QpackFieldSectionCodec.decode(
                            frame.encodedFieldSection,
                            DecodeContext.Empty.with(QpackScratchPoolKey, pool),
                        )
                    val status = parseStatus(decoded)
                    val headers = decoded.filterNot { it.name.startsWith(":") }
                    return Http3Response(status, headers, reader, pool, options.readTimeout)
                }
                // A DATA/unknown frame before HEADERS is malformed; skip it defensively.
            }
        } catch (t: Throwable) {
            reader.release()
            throw t
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
                ?: throw Http3StreamException("response HEADERS missing the :status pseudo-header")
        return raw.toIntOrNull()
            ?: throw Http3StreamException("response :status was not a number: \"$raw\"")
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
        } catch (t: Throwable) {
            buffer.freeIfNeeded()
            throw t
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
        } catch (t: Throwable) {
            buffer.freeIfNeeded()
            throw t
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
     * Reads the peer's control stream: its first frame must be SETTINGS (RFC 9114 §7.2.4),
     * which resolves [peerSettings]; subsequent control frames (GOAWAY, MAX_PUSH_ID, …) are
     * read and ignored until the stream ends. Any failure here resolves [peerSettings]
     * exceptionally so awaiters never hang.
     */
    private suspend fun handleControl(reader: Http3StreamReader) {
        try {
            val first = reader.nextFrame()
            if (first !is Http3Frame.Settings) {
                peerSettingsDeferred.completeExceptionally(
                    Http3StreamException(
                        "control stream's first frame was " +
                            "${first?.let { it::class.simpleName } ?: "end-of-stream"}, expected SETTINGS",
                    ),
                )
                return
            }
            peerSettingsDeferred.complete(Http3Settings(first.entries))
            while (reader.nextFrame() != null) {
                // Ignore later control frames for now (GOAWAY/MAX_PUSH_ID handling comes later).
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No-op if SETTINGS already arrived; otherwise unblock awaiters with the cause.
            peerSettingsDeferred.completeExceptionally(e)
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
