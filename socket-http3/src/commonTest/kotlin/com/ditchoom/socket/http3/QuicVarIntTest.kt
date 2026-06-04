package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 9000 §16 varint conformance. The four canonical example values from the RFC
 * (151288809941952652, 494878333, 15293, 37) plus boundary values, asserting both the
 * minimal encoded length and round-trip equality, and that a non-minimal wire encoding
 * still decodes correctly.
 */
class QuicVarIntTest {
    private fun roundTrip(
        value: Long,
        expectedLen: Int,
    ) {
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        QuicVarIntCodec.encode(buf, value, EncodeContext.Empty)
        assertEquals(expectedLen, buf.position(), "encoded byte count for $value")
        buf.resetForRead()
        assertEquals(value, QuicVarIntCodec.decode(buf, DecodeContext.Empty), "round-trip of $value")
    }

    @Test fun rfcVector8Byte() = roundTrip(151288809941952652L, 8)

    @Test fun rfcVector4Byte() = roundTrip(494878333L, 4)

    @Test fun rfcVector2Byte() = roundTrip(15293L, 2)

    @Test fun rfcVector1Byte() = roundTrip(37L, 1)

    @Test fun zero() = roundTrip(0L, 1)

    @Test fun maxOneByte() = roundTrip(63L, 1)

    @Test fun maxValue() = roundTrip(QUIC_VARINT_MAX, 8)

    @Test
    fun rfcVector2ByteExactBytes() {
        // RFC 9000 §16: 15293 encodes as 0x7b 0xbd.
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        QuicVarIntCodec.encode(buf, 15293L, EncodeContext.Empty)
        buf.resetForRead()
        assertEquals(0x7b.toByte(), buf.readByte())
        assertEquals(0xbd.toByte(), buf.readByte())
    }

    @Test
    fun decodesNonMinimalTwoByteForm() {
        // 0x40 0x25 is the (non-minimal) 2-byte encoding of 37; it must still decode to 37.
        val buf = BufferFactory.Default.allocate(2, ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x40.toByte())
        buf.writeByte(0x25.toByte())
        buf.resetForRead()
        assertEquals(37L, QuicVarIntCodec.decode(buf, DecodeContext.Empty))
    }
}
