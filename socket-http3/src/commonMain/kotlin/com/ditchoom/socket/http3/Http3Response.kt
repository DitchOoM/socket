package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
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
    private val reader: Http3StreamReader,
    private val pool: BufferPool,
    private val readTimeout: Duration,
) {
    /** Trailing header fields, populated once a trailing HEADERS frame is reached (else null). */
    var trailers: List<QpackHeaderField>? = null
        private set

    private var bodyDone = false

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
                    trailers =
                        QpackFieldSectionCodec.decode(
                            frame.encodedFieldSection,
                            DecodeContext.Empty.with(QpackScratchPoolKey, pool),
                        )
                    bodyDone = true
                    return null
                }
                else -> {} // skip unknown/other frames and continue
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
    fun close() = reader.release()
}
