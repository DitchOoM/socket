package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 7541 §5.2 / Appendix B Huffman codec tests. The byte vectors are the
 * canonical examples from RFC 7541 Appendix C (request/response headers), which
 * pin the embedded code table independently of the encoder.
 */
class QpackHuffmanTests {
    private fun readBuf(bytes: List<Int>): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

    private fun decode(bytes: List<Int>): String = QpackHuffman.decode(readBuf(bytes), bytes.size, "test")

    private fun encode(value: String): List<Int> {
        val buf = BufferFactory.Default.allocate(value.length * 8 + 1)
        QpackHuffman.encode(buf, value)
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    // --- decode: canonical RFC 7541 Appendix C vectors ----------------------

    @Test
    fun decode_rfcRequestVectors() {
        // C.4.1 / C.4.2 / C.4.3
        assertEquals(
            "www.example.com",
            decode(listOf(0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff)),
        )
        assertEquals("no-cache", decode(listOf(0xa8, 0xeb, 0x10, 0x64, 0x9c, 0xbf)))
        assertEquals("custom-key", decode(listOf(0x25, 0xa8, 0x49, 0xe9, 0x5b, 0xa9, 0x7d, 0x7f)))
        assertEquals("custom-value", decode(listOf(0x25, 0xa8, 0x49, 0xe9, 0x5b, 0xb8, 0xe8, 0xb4, 0xbf)))
    }

    @Test
    fun decode_rfcResponseVectors() {
        // C.6.1
        assertEquals("302", decode(listOf(0x64, 0x02)))
        assertEquals("private", decode(listOf(0xae, 0xc3, 0x77, 0x1a, 0x4b)))
        assertEquals(
            "Mon, 21 Oct 2013 20:13:21 GMT",
            decode(
                listOf(
                    0xd0,
                    0x7a,
                    0xbe,
                    0x94,
                    0x10,
                    0x54,
                    0xd4,
                    0x44,
                    0xa8,
                    0x20,
                    0x05,
                    0x95,
                    0x04,
                    0x0b,
                    0x81,
                    0x66,
                    0xe0,
                    0x82,
                    0xa6,
                    0x2d,
                    0x1b,
                    0xff,
                ),
            ),
        )
        assertEquals(
            "https://www.example.com",
            decode(
                listOf(
                    0x9d,
                    0x29,
                    0xad,
                    0x17,
                    0x18,
                    0x63,
                    0xc7,
                    0x8f,
                    0x0b,
                    0x97,
                    0xc8,
                    0xe9,
                    0xae,
                    0x82,
                    0xae,
                    0x43,
                    0xd3,
                ),
            ),
        )
    }

    @Test
    fun decode_singleSymbolWithPadding() {
        assertEquals("a", decode(listOf(0x1f))) // 00011 + 3 padding ones
        assertEquals(":", decode(listOf(0xb9))) // 1011100 + 1 padding one
        assertEquals("x", decode(listOf(0xf3))) // 1111001 + 1 padding one
    }

    @Test
    fun decode_emptyString() {
        assertEquals("", decode(emptyList()))
    }

    // --- padding / EOS error cases (RFC 7541 §5.2) --------------------------

    @Test
    fun decode_eosSymbolInBody_throws() {
        // 32 one-bits: the 30-bit all-ones EOS code completes before the bytes end.
        assertFailsWith<DecodeException> { decode(listOf(0xff, 0xff, 0xff, 0xff)) }
    }

    @Test
    fun decode_paddingLongerThan7Bits_throws() {
        // "00000000" packs to five 0x00 bytes with no padding; a trailing 0xff adds
        // 8 one-bits of padding (> 7) that complete no symbol.
        assertFailsWith<DecodeException> { decode(listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0xff)) }
    }

    @Test
    fun decode_paddingNotAllOnes_throws() {
        // Valid "a" is 0x1f (00011 + 111). Flipping the last pad bit to 0 (0x1e)
        // leaves padding "110" — not the MSBs of EOS.
        assertFailsWith<DecodeException> { decode(listOf(0x1e)) }
    }

    // --- encode: pin a couple vectors, then round-trip ----------------------

    @Test
    fun encode_matchesRfcVectors() {
        assertEquals(
            listOf(0xf1, 0xe3, 0xc2, 0xe5, 0xf2, 0x3a, 0x6b, 0xa0, 0xab, 0x90, 0xf4, 0xff),
            encode("www.example.com"),
        )
        assertEquals(listOf(0xae, 0xc3, 0x77, 0x1a, 0x4b), encode("private"))
        assertEquals(emptyList(), encode(""))
    }

    @Test
    fun roundTrip_strings() {
        for (s in listOf("", "a", "www.example.com", "no-cache", "Hello, World!", "302", "/index.html?q=1")) {
            assertEquals(s, decode(encode(s)))
        }
    }

    @Test
    fun roundTrip_allAsciiOctets() {
        // Each octet 0..127 is a single-byte UTF-8 char, so the String survives the
        // UTF-8 hop and this exercises symbols 0..127 through encoder and decoder.
        val ascii = CharArray(128) { it.toChar() }.concatToString()
        assertEquals(ascii, decode(encode(ascii)))
    }

    @Test
    fun roundTrip_multiByteUtf8() {
        // Huffman operates on UTF-8 octets; non-BMP/CJK strings must survive intact.
        for (s in listOf("👍", "日本語", "a👍本z")) {
            assertEquals(s, decode(encode(s)))
        }
    }

    @Test
    fun decode_doesNotOverAllocate_longInput() {
        // A long all-'0' run is the densest case (5-bit symbols); confirms the
        // output-size bound holds and decoding stays exact.
        val s = "0".repeat(500)
        assertEquals(s, decode(encode(s)))
    }

    @Test
    fun encode_isOctetAligned() {
        // Every encoding ends on a byte boundary regardless of total bit length.
        for (s in listOf("a", "ab", "abc", "abcd", "abcde")) {
            val buf = BufferFactory.Default.allocate(64)
            QpackHuffman.encode(buf, s)
            assertEquals(s, QpackHuffman.decode(buf.also { it.resetForRead() }, buf.remaining(), "t"))
        }
    }

    @Test
    fun encode_writesUtf8Bytes_notChars() {
        // Sanity that the encoder consumes UTF-8 octets: a 3-byte CJK char encodes
        // the three octets, and the round trip recovers the char.
        val buf = BufferFactory.Default.allocate(64)
        QpackHuffman.encode(buf, "本")
        buf.resetForRead()
        val len = buf.remaining()
        assertEquals("本", QpackHuffman.decode(buf, len, "t"))
        // And the raw UTF-8 of "本" is 3 bytes, so it is not encoded as a single symbol.
        assertEquals(
            3,
            "本".let {
                BufferFactory.Default
                    .allocate(8)
                    .apply { writeString(it, Charset.UTF8) }
                    .position()
            },
        )
    }
}
