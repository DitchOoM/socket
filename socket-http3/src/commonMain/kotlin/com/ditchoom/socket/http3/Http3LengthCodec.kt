package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * The HTTP/3 frame **Length** field (RFC 9114 §7.1) as the `@FramedBy`
 * framing codec of [Http3Frame]: a QUIC varint ([VarIntCodec]) carrying the
 * byte count of the payload that follows. The framework *computes* the
 * prefix from the encoded body on encode (via
 * [com.ditchoom.buffer.codec.FramedEncoder]) and narrows `buffer.limit()` to
 * bound the body on decode — keeping the frame model length-free.
 *
 * Decode rejects lengths above [Int.MAX_VALUE]: the wire allows 62-bit
 * lengths, but a frame body must fit a single bounded buffer — the same
 * bound the previous hand-written codec enforced.
 */
object Http3LengthCodec : BoundingLengthCodec<UInt> {
    /** A QUIC varint is at most 8 bytes (the 62-bit length class). */
    override val maxWireSize: Int = 8

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        val position = buffer.position()
        val value = VarIntCodec.decode(buffer, context)
        if (value !in 0..Int.MAX_VALUE.toLong()) {
            throw DecodeException(
                fieldPath = "Http3Frame.length",
                bufferPosition = position,
                expected = "0..${Int.MAX_VALUE}",
                actual = value.toString(),
            )
        }
        return value.toUInt()
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) = VarIntCodec.encode(buffer, value.toLong(), context)

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(VarIntCodec.encodedLength(value.toLong()))

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: UInt,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
