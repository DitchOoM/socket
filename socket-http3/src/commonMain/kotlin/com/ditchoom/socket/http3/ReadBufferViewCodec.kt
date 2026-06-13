package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.ViewCodec
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Opaque-payload codec for [Http3Frame]'s `@RemainingBytes` [ReadBuffer]
 * fields (DATA bodies, HEADERS / PUSH_PROMISE field sections).
 *
 * A [ViewCodec]: decode yields a zero-copy **view** over the frame-bounded
 * region (`readBytes(remaining)`), whose lifetime is tied to the buffer the
 * frame was decoded from — the same borrow contract [Http3StreamReader]
 * documents ("read or copy the payload before the next `nextFrame` call").
 * Encode is **non-destructive**: the payload's position is restored after
 * copying, so a frame can be size-checked and re-encoded without its payload
 * draining (matching the previous hand-written codec).
 */
object ReadBufferViewCodec : ViewCodec<ReadBuffer> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ReadBuffer = buffer.readBytes(buffer.remaining())

    override fun encode(
        buffer: WriteBuffer,
        value: ReadBuffer,
        context: EncodeContext,
    ) {
        val savedPosition = value.position()
        buffer.write(value)
        value.position(savedPosition)
    }

    override fun wireSize(
        value: ReadBuffer,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.remaining())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
