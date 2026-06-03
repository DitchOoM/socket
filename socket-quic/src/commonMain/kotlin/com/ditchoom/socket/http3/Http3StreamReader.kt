package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.time.Duration

/** Raised when a frame can't be reassembled — a truncated frame at FIN, or a peer reset. */
class Http3StreamException(
    message: String,
) : Exception(message)

/**
 * Reassembles a QUIC stream of RFC 9114 §7.1 frames into [Http3Frame]s. HTTP/3 packs
 * frames back-to-back on a stream with no outer delimiter, so this buffers stream chunks
 * in a [StreamProcessor] and uses [Http3FrameCodec.peekFrameSize] to know when a whole
 * frame — type + length varints + payload — is buffered before decoding it.
 *
 * One reader wraps one stream. [nextFrame] reassembles a frame split across reads and
 * returns null at clean end-of-stream; a partial frame at FIN, or a reset, throws
 * [Http3StreamException].
 *
 * The returned frame for DATA/HEADERS borrows a buffer owned by this reader's processor;
 * read or copy its payload before the next [nextFrame] call. [release] returns the
 * processor's buffers to the pool when the stream is done.
 */
class Http3StreamReader(
    private val stream: ByteStream,
    private val processor: StreamProcessor,
) {
    private var ended = false

    /**
     * Returns the next complete frame, reading from the stream as needed, or null once the
     * stream has cleanly ended with no buffered bytes.
     */
    suspend fun nextFrame(timeout: Duration = Duration.INFINITE): Http3Frame? {
        while (true) {
            // A fully buffered frame is returned even after FIN, so trailing complete frames drain.
            val peek = Http3FrameCodec.peekFrameSize(processor, 0)
            if (peek is PeekResult.Complete && processor.available() >= peek.bytes) {
                return Http3FrameCodec.decode(processor.readBuffer(peek.bytes), DecodeContext.Empty)
            }
            if (ended) {
                if (processor.available() == 0) return null // clean end
                // Leftover bytes that don't form a whole frame (short header or short payload).
                throw Http3StreamException("stream ended mid-frame: ${processor.available()} trailing byte(s)")
            }
            when (val result = stream.read(timeout)) {
                is ReadResult.Data -> processor.append(result.buffer)
                ReadResult.End -> ended = true
                ReadResult.Reset -> {
                    ended = true
                    throw Http3StreamException("stream was reset by the peer")
                }
            }
        }
    }

    /** Return the processor's buffers to the pool. Call once the stream is fully consumed. */
    fun release() = processor.release()

    companion object {
        fun create(
            stream: ByteStream,
            pool: BufferPool,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): Http3StreamReader = Http3StreamReader(stream, StreamProcessor.create(pool, byteOrder))
    }
}
