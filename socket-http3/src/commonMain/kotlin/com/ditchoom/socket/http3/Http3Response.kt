package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.quic.QuicByteStream
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration

/**
 * An HTTP/3 response (RFC 9114 §4.1).
 *
 * [status] and [headers] are decoded from the response's first HEADERS frame (the `:status`
 * pseudo-header and the regular fields, respectively). The body is then consumed frame by
 * frame via [nextBodyChunk], or collected whole with [readFullBody]. A trailing field section
 * (RFC 9114 §4.1) is decoded into [trailers] and ends the body.
 *
 * Backed by the request stream's [Http3StreamReader]: a buffer returned by [nextBodyChunk] is
 * borrowed from the reader and only valid until the next call. [close] releases the reader's
 * buffers — always call it (directly or via [readFullBody] + [close]) once done.
 */
class Http3Response internal constructor(
    val status: Int,
    val headers: List<QpackHeaderField>,
    private val stream: QuicByteStream,
    private val reader: Http3StreamReader,
    private val pool: BufferPool,
    private val readTimeout: Duration,
    // Handles a PUSH_PROMISE frame interleaved in the response (RFC 9114 §7.2.5): registers the
    // promise on the connection and emits the push. Null for a *pushed* response's own body — a
    // push stream MUST NOT carry PUSH_PROMISE, so one there is H3_FRAME_UNEXPECTED.
    private val onPushPromise: (suspend (Http3Frame.PushPromise) -> Unit)?,
    // Decodes a trailing field section through the connection's QPACK decoder (so dynamic-table
    // references in trailers resolve, and the decoder emits a Section Ack for the trailer section).
    private val decodeFields: suspend (ReadBuffer) -> List<QpackHeaderField>,
) {
    /** Trailing header fields, populated once a trailing HEADERS frame is reached (else null). */
    var trailers: List<QpackHeaderField>? = null
        private set

    private var bodyDone = false

    // Completed by close()/cancel(). For a *pushed* response the push-stream coroutine awaits this
    // ([awaitClosed]) before releasing the shared stream processor, so the body can stream lazily
    // while the stream stays open. For a normal response nothing awaits it — completing is harmless.
    private val closedSignal = CompletableDeferred<Unit>()

    /**
     * The next DATA-frame payload, or null at end of body. The returned buffer is **borrowed**
     * from the reader — read or copy it before the next call. A trailing HEADERS frame is
     * decoded into [trailers] and ends the body (returns null); unknown frames are skipped
     * (RFC 9114 §9).
     */
    suspend fun nextBodyChunk(): ReadBuffer? {
        if (bodyDone) return null
        while (true) {
            when (val frame = reader.nextFrame(readTimeout)) {
                null -> {
                    bodyDone = true
                    return null
                }
                is Http3Frame.Data -> return frame.payload
                is Http3Frame.Headers -> {
                    trailers = decodeFields(frame.encodedFieldSection)
                    bodyDone = true
                    return null
                }
                // PUSH_PROMISE may be interleaved among a request-stream response's body frames
                // (RFC 9114 §7.2.5). Route it to the connection's handler and keep reading the body;
                // on a pushed response (onPushPromise == null) it is H3_FRAME_UNEXPECTED instead.
                is Http3Frame.PushPromise -> {
                    val handler =
                        onPushPromise ?: run {
                            bodyDone = true
                            throw Http3StreamException(
                                Http3Violation.UnexpectedFrame(Http3FrameType.PUSH_PROMISE, Http3FrameContext.PUSH_STREAM),
                            )
                        }
                    handler(frame)
                }
                // GREASE/unknown frame types are ignored (RFC 9114 §9); a reserved HTTP/2 type is
                // FRAME_UNEXPECTED.
                is Http3Frame.Unknown -> frame.rejectIfReservedHttp2Frame()
                // Only DATA and a trailing HEADERS section are valid in a response body; anything
                // else (SETTINGS, GOAWAY, …) on a request stream is H3_FRAME_UNEXPECTED (§4.1).
                else -> {
                    bodyDone = true
                    throw Http3StreamException(
                        Http3Violation.UnexpectedFrame(frame.wireType, Http3FrameContext.RESPONSE_BODY),
                    )
                }
            }
        }
    }

    /**
     * Collect the entire response body into a single buffer. Each chunk is copied out of the
     * borrowed reader buffer, so the result is independent of the reader's lifetime. The caller
     * owns the returned buffer (release via `freeIfNeeded()`).
     */
    suspend fun readFullBody(): ReadBuffer {
        val chunks = mutableListOf<ReadBuffer>()
        var total = 0
        while (true) {
            val chunk = nextBodyChunk() ?: break
            val n = chunk.remaining()
            val copy = pool.allocate(n.coerceAtLeast(1))
            if (n > 0) copy.write(chunk)
            copy.resetForRead()
            chunks += copy
            total += n
        }
        val out = pool.allocate(total.coerceAtLeast(1))
        for (chunk in chunks) {
            if (chunk.remaining() > 0) out.write(chunk)
            chunk.freeIfNeeded()
        }
        out.resetForRead()
        return out
    }

    /** Release the underlying reader's buffers. Idempotent at the [Http3StreamReader] level. */
    fun close() {
        closedSignal.complete(Unit)
        reader.release()
    }

    /**
     * Suspends until [close] or [cancel] is called. Used by the push-stream handler to keep a pushed
     * response's stream alive until the application is done reading its body. Internal — not part of
     * the response-consumer API.
     */
    internal suspend fun awaitClosed() = closedSignal.await()

    /**
     * Cancel the request/response: reset the request stream with [Http3ErrorCode.REQUEST_CANCELLED]
     * (RFC 9114 §4.1 — RESET_STREAM + STOP_SENDING tell the server to stop producing the response)
     * and release the reader's buffers. Use this instead of [close] to abort an in-progress
     * response (e.g. the body is no longer wanted) rather than draining it to completion.
     */
    suspend fun cancel() {
        try {
            stream.reset(Http3ErrorCode.REQUEST_CANCELLED)
        } finally {
            closedSignal.complete(Unit)
            reader.release()
        }
    }
}
