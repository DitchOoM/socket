package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import kotlin.time.Duration

/**
 * An HTTP/3 request as seen by the **server** (RFC 9114 §4.1): the request-line pseudo-headers
 * ([method], [scheme], [authority], [path]) and regular [headers], decoded from the request's first
 * HEADERS frame, plus the body streamed frame-by-frame via [nextBodyChunk] (or collected whole with
 * [readFullBody]). A trailing field section is decoded into [trailers] and ends the body.
 *
 * Backed by the request stream's [Http3StreamReader]: a buffer returned by [nextBodyChunk] is
 * borrowed from the reader and only valid until the next call. The server framework releases the
 * reader when the exchange completes.
 */
class Http3ServerRequest internal constructor(
    val method: String,
    val scheme: String,
    val authority: String,
    val path: String,
    val headers: List<QpackHeaderField>,
    private val reader: Http3StreamReader,
    private val pool: BufferPool,
    private val readTimeout: Duration,
    // Decodes a trailing field section through the server's QPACK decoder (dynamic-table refs resolve).
    private val decodeFields: suspend (ReadBuffer) -> List<QpackHeaderField>,
) {
    /** Trailing header fields, populated once a trailing HEADERS frame is reached (else null). */
    var trailers: List<QpackHeaderField>? = null
        private set

    private var bodyDone = false

    /**
     * The next request-body DATA-frame payload, or null at end of body. The returned buffer is
     * **borrowed** from the reader — read or copy it before the next call. A trailing HEADERS frame is
     * decoded into [trailers] and ends the body (returns null); unknown frames are skipped (RFC 9114 §9).
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
                // Unknown/reserved frame types MUST be ignored (RFC 9114 §9).
                is Http3Frame.Unknown -> {}
                // Only DATA and a trailing HEADERS section are valid in a request body; anything else
                // (SETTINGS, GOAWAY, a second leading HEADERS, …) on a request stream is H3_FRAME_UNEXPECTED.
                else -> {
                    bodyDone = true
                    throw Http3StreamException(
                        "unexpected ${frame::class.simpleName} in the request body",
                        Http3ErrorCode.FRAME_UNEXPECTED,
                    )
                }
            }
        }
    }

    /**
     * Collect the entire request body into a single buffer. Each chunk is copied out of the borrowed
     * reader buffer, so the result is independent of the reader's lifetime. The caller owns the
     * returned buffer (release via `freeIfNeeded()`).
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

    /** Drains any unread body so the request stream reaches its end before the response FIN. */
    internal suspend fun drain() {
        while (nextBodyChunk() != null) { /* discard borrowed chunks */ }
    }
}
