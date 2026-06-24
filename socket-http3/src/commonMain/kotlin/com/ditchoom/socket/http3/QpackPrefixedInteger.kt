package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * HPACK/QPACK prefixed-integer codec (RFC 7541 §5.1, used by QPACK field-line
 * representations in RFC 9204 §4.1.1).
 *
 * The integer occupies an N-bit prefix at the low end of the first byte; the
 * high `8 − N` bits of that first byte are caller-owned flags that identify the
 * surrounding representation. If the value fits in the prefix (`< 2^N − 1`) it
 * is written inline; otherwise the prefix is set to all ones and the remainder
 * is emitted in 7-bit little-endian continuation groups, each with its high bit
 * set except the last.
 *
 * This is NOT the QUIC variable-length integer ([VarIntCodec]) — QPACK field
 * lines use this prefix form, while HTTP/3 frames use QUIC varints. The two are
 * not interchangeable.
 */
object QpackPrefixedInteger {
    /**
     * Largest value [decode] will accept: 2^62 − 1, matching [VarIntCodec.MAX_VALUE].
     * RFC 7541 §5.1 lets a decoder reject integers exceeding its limits; this bound
     * keeps the running sum within [Long] and well above any real QPACK index/length.
     */
    const val MAX_VALUE: Long = (1L shl 62) - 1

    /**
     * Writes [value] with an [prefixBits]-bit prefix. [firstByteFlags] supplies
     * the high `8 − prefixBits` bits of the first byte and must not overlap the
     * prefix.
     *
     * @throws IllegalArgumentException for an out-of-range prefix, a negative
     * value, or flags that collide with the prefix bits.
     */
    fun encode(
        buffer: WriteBuffer,
        value: Long,
        prefixBits: Int,
        firstByteFlags: Int = 0,
    ) {
        require(prefixBits in 1..8) { "prefixBits must be in 1..8, got $prefixBits" }
        require(value >= 0) { "prefixed integer must be non-negative, got $value" }
        val max = (1 shl prefixBits) - 1
        require((firstByteFlags and max) == 0) { "firstByteFlags overlap the $prefixBits-bit prefix" }
        if (value < max) {
            buffer.writeByte((firstByteFlags or value.toInt()).toByte())
            return
        }
        buffer.writeByte((firstByteFlags or max).toByte())
        var remainder = value - max
        while (remainder >= 0x80) {
            buffer.writeByte(((remainder and 0x7F) or 0x80).toByte())
            remainder = remainder shr 7
        }
        buffer.writeByte(remainder.toByte())
    }

    /**
     * Decodes a prefixed integer whose first byte has already been consumed and
     * is passed as [firstByte] — used by representation decoders that inspect
     * the first byte's flag bits before reading the integer.
     */
    fun decodeFromFirstByte(
        buffer: ReadBuffer,
        firstByte: Int,
        prefixBits: Int,
    ): Long {
        require(prefixBits in 1..8) { "prefixBits must be in 1..8, got $prefixBits" }
        val max = (1 shl prefixBits) - 1
        var value = (firstByte and max).toLong()
        if (value < max) return value
        var shift = 0
        while (true) {
            val byte = buffer.readByte().toInt() and 0xFF
            // Bound the integer BEFORE accumulating (RFC 7541 §5.1): cap the shift
            // so the left-shift can't wrap, then reject an addend that would push
            // the sum past MAX_VALUE — otherwise a long continuation run overflows
            // Long and yields a negative result. This is wire-driven (an attacker can
            // craft an over-long continuation run), so it is a DecodeException — a
            // malformed field section — not an IllegalArgumentException: the QPACK
            // decode boundary maps it to QPACK_DECOMPRESSION_FAILED (RFC 9204 §2.2).
            if (shift > 56 || value > MAX_VALUE - ((byte.toLong() and 0x7F) shl shift)) {
                throw DecodeException(
                    fieldPath = "QpackPrefixedInteger",
                    bufferPosition = buffer.position(),
                    expected = "an integer in 0..$MAX_VALUE",
                    actual = "a continuation run exceeding $MAX_VALUE",
                )
            }
            value += (byte.toLong() and 0x7F) shl shift
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return value
    }

    /** Decodes a prefixed integer, reading the first byte from [buffer]. */
    fun decode(
        buffer: ReadBuffer,
        prefixBits: Int,
    ): Long = decodeFromFirstByte(buffer, buffer.readByte().toInt() and 0xFF, prefixBits)

    /** A prefixed integer peeked off a [StreamProcessor]: its on-wire [byteLength] and decoded [value]. */
    data class Peeked(
        val byteLength: Int,
        val value: Long,
    )

    /**
     * Peek the prefixed integer starting at [offset] in [stream] without consuming, or null if not
     * enough bytes are buffered yet (the caller reads more and retries — RFC 9204 instructions are
     * not length-framed, so the reader must discover each one's length this way). Used by the
     * instruction-stream `peekLength` helpers; value bounds are re-validated on the consuming [decode].
     */
    fun peek(
        stream: StreamProcessor,
        offset: Int,
        prefixBits: Int,
    ): Peeked? {
        if (stream.available() < offset + 1) return null
        val max = (1 shl prefixBits) - 1
        var value = (stream.peekByte(offset).toInt() and 0xFF and max).toLong()
        if (value < max) return Peeked(1, value)
        var length = 1
        var shift = 0
        while (true) {
            if (stream.available() < offset + length + 1) return null
            val byte = stream.peekByte(offset + length).toInt() and 0xFF
            if (shift <= 56) value += (byte.toLong() and 0x7F) shl shift
            length++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return Peeked(length, value)
    }

    /** Number of bytes [encode] emits for [value] with an [prefixBits]-bit prefix. */
    fun encodedLength(
        value: Long,
        prefixBits: Int,
    ): Int {
        val max = (1 shl prefixBits) - 1
        if (value < max) return 1
        var count = 1
        var remainder = value - max
        while (remainder >= 0x80) {
            count++
            remainder = remainder shr 7
        }
        return count + 1
    }
}
