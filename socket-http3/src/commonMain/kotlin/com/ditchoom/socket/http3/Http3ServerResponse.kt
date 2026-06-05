package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicByteStream

/**
 * The response side of a server [Http3ServerExchange] (RFC 9114 §4.1). Write a response either in one
 * shot with [send], or stream it: [sendHeaders] (the `:status` pseudo-header is added automatically),
 * then zero or more [writeBody] DATA frames, an optional [sendTrailers], and the framework FINs the
 * send side when the request handler returns. If the handler sends nothing, a minimal `500` is emitted.
 */
class Http3ServerResponse internal constructor(
    private val stream: QuicByteStream,
    private val pool: BufferPool,
    private val options: ConnectionOptions,
    private val streamId: Long,
    // Encodes a field section through the server's QPACK (dynamic when capacity > 0, else static).
    private val encodeSection: suspend (List<QpackHeaderField>, Long) -> ReadBuffer,
) {
    private var headersSent = false
    private var finished = false

    /** Send the response HEADERS frame: `:status` first (RFC 9114 §4.3.1), then [headers]. Once only. */
    suspend fun sendHeaders(
        status: Int,
        headers: List<QpackHeaderField> = emptyList(),
    ) {
        check(!headersSent) { "response HEADERS already sent" }
        check(!finished) { "response already finished" }
        val fields =
            buildList {
                add(QpackHeaderField(":status", status.toString()))
                addAll(headers)
            }
        val section = encodeSection(fields, streamId)
        try {
            writeFrame(Http3Frame.Headers(section))
        } finally {
            section.freeIfNeeded()
        }
        headersSent = true
    }

    /** Send one response-body DATA frame. Call [sendHeaders] first. Zero-copy; does not take ownership. */
    suspend fun writeBody(chunk: ReadBuffer) {
        check(headersSent) { "call sendHeaders before writeBody" }
        check(!finished) { "response already finished" }
        if (chunk.remaining() > 0) writeFrame(Http3Frame.Data(chunk))
    }

    /** Send a trailing field section (RFC 9114 §4.1). Ends the body; no DATA may follow. */
    suspend fun sendTrailers(trailers: List<QpackHeaderField>) {
        check(headersSent) { "call sendHeaders before sendTrailers" }
        check(!finished) { "response already finished" }
        val section = encodeSection(trailers, streamId)
        try {
            writeFrame(Http3Frame.Headers(section))
        } finally {
            section.freeIfNeeded()
        }
    }

    /** Buffered convenience: HEADERS (`:status` + [headers]), an optional single DATA, then finish. */
    suspend fun send(
        status: Int,
        headers: List<QpackHeaderField> = emptyList(),
        body: ReadBuffer? = null,
    ) {
        sendHeaders(status, headers)
        body?.let { writeBody(it) }
        finish()
    }

    /** FIN the send side, emitting a minimal `500` first if the handler sent no HEADERS. Idempotent. */
    internal suspend fun finish() {
        if (finished) return
        if (!headersSent) sendHeaders(500)
        stream.shutdownSend()
        finished = true
    }

    private suspend fun writeFrame(frame: Http3Frame) {
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
}

/** The promised request line of a server push, encoded into the PUSH_PROMISE field section. */
internal data class PushPromiseSpec(
    val method: String,
    val scheme: String,
    val authority: String,
    val path: String,
    val promisedHeaders: List<QpackHeaderField>,
)

/** A server-side request/response pair handed to the `withHttp3Server` request handler. */
class Http3ServerExchange internal constructor(
    val request: Http3ServerRequest,
    val response: Http3ServerResponse,
    // Sends a PUSH_PROMISE on this request stream + a push stream carrying the pushed response;
    // returns false when the client has not granted (enough) push credit. Provided by the connection.
    private val pushFn: suspend (PushPromiseSpec, suspend Http3ServerResponse.() -> Unit) -> Boolean,
) {
    /**
     * Initiate a server push (RFC 9114 §4.6) for [path]: send a PUSH_PROMISE on this request stream
     * and write the pushed response via [respond] on a fresh push stream. Returns `true` if the push
     * was sent, or `false` if the client granted no (or insufficient) push credit — call it before
     * finishing [response], since the PUSH_PROMISE rides this request stream before its FIN.
     *
     * [authority] and [scheme] default to the originating request's. The pushed [respond] writes a
     * normal response (`send` or streaming); the framework FINs the push stream when it returns.
     */
    suspend fun push(
        path: String,
        authority: String = request.authority,
        method: String = "GET",
        scheme: String = request.scheme,
        promisedHeaders: List<QpackHeaderField> = emptyList(),
        respond: suspend Http3ServerResponse.() -> Unit,
    ): Boolean = pushFn(PushPromiseSpec(method, scheme, authority, path, promisedHeaders), respond)
}
