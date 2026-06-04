package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/** Largest QUIC variable-length integer (RFC 9000 §16): 2^62 − 1. */
const val QUIC_VARINT_MAX: Long = (1L shl 62) - 1

/**
 * QUIC / HTTP-3 variable-length integer codec (RFC 9000 §16) — the integer encoding
 * used pervasively in QUIC and HTTP/3 (frame types, lengths, stream/setting ids).
 *
 * The two most-significant bits of the first byte select the length; the remaining
 * bits are the big-endian value:
 *
 * | 2-bit prefix | length | usable bits | value range            |
 * |--------------|--------|-------------|------------------------|
 * | `00`         | 1 byte | 6           | 0 – 63                 |
 * | `01`         | 2 bytes| 14          | 0 – 16383              |
 * | `10`         | 4 bytes| 30          | 0 – 1073741823         |
 * | `11`         | 8 bytes| 62          | 0 – 2^62−1             |
 *
 * [encode] always emits the **minimal** length for the value; [decode] honours whatever
 * length the wire prefix declares (a non-minimal encoding still decodes to its value).
 *
 * This is NOT the MQTT-style `readVariableByteInteger` in the buffer library — that is a
 * different (7-bit continuation) encoding. Plug this into the codec framework via
 * `@UseCodec(QuicVarIntCodec::class)` for plain varint fields (e.g. a frame type).
 */
object QuicVarIntCodec : Codec<Long> {
    override fun encode(
        buffer: WriteBuffer,
        value: Long,
        context: EncodeContext,
    ) {
        require(value in 0..QUIC_VARINT_MAX) { "QUIC varint out of range [0, 2^62): $value" }
        when {
            value < (1L shl 6) -> {
                buffer.writeByte(value.toByte())
            }
            value < (1L shl 14) -> {
                buffer.writeByte((0x40L or (value shr 8)).toByte())
                buffer.writeByte(value.toByte())
            }
            value < (1L shl 30) -> {
                buffer.writeByte((0x80L or (value shr 24)).toByte())
                buffer.writeByte((value shr 16).toByte())
                buffer.writeByte((value shr 8).toByte())
                buffer.writeByte(value.toByte())
            }
            else -> {
                buffer.writeByte((0xC0L or (value shr 56)).toByte())
                buffer.writeByte((value shr 48).toByte())
                buffer.writeByte((value shr 40).toByte())
                buffer.writeByte((value shr 32).toByte())
                buffer.writeByte((value shr 24).toByte())
                buffer.writeByte((value shr 16).toByte())
                buffer.writeByte((value shr 8).toByte())
                buffer.writeByte(value.toByte())
            }
        }
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Long {
        val first = buffer.readUByte().toLong()
        return when ((first.toInt() shr 6) and 0x03) {
            0 -> first and 0x3FL
            1 ->
                ((first and 0x3FL) shl 8) or
                    buffer.readUByte().toLong()
            2 ->
                ((first and 0x3FL) shl 24) or
                    (buffer.readUByte().toLong() shl 16) or
                    (buffer.readUByte().toLong() shl 8) or
                    buffer.readUByte().toLong()
            else ->
                ((first and 0x3FL) shl 56) or
                    (buffer.readUByte().toLong() shl 48) or
                    (buffer.readUByte().toLong() shl 40) or
                    (buffer.readUByte().toLong() shl 32) or
                    (buffer.readUByte().toLong() shl 24) or
                    (buffer.readUByte().toLong() shl 16) or
                    (buffer.readUByte().toLong() shl 8) or
                    buffer.readUByte().toLong()
        }
    }

    override fun wireSize(
        value: Long,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(encodedLength(value))

    /** Minimal number of bytes RFC 9000 §16 uses to encode [value]. */
    fun encodedLength(value: Long): Int =
        when {
            value < (1L shl 6) -> 1
            value < (1L shl 14) -> 2
            value < (1L shl 30) -> 4
            else -> 8
        }
}

/**
 * A QUIC varint used as a **length prefix** that narrows the buffer limit to the
 * following payload region (the shape of an HTTP/3 frame: `Type Length Payload[Length]`).
 *
 * Wire encoding is identical to [QuicVarIntCodec]; the difference is [applyBound], which
 * the codec processor calls after decoding the length so subsequent payload fields read
 * only within the declared region. Plug in via `@UseCodec(QuicVarIntLengthCodec::class)`.
 */
object QuicVarIntLengthCodec : BoundingLengthCodec<Long> {
    override fun encode(
        buffer: WriteBuffer,
        value: Long,
        context: EncodeContext,
    ) = QuicVarIntCodec.encode(buffer, value, context)

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Long = QuicVarIntCodec.decode(buffer, context)

    override fun wireSize(
        value: Long,
        context: EncodeContext,
    ): WireSize = QuicVarIntCodec.wireSize(value, context)

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: Long,
    ) {
        // HTTP/3 frame lengths are varints up to 2^62−1, but a payload we actually decode
        // must be addressable as an Int-bounded buffer region. Reject the (pathological)
        // overflow explicitly rather than silently truncating via toInt().
        require(decodedValue in 0..Int.MAX_VALUE.toLong()) {
            "HTTP/3 length $decodedValue exceeds addressable buffer range (Int.MAX_VALUE)"
        }
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }

    override val maxWireSize: Int = 8
}
