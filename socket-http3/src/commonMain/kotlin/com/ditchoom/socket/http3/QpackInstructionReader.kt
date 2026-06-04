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
                return decode(processor.readBuffer(peeked.bytes))
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
        /** Reader for the peer's QPACK **encoder** stream (its instructions drive our decoder). */
        fun encoder(
            stream: ByteStream,
            pool: BufferPool,
        ): QpackInstructionReader<QpackEncoderInstruction> =
            QpackInstructionReader(
                stream,
                StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN),
                Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR,
                { QpackEncoderInstructionCodec.peekLength(it, 0) },
                { QpackEncoderInstructionCodec.decode(it, pool) },
            )

        /** Reader for the peer's QPACK **decoder** stream (its instructions drive our encoder). */
        fun decoder(
            stream: ByteStream,
            pool: BufferPool,
        ): QpackInstructionReader<QpackDecoderInstruction> =
            QpackInstructionReader(
                stream,
                StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN),
                Http3ErrorCode.QPACK_DECODER_STREAM_ERROR,
                { QpackDecoderInstructionCodec.peekLength(it, 0) },
                { QpackDecoderInstructionCodec.decode(it) },
            )
    }
}
