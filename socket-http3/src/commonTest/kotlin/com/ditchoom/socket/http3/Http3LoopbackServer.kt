package com.ditchoom.socket.http3

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicScope
import kotlinx.coroutines.launch

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
 * Intentionally minimal: no dynamic QPACK, no server push, no request validation beyond what the
 * loopback test asserts. Request/response bodies are modeled as UTF-8 strings for test ergonomics.
 */
internal class Http3LoopbackServer(
    private val options: ConnectionOptions = ConnectionOptions(),
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

    /** A canned response the server writes back as HEADERS (+ DATA) then a send-side FIN. */
    data class Response(
        val status: Int,
        val headers: List<QpackHeaderField> = emptyList(),
        val body: String = "",
    )

    // MultiThreaded: per-stream handler coroutines allocate from this pool concurrently.
    private val pool = BufferPool(threadingMode = ThreadingMode.MultiThreaded, factory = options.bufferFactory)

    /**
     * Run the HTTP/3 server role over [scope] — a server-accepted QUIC connection (the receiver of
     * `QuicServer.connections`). Returns when the connection closes (the [QuicScope.streams] flow
     * completes), at which point the framework tears the connection down.
     */
    suspend fun serve(scope: QuicScope) {
        writeControlStreamHeader(scope.openUniStream())
        scope.streams().collect { stream ->
            scope.launch {
                try {
                    if (stream.streamId.isUnidirectional) handleUniStream(stream) else handleRequest(stream)
                } catch (_: Http3StreamException) {
                    // A single stream failing must not take the connection down.
                }
            }
        }
    }

    /**
     * Read a client unidirectional stream's type prefix (RFC 9114 §6.2) and dispatch it, mirroring
     * the client's [Http3Connection] router: the control stream's frames (its first is SETTINGS) are
     * read and ignored — a static-table-only server acts on none — and QPACK/other uni streams are
     * drained raw so the peer isn't flow-control stalled. Reading the prefix (rather than blindly
     * discarding bytes) keeps the server honest about §6.2/§7.2.4.
     */
    private suspend fun handleUniStream(stream: QuicByteStream) {
        val reader = Http3StreamReader.create(stream, pool)
        try {
            when (reader.nextVarInt()) {
                // Read the client's control frames until the stream ends (it stays open for the
                // connection's life, so this parks until teardown cancels this coroutine).
                Http3StreamType.CONTROL -> while (reader.nextFrame() != null) { /* accept + ignore */ }
                // QPACK encoder/decoder, push, reserved/GREASE — not HTTP/3-framed or not acted on.
                else -> drain(stream)
            }
        } finally {
            reader.release()
        }
    }

    /** Read one request off a client bidi [stream], call [respond], and write the response back. */
    private suspend fun handleRequest(stream: QuicByteStream) {
        val reader = Http3StreamReader.create(stream, pool)
        try {
            val first =
                reader.nextFrame(options.readTimeout)
                    ?: throw Http3StreamException("request stream ended before a HEADERS frame")
            if (first !is Http3Frame.Headers) {
                throw Http3StreamException("request's first frame was ${first::class.simpleName}, expected HEADERS")
            }
            val fields =
                QpackFieldSectionCodec.decode(
                    first.encodedFieldSection,
                    DecodeContext.Empty.with(QpackScratchPoolKey, pool),
                )
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
            writeResponse(stream, respond(request))
        } finally {
            reader.release()
        }
    }

    /** Write the response HEADERS (`:status` first) + an optional DATA frame, then FIN the send side. */
    private suspend fun writeResponse(
        stream: QuicByteStream,
        response: Response,
    ) {
        val fields =
            buildList {
                add(QpackHeaderField(":status", response.status.toString()))
                addAll(response.headers)
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
        val frameSize = (Http3FrameCodec.wireSize(settings, EncodeContext.Empty) as WireSize.Exact).bytes
        val buffer = pool.allocate(VarIntCodec.encodedLength(Http3StreamType.CONTROL) + frameSize)
        try {
            VarIntCodec.encode(buffer, Http3StreamType.CONTROL, EncodeContext.Empty)
            Http3FrameCodec.encode(buffer, settings, EncodeContext.Empty)
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
