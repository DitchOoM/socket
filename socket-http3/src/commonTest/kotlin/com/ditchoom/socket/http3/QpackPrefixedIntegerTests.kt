package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QpackPrefixedIntegerTests {
    private fun bufferOf(vararg bytes: Int): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    private fun encode(
        value: Long,
        prefixBits: Int,
        firstByteFlags: Int = 0,
    ): List<Int> {
        val buf = BufferFactory.Default.allocate(16)
        QpackPrefixedInteger.encode(buf, value, prefixBits, firstByteFlags)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    // --- RFC 7541 Appendix C.1 worked examples ------------------------------

    @Test
    fun rfc7541_c1_1_encode10_with5BitPrefix() {
        // C.1.1: 10 fits in a 5-bit prefix -> single byte 0x0a.
        assertEquals(listOf(0x0a), encode(10, prefixBits = 5))
        assertEquals(10L, QpackPrefixedInteger.decode(bufferOf(0x0a), prefixBits = 5))
    }

    @Test
    fun rfc7541_c1_2_encode1337_with5BitPrefix() {
        // C.1.2: 1337 with a 5-bit prefix -> 0x1f 0x9a 0x0a.
        assertEquals(listOf(0x1f, 0x9a, 0x0a), encode(1337, prefixBits = 5))
        assertEquals(1337L, QpackPrefixedInteger.decode(bufferOf(0x1f, 0x9a, 0x0a), prefixBits = 5))
    }

    @Test
    fun rfc7541_c1_3_encode42_with8BitPrefix() {
        // C.1.3: 42 starting at an octet boundary (8-bit prefix) -> 0x2a.
        assertEquals(listOf(0x2a), encode(42, prefixBits = 8))
        assertEquals(42L, QpackPrefixedInteger.decode(bufferOf(0x2a), prefixBits = 8))
    }

    // --- prefix-boundary behavior -------------------------------------------

    @Test
    fun prefixMax_spillsToZeroContinuation() {
        // value == 2^5 - 1 == 31: prefix saturates, remainder 0 -> 0x1f 0x00.
        assertEquals(listOf(0x1f, 0x00), encode(31, prefixBits = 5))
        assertEquals(31L, QpackPrefixedInteger.decode(bufferOf(0x1f, 0x00), prefixBits = 5))
        // 30 still fits inline.
        assertEquals(listOf(0x1e), encode(30, prefixBits = 5))
    }

    @Test
    fun zero_encodesInline_forEveryPrefixWidth() {
        for (prefixBits in 1..8) {
            assertEquals(listOf(0x00), encode(0, prefixBits))
            assertEquals(0L, QpackPrefixedInteger.decode(bufferOf(0x00), prefixBits))
        }
    }

    // --- first-byte flags coexist with the prefix ---------------------------

    @Test
    fun firstByteFlags_occupyHighBits_indexedFieldLinePattern() {
        // QPACK indexed field line, static table: flags 0b11xxxxxx, 6-bit prefix.
        // index 2 fits inline -> 0b11000010 = 0xc2.
        assertEquals(listOf(0xc2), encode(2, prefixBits = 6, firstByteFlags = 0xc0))
        // Decoding ignores the flag bits and recovers the index (no continuation bytes).
        assertEquals(2L, QpackPrefixedInteger.decodeFromFirstByte(bufferOf(), 0xc2, prefixBits = 6))
    }

    @Test
    fun decodeFromFirstByte_recoversValue_ignoringFlags() {
        // 0xff with a 6-bit prefix: prefix saturates (63), continuation 0x00 -> 63.
        assertEquals(63L, QpackPrefixedInteger.decodeFromFirstByte(bufferOf(0x00), 0xff, prefixBits = 6))
        // multi-byte: name index 1337 with 4-bit prefix and flags 0b0101 0000.
        val buf = BufferFactory.Default.allocate(16)
        QpackPrefixedInteger.encode(buf, 1337, prefixBits = 4, firstByteFlags = 0x50)
        buf.resetForRead()
        val first = buf.readByte().toInt() and 0xFF
        assertEquals(1337L, QpackPrefixedInteger.decodeFromFirstByte(buf, first, prefixBits = 4))
    }

    // --- round-trip + encodedLength -----------------------------------------

    @Test
    fun roundTrip_acrossValuesAndPrefixes() {
        val values = listOf(0L, 1L, 14L, 15L, 16L, 127L, 128L, 255L, 256L, 1337L, 100_000L, 1L shl 40)
        for (prefixBits in intArrayOf(4, 5, 6, 7, 8)) {
            for (value in values) {
                // Encode and decode through a single PlatformBuffer — no array round-trip.
                val buf = BufferFactory.Default.allocate(16)
                QpackPrefixedInteger.encode(buf, value, prefixBits)
                assertEquals(
                    QpackPrefixedInteger.encodedLength(value, prefixBits),
                    buf.position(),
                    "encodedLength for value=$value prefixBits=$prefixBits",
                )
                buf.resetForRead()
                assertEquals(
                    value,
                    QpackPrefixedInteger.decode(buf, prefixBits),
                    "round-trip for value=$value prefixBits=$prefixBits",
                )
            }
        }
    }

    @Test
    fun maxValue_roundTrips() {
        for (prefixBits in intArrayOf(4, 5, 6, 7, 8)) {
            val buf = BufferFactory.Default.allocate(16)
            QpackPrefixedInteger.encode(buf, QpackPrefixedInteger.MAX_VALUE, prefixBits)
            buf.resetForRead()
            assertEquals(
                QpackPrefixedInteger.MAX_VALUE,
                QpackPrefixedInteger.decode(buf, prefixBits),
                "MAX_VALUE round-trip for prefixBits=$prefixBits",
            )
        }
    }

    // --- rejected inputs ----------------------------------------------------

    @Test
    fun decode_overlongContinuation_throwsInsteadOfOverflowing() {
        // 0xff prefix(8)=255 then a long run of 0xff continuation groups: by the
        // group at shift 56 the running sum blows past MAX_VALUE, which must be
        // rejected rather than overflowing Long into a negative result. The overflow
        // is wire-driven, so it is a DecodeException (a malformed field section), which
        // the QPACK decode boundary maps to QPACK_DECOMPRESSION_FAILED (RFC 9204 §2.2).
        val buf = BufferFactory.Default.allocate(10)
        buf.fill(0xff.toByte())
        buf.resetForRead()
        assertFailsWith<DecodeException> {
            QpackPrefixedInteger.decodeFromFirstByte(buf, 0xff, prefixBits = 8)
        }
    }

    @Test
    fun encode_negative_throws() {
        val buf = BufferFactory.Default.allocate(16)
        assertFailsWith<IllegalArgumentException> { QpackPrefixedInteger.encode(buf, -1, prefixBits = 5) }
    }

    @Test
    fun encode_flagsOverlappingPrefix_throws() {
        val buf = BufferFactory.Default.allocate(16)
        // 0x01 collides with a 5-bit prefix (low 5 bits).
        assertFailsWith<IllegalArgumentException> {
            QpackPrefixedInteger.encode(buf, 3, prefixBits = 5, firstByteFlags = 0x01)
        }
    }

    @Test
    fun encode_invalidPrefixBits_throws() {
        val buf = BufferFactory.Default.allocate(16)
        assertFailsWith<IllegalArgumentException> { QpackPrefixedInteger.encode(buf, 1, prefixBits = 0) }
        assertFailsWith<IllegalArgumentException> { QpackPrefixedInteger.encode(buf, 1, prefixBits = 9) }
    }
}
