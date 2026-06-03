package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * QUIC variable-length integer codec (RFC 9000 §16), reused unchanged by
 * HTTP/3 (RFC 9114 §16) and QPACK (RFC 9204) length fields.
 *
 * Modeled as a [Codec]<[Long]> so it composes with the buffer-codec framework:
 * HTTP/3 frame messages reference it on their `type`/`length` fields via
 * `@UseCodec(codec = VarIntCodec::class)`, and [peekFrameSize] provides the
 * non-consuming frame-boundary length-peek that streaming frame readers need.
 *
 * The two most significant bits of the first byte encode the total length in
 * bytes; the remaining bits carry the value, big-endian:
 *
 * | 2-bit prefix | total length | usable bits | max value             |
 * |--------------|--------------|-------------|-----------------------|
 * | 0b00         | 1 byte       | 6           | 63                    |
 * | 0b01         | 2 bytes      | 14          | 16383                 |
 * | 0b10         | 4 bytes      | 30          | 1073741823            |
 * | 0b11         | 8 bytes      | 62          | 4611686018427387903   |
 *
 * [encode] always emits the minimal encoding. [decode] accepts any valid
 * encoding, including non-minimal ones — RFC 9000 §16 requires a receiver to
 * decode them (e.g. the two-byte `0x40 0x25` and the one-byte `0x25` both
 * decode to 37).
 *
 * Bytes are assembled one at a time, so the wire form is always big-endian
 * (network order) regardless of the [com.ditchoom.buffer.ByteOrder] of the
 * caller-supplied buffer — a varint must not depend on how the surrounding
 * buffer happened to be allocated.
 */
object VarIntCodec : Codec<Long> {
    /** Largest value representable in a QUIC varint: 2^62 − 1. */
    const val MAX_VALUE: Long = (1L shl 62) - 1

    /** Number of bytes [encode] emits for [value]: 1, 2, 4, or 8. */
    fun encodedLength(value: Long): Int {
        require(value in 0..MAX_VALUE) { "varint out of range [0, $MAX_VALUE]: $value" }
        return when {
            value < (1L shl 6) -> 1
            value < (1L shl 14) -> 2
            value < (1L shl 30) -> 4
            else -> 8
        }
    }

    /**
     * Writes [value] to [buffer] using the minimal encoding, advancing the
     * buffer's position.
     *
     * @throws IllegalArgumentException if [value] is negative or exceeds [MAX_VALUE].
     */
    override fun encode(
        buffer: WriteBuffer,
        value: Long,
        context: EncodeContext,
    ) {
        when (encodedLength(value)) {
            1 -> buffer.writeByte(value.toByte())
            2 -> {
                buffer.writeByte((0x40 or (value ushr 8).toInt()).toByte())
                buffer.writeByte(value.toByte())
            }
            4 -> {
                buffer.writeByte((0x80 or (value ushr 24).toInt()).toByte())
                buffer.writeByte((value ushr 16).toByte())
                buffer.writeByte((value ushr 8).toByte())
                buffer.writeByte(value.toByte())
            }
            else -> {
                buffer.writeByte((0xc0 or (value ushr 56).toInt()).toByte())
                buffer.writeByte((value ushr 48).toByte())
                buffer.writeByte((value ushr 40).toByte())
                buffer.writeByte((value ushr 32).toByte())
                buffer.writeByte((value ushr 24).toByte())
                buffer.writeByte((value ushr 16).toByte())
                buffer.writeByte((value ushr 8).toByte())
                buffer.writeByte(value.toByte())
            }
        }
    }

    /** A varint's encoded size is always exactly [encodedLength]. */
    override fun wireSize(
        value: Long,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(encodedLength(value))

    /**
     * Reads a varint from [buffer] at its current position, advancing the
     * position past the consumed bytes, and returns the decoded value.
     *
     * The caller must ensure the buffer holds a complete varint; a truncated
     * encoding surfaces as the underlying buffer's read failure when the
     * continuation bytes are consumed. [peekFrameSize] determines, from the
     * first byte alone, how many bytes a complete varint occupies.
     */
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Long {
        val first = buffer.readByte().toInt() and 0xFF
        val length = 1 shl (first ushr 6) // prefix 0..3 → 1, 2, 4, 8 bytes
        var value = (first and 0x3F).toLong()
        repeat(length - 1) {
            value = (value shl 8) or (buffer.readByte().toLong() and 0xFF)
        }
        return value
    }

    /**
     * Reports the total byte length of the varint starting at [baseOffset]
     * without consuming any bytes. The length is fully determined by the first
     * byte's 2-bit prefix, so a single readable byte is enough.
     */
    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult {
        if (stream.available() < baseOffset + 1) return PeekResult.NeedsMoreData
        val first = stream.peekByte(baseOffset).toInt() and 0xFF
        return PeekResult.Complete(1 shl (first ushr 6))
    }
}
