package com.ditchoom.socket.http3

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.time.Duration

/**
 * Reassembles a QPACK instruction stream (RFC 9204 §4.3 / §4.4) into instructions. Instructions are
 * packed back-to-back with no length framing and can split across QUIC reads, so this buffers stream
 * chunks in a [StreamProcessor] and uses the codec's `peekLength` to know when a whole instruction is
 * buffered before decoding it — exactly the [Http3StreamReader] pattern, parameterized over the two
 * instruction directions via [peek]/[decode].
 */
class QpackInstructionReader<T> private constructor(
    private val stream: ByteStream,
    private val processor: StreamProcessor,
    private val errorCode: Long,
    private val peek: (StreamProcessor) -> PeekResult,
    private val decode: (ReadBuffer) -> T,
) {
    private var ended = false

    /** The next complete instruction, or null once the stream cleanly ends with no buffered bytes. */
    suspend fun next(timeout: Duration = Duration.INFINITE): T? {
        while (true) {
            val peeked = peek(processor)
            if (peeked is PeekResult.Complete && processor.available() >= peeked.bytes) {
                // Scoped: instructions decode into owned values (Strings/Longs — never buffer
                // views), so the wire bytes recycle to the pool as soon as decode returns.
                return processor.readBufferScoped(peeked.bytes) { decode(this) }
            }
            if (ended) {
                if (processor.available() == 0) return null
                throw Http3StreamException("QPACK stream ended mid-instruction", errorCode)
            }
            when (val result = stream.read(timeout)) {
                is ReadResult.Data -> processor.append(result.buffer)
                ReadResult.End -> ended = true
                // A QPACK encoder/decoder stream is critical; the peer resetting it is fatal (§4.2).
                ReadResult.Reset -> {
                    ended = true
                    throw Http3StreamException("QPACK stream was reset by the peer", Http3ErrorCode.CLOSED_CRITICAL_STREAM)
                }
            }
        }
    }

    fun release() = processor.release()

    companion object {
        /**
         * Reader for the peer's QPACK **encoder** stream (its instructions drive our decoder), over a
         * caller-supplied [processor] — used by the connection so the bytes already buffered while
         * reading the stream-type prefix carry over. [scratchPool] backs Huffman string decoding.
         */
        fun encoder(
            stream: ByteStream,
            processor: StreamProcessor,
            scratchPool: BufferPool,
        ): QpackInstructionReader<QpackEncoderInstruction> =
            QpackInstructionReader(
                stream,
                processor,
                Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR,
                { QpackEncoderInstructionCodec.peekLength(it, 0) },
                { QpackEncoderInstructionCodec.decode(it, scratchPool) },
            )

        /** Reader for the peer's QPACK **decoder** stream (its instructions drive our encoder). */
        fun decoder(
            stream: ByteStream,
            processor: StreamProcessor,
        ): QpackInstructionReader<QpackDecoderInstruction> =
            QpackInstructionReader(
                stream,
                processor,
                Http3ErrorCode.QPACK_DECODER_STREAM_ERROR,
                { QpackDecoderInstructionCodec.peekLength(it, 0) },
                { QpackDecoderInstructionCodec.decode(it) },
            )

        /** Convenience for tests: own a fresh [StreamProcessor] from [pool]. */
        fun encoder(
            stream: ByteStream,
            pool: BufferPool,
        ): QpackInstructionReader<QpackEncoderInstruction> = encoder(stream, StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN), pool)

        /** Convenience for tests: own a fresh [StreamProcessor] from [pool]. */
        fun decoder(
            stream: ByteStream,
            pool: BufferPool,
        ): QpackInstructionReader<QpackDecoderInstruction> = decoder(stream, StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN))
    }
}
