package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VarIntTests {
    /** Builds a read-positioned buffer from raw byte values. */
    private fun bufferOf(vararg bytes: Int): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    /** Builds a [StreamProcessor] pre-loaded with [bytes]. */
    private fun streamOf(vararg bytes: Int): StreamProcessor {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.SingleThreaded,
                maxPoolSize = 4,
                defaultBufferSize = 64,
                factory = BufferFactory.Default,
            )
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        stream.append(bufferOf(*bytes))
        return stream
    }

    private fun ReadBuffer.decodeVarInt(): Long = VarIntCodec.decode(this, DecodeContext.Empty)

    /** Encodes [value], returns the raw bytes (as unsigned ints) that were written. */
    private fun encode(value: Long): List<Int> {
        val buf = BufferFactory.Default.allocate(8)
        VarIntCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    // --- RFC 9000 Appendix A.1 decode vectors -------------------------------

    @Test
    fun decode_rfc9000_eightByte() {
        // 0xc2197c5eff14e88c -> 151288809941952652
        val v = bufferOf(0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c).decodeVarInt()
        assertEquals(151288809941952652L, v)
    }

    @Test
    fun decode_rfc9000_fourByte() {
        // 0x9d7f3e7d -> 494878333
        val v = bufferOf(0x9d, 0x7f, 0x3e, 0x7d).decodeVarInt()
        assertEquals(494878333L, v)
    }

    @Test
    fun decode_rfc9000_twoByte() {
        // 0x7bbd -> 15293
        val v = bufferOf(0x7b, 0xbd).decodeVarInt()
        assertEquals(15293L, v)
    }

    @Test
    fun decode_rfc9000_oneByte() {
        // 0x25 -> 37
        val v = bufferOf(0x25).decodeVarInt()
        assertEquals(37L, v)
    }

    @Test
    fun decode_rfc9000_nonMinimalTwoByte_decodesTo37() {
        // 0x4025 is a valid non-minimal encoding of 37 (RFC 9000 §16).
        val v = bufferOf(0x40, 0x25).decodeVarInt()
        assertEquals(37L, v)
    }

    // --- encodedLength boundaries -------------------------------------------

    @Test
    fun encodedLength_boundaries() {
        assertEquals(1, VarIntCodec.encodedLength(0))
        assertEquals(1, VarIntCodec.encodedLength(63)) // 2^6 - 1
        assertEquals(2, VarIntCodec.encodedLength(64)) // 2^6
        assertEquals(2, VarIntCodec.encodedLength(16383)) // 2^14 - 1
        assertEquals(4, VarIntCodec.encodedLength(16384)) // 2^14
        assertEquals(4, VarIntCodec.encodedLength(1073741823)) // 2^30 - 1
        assertEquals(8, VarIntCodec.encodedLength(1073741824)) // 2^30
        assertEquals(8, VarIntCodec.encodedLength(VarIntCodec.MAX_VALUE)) // 2^62 - 1
    }

    // --- encode emits the minimal length ------------------------------------

    @Test
    fun encode_minimalLength_oneByte() {
        assertEquals(listOf(0x00), encode(0))
        assertEquals(listOf(0x3f), encode(63))
    }

    @Test
    fun encode_minimalLength_twoByte() {
        // 64 -> 0x4040 ; 15293 -> 0x7bbd
        assertEquals(listOf(0x40, 0x40), encode(64))
        assertEquals(listOf(0x7b, 0xbd), encode(15293))
    }

    @Test
    fun encode_minimalLength_fourByte() {
        // 16384 -> 0x80004000 ; 494878333 -> 0x9d7f3e7d
        assertEquals(listOf(0x80, 0x00, 0x40, 0x00), encode(16384))
        assertEquals(listOf(0x9d, 0x7f, 0x3e, 0x7d), encode(494878333))
    }

    @Test
    fun encode_minimalLength_eightByte() {
        assertEquals(
            listOf(0xc2, 0x19, 0x7c, 0x5e, 0xff, 0x14, 0xe8, 0x8c),
            encode(151288809941952652L),
        )
    }

    @Test
    fun encode_maxValue_allOnes() {
        // 2^62 - 1 -> 0xff followed by seven 0xff bytes
        assertEquals(List(8) { 0xff }, encode(VarIntCodec.MAX_VALUE))
    }

    // --- round trip across every length class -------------------------------

    @Test
    fun roundTrip_acrossLengthClasses() {
        val values =
            listOf(
                0L,
                1L,
                37L,
                63L,
                64L,
                300L,
                16383L,
                16384L,
                1_000_000L,
                1073741823L,
                1073741824L,
                1L shl 40,
                VarIntCodec.MAX_VALUE,
            )
        for (value in values) {
            val buf = BufferFactory.Default.allocate(8)
            VarIntCodec.encode(buf, value, EncodeContext.Empty)
            val expectedLen = VarIntCodec.encodedLength(value)
            assertEquals(expectedLen, buf.position(), "encoded length for $value")
            buf.resetForRead()
            assertEquals(value, VarIntCodec.decode(buf, DecodeContext.Empty), "round-trip for $value")
            assertEquals(expectedLen, buf.position(), "consumed bytes for $value")
        }
    }

    // --- sequential decode advances the position ----------------------------

    @Test
    fun decode_consumesExactlyOneVarint_leavingTrailingBytes() {
        // Two varints back to back: 37 (1 byte) then 15293 (2 bytes).
        val buf = bufferOf(0x25, 0x7b, 0xbd)
        assertEquals(37L, VarIntCodec.decode(buf, DecodeContext.Empty))
        assertEquals(1, buf.position())
        assertEquals(15293L, VarIntCodec.decode(buf, DecodeContext.Empty))
        assertEquals(3, buf.position())
    }

    // --- wireSize is exact and matches encodedLength ------------------------

    @Test
    fun wireSize_isExact_matchesEncodedLength() {
        for (value in listOf(0L, 63L, 64L, 16383L, 16384L, 1073741823L, 1073741824L, VarIntCodec.MAX_VALUE)) {
            val size = VarIntCodec.wireSize(value, EncodeContext.Empty)
            assertTrue(size is WireSize.Exact, "wireSize for $value should be Exact")
            assertEquals(VarIntCodec.encodedLength(value), (size as WireSize.Exact).bytes)
        }
    }

    // --- peekFrameSize derives total length from the first byte alone -------

    @Test
    fun peekFrameSize_returnsTotalLength_fromFirstByteOnly() {
        // Each prefix class; for the 4/8-byte cases only the first byte is present.
        assertEquals(1, peekBytes(streamOf(0x25)))
        assertEquals(2, peekBytes(streamOf(0x7b)))
        assertEquals(4, peekBytes(streamOf(0x9d)))
        assertEquals(8, peekBytes(streamOf(0xc2)))
    }

    @Test
    fun peekFrameSize_needsMoreData_whenEmpty() {
        assertEquals(PeekResult.NeedsMoreData, VarIntCodec.peekFrameSize(streamOf(), 0))
    }

    @Test
    fun peekFrameSize_honorsBaseOffset() {
        // One leading byte to skip, then a 4-byte varint prefix.
        assertEquals(4, peekBytes(streamOf(0xff, 0x9d), baseOffset = 1))
    }

    private fun peekBytes(
        stream: StreamProcessor,
        baseOffset: Int = 0,
    ): Int {
        val result = VarIntCodec.peekFrameSize(stream, baseOffset)
        assertTrue(result is PeekResult.Complete, "expected Complete, got $result")
        return (result as PeekResult.Complete).bytes
    }

    // --- rejected inputs ----------------------------------------------------

    @Test
    fun encode_negative_throws() {
        val buf = BufferFactory.Default.allocate(8)
        assertFailsWith<IllegalArgumentException> { VarIntCodec.encode(buf, -1, EncodeContext.Empty) }
    }

    @Test
    fun encode_overMax_throws() {
        val buf = BufferFactory.Default.allocate(8)
        assertFailsWith<IllegalArgumentException> {
            VarIntCodec.encode(buf, VarIntCodec.MAX_VALUE + 1, EncodeContext.Empty)
        }
    }
}
