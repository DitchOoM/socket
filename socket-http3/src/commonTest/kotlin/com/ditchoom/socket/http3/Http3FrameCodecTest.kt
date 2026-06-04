package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round-trip coverage for the hand-written [Http3FrameCodec] (RFC 9114 §7.1/§7.2):
 * each frame type encodes to `Type Length Payload` and decodes back, the decoder leaves the
 * buffer positioned exactly after each frame (so a frame loop stays aligned), and an
 * unrecognised type is preserved as [Http3Frame.Unknown] (RFC 9114 §9 skip).
 */
class Http3FrameCodecTest {
    private fun buf(capacity: Int = 256): PlatformBuffer = BufferFactory.Default.allocate(capacity, ByteOrder.BIG_ENDIAN)

    private fun bytesOf(vararg values: Int): PlatformBuffer {
        val b = buf(values.size.coerceAtLeast(1))
        values.forEach { b.writeByte(it.toByte()) }
        b.resetForRead()
        return b
    }

    private fun payloadBytes(frame: Http3Frame): ByteArray =
        when (frame) {
            is Http3Frame.Data -> frame.payload.let { it.readByteArray(it.remaining()) }
            is Http3Frame.Headers -> frame.encodedFieldBlock.let { it.readByteArray(it.remaining()) }
            is Http3Frame.Unknown -> frame.payload.let { it.readByteArray(it.remaining()) }
            else -> ByteArray(0)
        }

    @Test
    fun dataFrameRoundTrips() {
        val body = bytesOf(0xDE, 0xAD, 0xBE, 0xEF)
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.Data(body), EncodeContext.Empty)
        out.resetForRead()
        val decoded = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertIs<Http3Frame.Data>(decoded)
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), payloadBytes(decoded))
    }

    @Test
    fun headersFrameRoundTrips() {
        val block = bytesOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.Headers(block), EncodeContext.Empty)
        out.resetForRead()
        val decoded = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertIs<Http3Frame.Headers>(decoded)
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), payloadBytes(decoded))
    }

    @Test
    fun settingsFrameRoundTrips() {
        val settings =
            Http3Frame.Settings(
                listOf(
                    // QPACK_MAX_TABLE_CAPACITY=4096, MAX_FIELD_SECTION_SIZE=large varint
                    Http3SettingsPair(identifier = 0x01, value = 4096),
                    Http3SettingsPair(identifier = 0x06, value = 151288809941952652L),
                ),
            )
        val out = buf()
        Http3FrameCodec.encode(out, settings, EncodeContext.Empty)
        out.resetForRead()
        val decoded = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertEquals(settings, decoded)
    }

    @Test
    fun emptySettingsFrameRoundTrips() {
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.Settings(emptyList()), EncodeContext.Empty)
        out.resetForRead()
        assertEquals(Http3Frame.Settings(emptyList()), Http3FrameCodec.decode(out, DecodeContext.Empty))
    }

    @Test
    fun goAwayRoundTrips() {
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.GoAway(15293), EncodeContext.Empty)
        out.resetForRead()
        assertEquals(Http3Frame.GoAway(15293), Http3FrameCodec.decode(out, DecodeContext.Empty))
    }

    @Test
    fun cancelPushAndMaxPushIdRoundTrip() {
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.CancelPush(7), EncodeContext.Empty)
        Http3FrameCodec.encode(out, Http3Frame.MaxPushId(494878333L), EncodeContext.Empty)
        out.resetForRead()
        assertEquals(Http3Frame.CancelPush(7), Http3FrameCodec.decode(out, DecodeContext.Empty))
        assertEquals(Http3Frame.MaxPushId(494878333L), Http3FrameCodec.decode(out, DecodeContext.Empty))
    }

    @Test
    fun unknownFrameTypeIsPreservedForSkipping() {
        // 0x21 is a reserved/greasing type (0x1f*0 + 0x21) — must decode to Unknown, payload intact.
        val out = buf()
        QuicVarIntCodec.encode(out, 0x21, EncodeContext.Empty)
        QuicVarIntCodec.encode(out, 3, EncodeContext.Empty)
        out.writeByte(0xAA.toByte())
        out.writeByte(0xBB.toByte())
        out.writeByte(0xCC.toByte())
        out.resetForRead()
        val decoded = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertIs<Http3Frame.Unknown>(decoded)
        assertEquals(0x21L, decoded.type)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), payloadBytes(decoded))
    }

    @Test
    fun multipleFramesDecodeSequentially() {
        // A realistic control-stream prefix: SETTINGS then a DATA frame back to back.
        val out = buf()
        Http3FrameCodec.encode(out, Http3Frame.Settings(listOf(Http3SettingsPair(0x01, 100))), EncodeContext.Empty)
        Http3FrameCodec.encode(out, Http3Frame.Data(bytesOf(1, 2)), EncodeContext.Empty)
        out.resetForRead()

        val first = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertEquals(Http3Frame.Settings(listOf(Http3SettingsPair(0x01, 100))), first)
        val second = Http3FrameCodec.decode(out, DecodeContext.Empty)
        assertIs<Http3Frame.Data>(second)
        assertContentEquals(byteArrayOf(1, 2), payloadBytes(second))
        assertTrue(out.remaining() == 0, "all frame bytes consumed")
    }
}
