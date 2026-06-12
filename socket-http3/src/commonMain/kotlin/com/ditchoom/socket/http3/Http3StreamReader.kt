package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.time.Duration

/**
 * Raised when an HTTP/3 stream can't be processed — a frame that can't be reassembled (truncated at
 * FIN, peer reset) or a protocol violation detected while routing/reading frames.
 *
 * [errorCode] is the RFC 9114 §8.1 / RFC 9204 §8.3 code the endpoint would use to abort the
 * connection or stream (see [Http3ErrorCode]); it defaults to
 * [Http3ErrorCode.GENERAL_PROTOCOL_ERROR] so existing throw sites and `catch`/`assertFailsWith`
 * callers keep working unchanged. Specific violations set a more precise code (e.g.
 * [Http3ErrorCode.FRAME_UNEXPECTED], [Http3ErrorCode.MISSING_SETTINGS]).
 */
class Http3StreamException(
    message: String,
    val errorCode: Long = Http3ErrorCode.GENERAL_PROTOCOL_ERROR,
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
 * read or copy its payload before the next [nextFrame] call — that call (or [release])
 * recycles the previous frame's wire bytes, so a stale view is freed memory, not garbage.
 * [release] returns the processor's buffers to the pool when the stream is done.
 */
class Http3StreamReader(
    private val stream: ByteStream,
    private val processor: StreamProcessor,
) {
    private var ended = false

    // The previous frame's wire buffer and its borrowed payload view. Both are released at
    // the start of the next nextFrame() call — the documented end of the borrow window —
    // so exact-fit chunks handed over by the processor and pool-acquired merged copies are
    // recycled instead of leaking until GC (or forever, on native targets).
    private var retainedFrameBuffer: ReadBuffer? = null
    private var retainedPayload: ReadBuffer? = null

    private fun recyclePreviousFrame() {
        retainedPayload?.freeIfNeeded()
        retainedPayload = null
        retainedFrameBuffer?.freeIfNeeded()
        retainedFrameBuffer = null
    }

    /**
     * Returns the next complete frame, reading from the stream as needed, or null once the
     * stream has cleanly ended with no buffered bytes.
     */
    suspend fun nextFrame(timeout: Duration = Duration.INFINITE): Http3Frame? {
        recyclePreviousFrame()
        while (true) {
            // A fully buffered frame is returned even after FIN, so trailing complete frames drain.
            val peek = Http3FrameCodec.peekFrameSize(processor, 0)
            if (peek is PeekResult.Complete && processor.available() >= peek.bytes) {
                val frameBuffer = processor.readBuffer(peek.bytes)
                val frame =
                    try {
                        Http3FrameCodec.decode(frameBuffer, DecodeContext.Empty)
                    } catch (t: Throwable) {
                        frameBuffer.freeIfNeeded()
                        throw t
                    }
                val payload = frame.borrowedPayloadOrNull()
                if (payload == null) {
                    // A fully-structured frame (SETTINGS/GOAWAY/...): nothing borrows the wire bytes.
                    frameBuffer.freeIfNeeded()
                } else {
                    retainedFrameBuffer = frameBuffer
                    // EMPTY_BUFFER is a shared singleton (empty payloads) — never free it.
                    retainedPayload = payload.takeUnless { it === ReadBuffer.EMPTY_BUFFER }
                }
                return frame
            }
            if (ended) {
                if (processor.available() == 0) return null // clean end
                // Leftover bytes that don't form a whole frame (short header or short payload).
                throw Http3StreamException(
                    "stream ended mid-frame: ${processor.available()} trailing byte(s)",
                    Http3ErrorCode.FRAME_ERROR,
                )
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

    /**
     * Reads a single QUIC varint (RFC 9000 §16) from the stream — used for the unidirectional
     * stream-type prefix that precedes the frames on a control / QPACK stream (RFC 9114 §6.2).
     * Throws [Http3StreamException] if the stream ends before a complete varint or is reset.
     */
    suspend fun nextVarInt(timeout: Duration = Duration.INFINITE): Long {
        while (true) {
            val peek = VarIntCodec.peekFrameSize(processor, 0)
            if (peek is PeekResult.Complete && processor.available() >= peek.bytes) {
                // Scoped: the decoded Long owns nothing, so the wire bytes recycle immediately.
                return processor.readBufferScoped(peek.bytes) { VarIntCodec.decode(this, DecodeContext.Empty) }
            }
            if (ended) {
                throw Http3StreamException("stream ended before a complete varint", Http3ErrorCode.FRAME_ERROR)
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

    /**
     * Peek the value of the next QUIC varint **without consuming it**, reading from the stream until a
     * full varint is buffered. Returns null at a clean end-of-stream with no buffered bytes (lets a
     * caller distinguish an empty stream from one carrying data). Throws [Http3StreamException] if the
     * stream ends mid-varint or is reset.
     *
     * Used to demultiplex a stream's leading varint (e.g. a WebTransport bidirectional-stream signal,
     * draft-ietf-webtrans-http3 §4.2) before deciding whether to keep reading it as HTTP/3 frames.
     */
    suspend fun peekVarInt(timeout: Duration = Duration.INFINITE): Long? {
        while (true) {
            val peek = VarIntCodec.peekFrameSize(processor, 0)
            if (peek is PeekResult.Complete && processor.available() >= peek.bytes) {
                val first = processor.peekByte(0).toInt() and 0xFF
                val length = VarIntCodec.lengthFromPrefix(first)
                var value = (first and 0x3F).toLong()
                for (i in 1 until length) {
                    value = (value shl 8) or (processor.peekByte(i).toLong() and 0xFF)
                }
                return value
            }
            if (ended) {
                if (processor.available() == 0) return null
                throw Http3StreamException("stream ended before a complete varint", Http3ErrorCode.FRAME_ERROR)
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
    fun release() {
        recyclePreviousFrame()
        processor.release()
    }

    companion object {
        fun create(
            stream: ByteStream,
            pool: BufferPool,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): Http3StreamReader = Http3StreamReader(stream, StreamProcessor.create(pool, byteOrder))
    }
}

/** The frame's borrowed wire-bytes view (see [ReadBufferViewCodec]), or null for value frames. */
private fun Http3Frame.borrowedPayloadOrNull(): ReadBuffer? =
    when (this) {
        is Http3Frame.Data -> payload
        is Http3Frame.Headers -> encodedFieldSection
        is Http3Frame.PushPromise -> encodedFieldSection
        is Http3Frame.Unknown -> payload
        is Http3Frame.Settings, is Http3Frame.GoAway,
        is Http3Frame.MaxPushId, is Http3Frame.CancelPush,
        -> null
    }
