package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QpackFieldSectionCodecTests {
    private fun bufferOf(vararg bytes: Int): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    private fun encode(fields: List<QpackHeaderField>): List<Int> {
        val buf = BufferFactory.Default.allocate(256)
        QpackFieldSectionCodec.encode(buf, fields, EncodeContext.Empty)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    private fun decode(vararg bytes: Int): List<QpackHeaderField> = QpackFieldSectionCodec.decode(bufferOf(*bytes), DecodeContext.Empty)

    /** Encode → decode through one PlatformBuffer. */
    private fun roundTrip(fields: List<QpackHeaderField>): List<QpackHeaderField> {
        val buf = BufferFactory.Default.allocate(256)
        QpackFieldSectionCodec.encode(buf, fields, EncodeContext.Empty)
        buf.resetForRead()
        return QpackFieldSectionCodec.decode(buf, DecodeContext.Empty)
    }

    private fun ascii(s: String): List<Int> = s.map { it.code }

    // --- field section prefix ----------------------------------------------

    @Test
    fun emptySection_isPrefixOnly() {
        // RIC=0, Delta Base=0 → 0x00 0x00, no representations.
        assertEquals(listOf(0x00, 0x00), encode(emptyList()))
        assertEquals(emptyList(), decode(0x00, 0x00))
    }

    // --- Indexed Field Line (static, §4.5.2) --------------------------------

    @Test
    fun indexed_exactStaticMatch() {
        // (:method, GET) = static index 17 → 0xC0 | 17 = 0xD1 (fits the 6-bit prefix).
        assertEquals(listOf(0x00, 0x00, 0xD1), encode(listOf(QpackHeaderField(":method", "GET"))))
        assertEquals(listOf(QpackHeaderField(":method", "GET")), decode(0x00, 0x00, 0xD1))
    }

    @Test
    fun indexed_multiByteIndex() {
        // (:status, 100) = static index 63 → prefix saturates: 0xFF then 0x00.
        assertEquals(listOf(0x00, 0x00, 0xFF, 0x00), encode(listOf(QpackHeaderField(":status", "100"))))
        assertEquals(listOf(QpackHeaderField(":status", "100")), decode(0x00, 0x00, 0xFF, 0x00))
    }

    // --- Literal w/ Name Reference (static, §4.5.4) -------------------------

    @Test
    fun literalNameRef_staticName_huffmanValue() {
        // (:authority, "example.com"): name @0, value Huffman-coded (8 B < 11 raw),
        // so the 7-bit length prefix sets H=1 → 0x80 | 8 = 0x88.
        val valueHuffman = listOf(0x2f, 0x91, 0xd3, 0x5d, 0x05, 0x5c, 0x87, 0xa7)
        val expected = listOf(0x00, 0x00, 0x50, 0x88) + valueHuffman
        assertEquals(expected, encode(listOf(QpackHeaderField(":authority", "example.com"))))
        assertEquals(listOf(QpackHeaderField(":authority", "example.com")), decode(*expected.toIntArray()))
    }

    @Test
    fun decode_literalNameRef_rawValue() {
        // A peer may still send the value raw (H=0, len 11); the decoder accepts it.
        val raw = listOf(0x00, 0x00, 0x50, 0x0B) + ascii("example.com")
        assertEquals(listOf(QpackHeaderField(":authority", "example.com")), decode(*raw.toIntArray()))
    }

    // --- Literal w/ Literal Name (§4.5.6) -----------------------------------

    @Test
    fun literalLiteralName_huffmanNameRawValueOnTie() {
        // ("x-custom-header", "val"): name not in table. Name Huffman-codes to 11 B
        // (< 15 raw) → H=1; 3-bit length 11 saturates: 0x20|0x08|0x07 = 0x2f then 0x04.
        // Value "val" Huffman is 3 B == 3 raw → tie keeps raw (H=0, len 3).
        val nameHuffman = listOf(0xf2, 0xb1, 0x2d, 0x42, 0x4f, 0x4a, 0xd3, 0x94, 0x72, 0x16, 0xcf)
        val expected = listOf(0x00, 0x00, 0x2f, 0x04) + nameHuffman + listOf(0x03) + ascii("val")
        assertEquals(expected, encode(listOf(QpackHeaderField("x-custom-header", "val"))))
        assertEquals(listOf(QpackHeaderField("x-custom-header", "val")), decode(*expected.toIntArray()))
    }

    @Test
    fun decode_literalLiteralName_rawName() {
        // A peer may send the name raw (H=0, len 15 → 0x27 then 0x08); decoder accepts it.
        val raw = listOf(0x00, 0x00, 0x27, 0x08) + ascii("x-custom-header") + listOf(0x03) + ascii("val")
        assertEquals(listOf(QpackHeaderField("x-custom-header", "val")), decode(*raw.toIntArray()))
    }

    // --- round trips --------------------------------------------------------

    @Test
    fun roundTrip_realisticRequest() {
        val headers =
            listOf(
                QpackHeaderField(":method", "GET"), // indexed
                QpackHeaderField(":scheme", "https"), // indexed
                QpackHeaderField(":authority", "example.com"), // name-ref
                QpackHeaderField(":path", "/"), // indexed
                QpackHeaderField("user-agent", "ditchoom-test/1.0"), // name-ref
                QpackHeaderField("x-request-id", "abc-123"), // literal name
            )
        assertEquals(headers, roundTrip(headers))
    }

    @Test
    fun roundTrip_duplicateNamesAndEmptyValue() {
        val headers =
            listOf(
                QpackHeaderField("cookie", ""), // name-ref @5, empty value literal
                QpackHeaderField("accept", "*/*"), // indexed @29
                QpackHeaderField("accept", "application/grpc"), // name-ref @29
            )
        assertEquals(headers, roundTrip(headers))
    }

    @Test
    fun roundTrip_nonBmpAndMultiByteUtf8() {
        // Surrogate-pair emoji (4 UTF-8 bytes) + CJK (3 bytes each) — guards the
        // utf8ByteLength length-prefix against wire/size disagreement.
        val headers =
            listOf(
                QpackHeaderField("x-emoji", "👍"),
                QpackHeaderField("x-cjk", "日本語"),
                QpackHeaderField("x-mixed", "a👍本z"),
            )
        assertEquals(headers, roundTrip(headers))
        // wireSize must equal the actual encoded length for non-BMP/multibyte values too.
        val size = QpackFieldSectionCodec.wireSize(headers, EncodeContext.Empty)
        assertIs<WireSize.Exact>(size)
        assertEquals(encode(headers).size, size.bytes)
    }

    @Test
    fun decode_truncatedStringLiteral_throws() {
        // name-ref @0, value declares length 10 but only 2 bytes follow.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x50, 0x0A, 0x41, 0x42) }
    }

    @Test
    fun decode_indexOutOfStaticTableRange_throws() {
        // Indexed static, index 16 + continuation 0x00 → 143, beyond the 0..98 table.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0xD0, 0x00) }
    }

    @Test
    fun wireSize_matchesEncodedLength() {
        val headers =
            listOf(
                QpackHeaderField(":method", "GET"),
                QpackHeaderField(":authority", "example.com"),
                QpackHeaderField("x-custom-header", "value"),
            )
        val size = QpackFieldSectionCodec.wireSize(headers, EncodeContext.Empty)
        assertIs<WireSize.Exact>(size)
        assertEquals(encode(headers).size, size.bytes)
    }

    // --- rejected inputs ----------------------------------------------------

    @Test
    fun decode_nonZeroRequiredInsertCount_throws() {
        // First prefix byte 0x05 → RIC=5 → needs the dynamic table.
        assertFailsWith<DecodeException> { decode(0x05, 0x00, 0xD1) }
    }

    @Test
    fun decode_dynamicIndexedReference_throws() {
        // Indexed with T=0 (0x80, bit6 clear) → dynamic-table reference.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x85) }
    }

    @Test
    fun decode_dynamicNameReference_throws() {
        // Literal name-ref with T=0 (0x40, bit4 clear) → dynamic-table name reference.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x40, 0x01, 0x41) }
    }

    // --- Huffman-coded strings (H=1, step ④) --------------------------------

    private fun decodeList(bytes: List<Int>): List<QpackHeaderField> =
        QpackFieldSectionCodec.decode(bufferOf(*bytes.toIntArray()), DecodeContext.Empty)

    @Test
    fun decode_huffmanValue_endToEnd() {
        // Name-ref @0 (:authority), value H=1 "www.example.com" (RFC 7541 C.4.1):
        // value length-prefix 0x80|12 = 0x8c, then the 12 Huffman bytes.
        val huffman = listOf(0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff)
        val bytes = listOf(0x00, 0x00, 0x50, 0x8c) + huffman
        assertEquals(listOf(QpackHeaderField(":authority", "www.example.com")), decodeList(bytes))
    }

    @Test
    fun decode_huffmanLiteralName_endToEnd() {
        // Literal-literal-name with H=1 name "custom-key" (8 Huffman bytes) and a raw
        // value "x". 3-bit name-length prefix saturates (8 ≥ 7): 0x20|0x08|0x07 = 0x2f,
        // continuation 0x01; value is H=0 len 1 then 'x'.
        val nameHuffman = listOf(0x25, 0xa8, 0x49, 0xe9, 0x5b, 0xa9, 0x7d, 0x7f)
        val bytes = listOf(0x00, 0x00, 0x2f, 0x01) + nameHuffman + listOf(0x01, 'x'.code)
        assertEquals(listOf(QpackHeaderField("custom-key", "x")), decodeList(bytes))
    }

    @Test
    fun decode_huffmanValueFollowedByMoreLines() {
        // A Huffman value mid-section must consume exactly its declared bytes so the
        // next representation decodes: name-ref @0 H=1 "www.example.com", then an
        // indexed field line 0xD1 = (:method, GET).
        val huffman = listOf(0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff)
        val bytes = listOf(0x00, 0x00, 0x50, 0x8c) + huffman + listOf(0xD1)
        assertEquals(
            listOf(QpackHeaderField(":authority", "www.example.com"), QpackHeaderField(":method", "GET")),
            decodeList(bytes),
        )
    }

    @Test
    fun decode_huffmanValue_withScratchPoolFromContext() {
        // A pool supplied via DecodeContext is used for the transient Huffman buffer;
        // the decoded result is identical and the buffer is reused across two sections.
        val pool = BufferPool(ThreadingMode.SingleThreaded, maxPoolSize = 4, defaultBufferSize = 64, BufferFactory.Default)
        val context = DecodeContext.Empty.with(QpackScratchPoolKey, pool)
        val huffman = listOf(0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff)
        val bytes = listOf(0x00, 0x00, 0x50, 0x8c) + huffman
        repeat(3) {
            val result = QpackFieldSectionCodec.decode(bufferOf(*bytes.toIntArray()), context)
            assertEquals(listOf(QpackHeaderField(":authority", "www.example.com")), result)
        }
        assertTrue(pool.stats().poolHits > 0, "expected scratch-buffer reuse, got ${pool.stats()}")
    }

    @Test
    fun decode_huffmanInvalidPadding_throws() {
        // Name-ref @0, value H=1 len 1 with byte 0x1e — a corrupted "a" whose padding
        // ("110") is not the MSBs of EOS → decode error surfaced by the field codec.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x50, 0x81, 0x1e) }
    }

    @Test
    fun decode_huffmanTruncated_throws() {
        // Value H=1 declares 12 bytes but only 2 follow → bounds check rejects it.
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x50, 0x8c, 0xf1, 0xe3) }
    }

    @Test
    fun decode_postBaseRepresentation_throws() {
        // 0x10 → "Indexed with Post-Base Index" (dynamic-only).
        assertFailsWith<DecodeException> { decode(0x00, 0x00, 0x10) }
    }
}
