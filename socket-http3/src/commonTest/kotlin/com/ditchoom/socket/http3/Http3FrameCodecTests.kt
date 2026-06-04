package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Http3FrameCodecTests {
    private fun bufferOf(vararg bytes: Int): PlatformBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size.coerceAtLeast(1))
        for (b in bytes) buf.writeByte(b.toByte())
        buf.resetForRead()
        return buf
    }

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

    private fun ReadBuffer.toIntList(): List<Int> = (0 until remaining()).map { readByte().toInt() and 0xFF }

    private fun decodeFrame(vararg bytes: Int): Http3Frame = Http3FrameCodec.decode(bufferOf(*bytes), DecodeContext.Empty)

    private fun encodeFrame(frame: Http3Frame): List<Int> {
        val buf = BufferFactory.Default.allocate(64)
        Http3FrameCodec.encode(buf, frame, EncodeContext.Empty)
        buf.resetForRead()
        return buf.toIntList()
    }

    private fun wireSizeOf(frame: Http3Frame): Int {
        val size = Http3FrameCodec.wireSize(frame, EncodeContext.Empty)
        assertIs<WireSize.Exact>(size)
        return size.bytes
    }

    // --- DATA (0x00) --------------------------------------------------------

    @Test
    fun data_decodeAndEncode_roundTrip() {
        // 00 03 41 41 41 : DATA, length 3, "AAA"
        val frame = decodeFrame(0x00, 0x03, 0x41, 0x41, 0x41)
        assertIs<Http3Frame.Data>(frame)
        assertEquals(listOf(0x41, 0x41, 0x41), frame.payload.toIntList())

        assertEquals(
            listOf(0x00, 0x03, 0x41, 0x41, 0x41),
            encodeFrame(Http3Frame.Data(bufferOf(0x41, 0x41, 0x41))),
        )
        assertEquals(5, wireSizeOf(Http3Frame.Data(bufferOf(0x41, 0x41, 0x41))))
    }

    @Test
    fun data_emptyPayload() {
        val frame = decodeFrame(0x00, 0x00)
        assertIs<Http3Frame.Data>(frame)
        assertEquals(0, frame.payload.remaining())
        assertEquals(listOf(0x00, 0x00), encodeFrame(Http3Frame.Data(bufferOf())))
    }

    // --- HEADERS (0x01) -----------------------------------------------------

    @Test
    fun headers_decodeAndEncode_roundTrip() {
        // 01 02 aa bb : HEADERS, length 2, opaque QPACK block aa bb
        val frame = decodeFrame(0x01, 0x02, 0xaa, 0xbb)
        assertIs<Http3Frame.Headers>(frame)
        assertEquals(listOf(0xaa, 0xbb), frame.encodedFieldSection.toIntList())

        assertEquals(
            listOf(0x01, 0x02, 0xaa, 0xbb),
            encodeFrame(Http3Frame.Headers(bufferOf(0xaa, 0xbb))),
        )
    }

    // --- SETTINGS (0x04) ----------------------------------------------------

    @Test
    fun settings_singleEntry_roundTrip() {
        // 04 05 06 80 00 40 00 : SETTINGS, length 5,
        //   id 0x06 (MAX_FIELD_SECTION_SIZE) = value 16384 (4-byte varint 80 00 40 00)
        val frame = decodeFrame(0x04, 0x05, 0x06, 0x80, 0x00, 0x40, 0x00)
        assertIs<Http3Frame.Settings>(frame)
        assertEquals(listOf(Http3Setting(Http3SettingId.MAX_FIELD_SECTION_SIZE, 16384)), frame.entries)

        assertEquals(
            listOf(0x04, 0x05, 0x06, 0x80, 0x00, 0x40, 0x00),
            encodeFrame(Http3Frame.Settings(listOf(Http3Setting(0x06, 16384)))),
        )
    }

    @Test
    fun settings_multipleEntries_roundTrip() {
        val settings =
            Http3Frame.Settings(
                listOf(
                    Http3Setting(Http3SettingId.QPACK_MAX_TABLE_CAPACITY, 4096),
                    Http3Setting(Http3SettingId.QPACK_BLOCKED_STREAMS, 0),
                    Http3Setting(Http3SettingId.MAX_FIELD_SECTION_SIZE, 16384),
                ),
            )
        val bytes = encodeFrame(settings)
        val decoded = Http3FrameCodec.decode(bufferOf(*bytes.toIntArray()), DecodeContext.Empty)
        assertEquals(settings, decoded)
        assertEquals(bytes.size, wireSizeOf(settings))
    }

    @Test
    fun settings_truncatedPair_failsCleanly() {
        // 04 01 05 : SETTINGS, length 1 — identifier 0x05 with no room for a value.
        // Must throw rather than read the value varint past the frame boundary.
        assertFailsWith<DecodeException> { decodeFrame(0x04, 0x01, 0x05) }
    }

    @Test
    fun settings_valueVarintOverrunsLength_failsCleanly() {
        // 04 02 06 80 : SETTINGS length 2 — id 0x06, then a value whose leading
        // byte 0x80 announces a 4-byte varint that can't fit in the 1 remaining byte.
        assertFailsWith<DecodeException> { decodeFrame(0x04, 0x02, 0x06, 0x80) }
    }

    @Test
    fun encode_isNonDestructive_payloadSurvivesReEncodeAndWireSize() {
        val frame = Http3Frame.Data(bufferOf(0x41, 0x42))
        val first = encodeFrame(frame)
        // Re-encoding the same instance yields identical bytes (payload not drained)...
        assertEquals(first, encodeFrame(frame))
        // ...and wireSize still reflects the full payload.
        assertEquals(4, wireSizeOf(frame))
    }

    @Test
    fun settings_empty() {
        val frame = decodeFrame(0x04, 0x00)
        assertIs<Http3Frame.Settings>(frame)
        assertTrue(frame.entries.isEmpty())
        assertEquals(listOf(0x04, 0x00), encodeFrame(Http3Frame.Settings(emptyList())))
    }

    // --- Unknown / GREASE (RFC 9114 §9 ignore-unknown) ----------------------

    @Test
    fun unknown_singleByteType_isCapturedNotThrown() {
        // 21 02 aa bb : reserved type 0x21 (GREASE, N=0), length 2
        val frame = decodeFrame(0x21, 0x02, 0xaa, 0xbb)
        assertIs<Http3Frame.Unknown>(frame)
        assertEquals(0x21L, frame.type)
        assertEquals(listOf(0xaa, 0xbb), frame.payload.toIntList())
        assertTrue(Http3FrameType.isReserved(frame.type))
    }

    @Test
    fun unknown_multiByteVarintType_isDecodedCorrectly() {
        // 40 40 01 ff : type = 2-byte varint 0x4040 -> 64 (GREASE 0x1f*1+0x21), length 1
        val frame = decodeFrame(0x40, 0x40, 0x01, 0xff)
        assertIs<Http3Frame.Unknown>(frame)
        assertEquals(64L, frame.type)
        assertTrue(Http3FrameType.isReserved(64L))
        // The byte-for-byte re-encode (which consumes the payload) proves the
        // multi-byte type and the 0xff payload were both captured.
        assertEquals(listOf(0x40, 0x40, 0x01, 0xff), encodeFrame(frame))
    }

    @Test
    fun isReserved_classifiesGreaseTypes() {
        assertTrue(Http3FrameType.isReserved(0x21)) // N=0
        assertTrue(Http3FrameType.isReserved(0x40)) // N=1
        assertTrue(!Http3FrameType.isReserved(Http3FrameType.DATA))
        assertTrue(!Http3FrameType.isReserved(Http3FrameType.SETTINGS))
        assertTrue(!Http3FrameType.isReserved(Http3FrameType.GOAWAY))
    }

    // --- sequential decode --------------------------------------------------

    @Test
    fun decode_twoFramesBackToBack() {
        // DATA "A" (00 01 41) then SETTINGS empty (04 00)
        val buf = bufferOf(0x00, 0x01, 0x41, 0x04, 0x00)
        val first = Http3FrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<Http3Frame.Data>(first)
        assertEquals(listOf(0x41), first.payload.toIntList())
        val second = Http3FrameCodec.decode(buf, DecodeContext.Empty)
        assertIs<Http3Frame.Settings>(second)
        assertTrue(second.entries.isEmpty())
    }

    // --- peekFrameSize ------------------------------------------------------

    @Test
    fun peekFrameSize_reportsTotal_onceHeaderPresent_evenWithoutPayload() {
        // Header only: 00 03 (DATA, length 3) — payload absent, size still known = 5.
        assertEquals(PeekResult.Complete(5), Http3FrameCodec.peekFrameSize(streamOf(0x00, 0x03), 0))
        // Full frame present.
        assertEquals(PeekResult.Complete(5), Http3FrameCodec.peekFrameSize(streamOf(0x00, 0x03, 0x41, 0x41, 0x41), 0))
    }

    @Test
    fun peekFrameSize_multiByteVarintLength() {
        // type 0x00, length = 16384 (4-byte varint 80 00 40 00) -> header 5 bytes, total 16389.
        assertEquals(
            PeekResult.Complete(1 + 4 + 16384),
            Http3FrameCodec.peekFrameSize(streamOf(0x00, 0x80, 0x00, 0x40, 0x00), 0),
        )
    }

    @Test
    fun peekFrameSize_needsMoreData_whenHeaderIncomplete() {
        // Empty.
        assertEquals(PeekResult.NeedsMoreData, Http3FrameCodec.peekFrameSize(streamOf(), 0))
        // Type present but length varint announces 4 bytes, only 1 of them present.
        assertEquals(PeekResult.NeedsMoreData, Http3FrameCodec.peekFrameSize(streamOf(0x00, 0x80), 0))
    }
}
