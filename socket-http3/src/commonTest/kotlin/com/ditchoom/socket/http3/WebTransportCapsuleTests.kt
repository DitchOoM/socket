package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure, deterministic tests for the WebTransport wire constants + WT_CLOSE_SESSION capsule codec
 * (draft-ietf-webtrans-http3 §6, RFC 9297 Capsule Protocol) — no I/O, so they run on every platform.
 */
class WebTransportCapsuleTests {
    private fun roundTripCloseCapsule(
        code: Int,
        reason: String,
    ): WebTransportCloseInfo {
        val reasonBytes = qpackUtf8ByteLength(reason)
        val size = WebTransportWire.closeSessionCapsuleSize(reasonBytes)
        val buffer: PlatformBuffer = BufferFactory.deterministic().allocate(size.coerceAtLeast(1))
        WebTransportWire.writeCloseSessionCapsule(buffer, code, reason, reasonBytes)
        assertEquals(size, buffer.position(), "writer must emit exactly closeSessionCapsuleSize bytes")
        buffer.resetForRead()

        // Parse it back the way the capsule loop does: Type, Length, then the value.
        val type = VarIntCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(WebTransportWire.WT_CLOSE_SESSION, type)
        val length = VarIntCodec.decode(buffer, DecodeContext.Empty).toInt()
        assertEquals(4 + reasonBytes, length)
        return WebTransportWire.readCloseSessionValue(buffer, length)
    }

    @Test
    fun closeCapsule_roundTrips_codeAndReason() {
        assertEquals(WebTransportCloseInfo(42, "going away"), roundTripCloseCapsule(42, "going away"))
    }

    @Test
    fun closeCapsule_roundTrips_zeroCodeEmptyReason() {
        assertEquals(WebTransportCloseInfo(0, ""), roundTripCloseCapsule(0, ""))
    }

    @Test
    fun closeCapsule_roundTrips_largeCode() {
        // A full 32-bit code must survive the explicit network-order byte packing.
        val code = -0x0F0E0D0C // 0xF0F1F2F4 as a signed Int
        assertEquals(WebTransportCloseInfo(code, "x"), roundTripCloseCapsule(code, "x"))
    }

    @Test
    fun closeCapsule_roundTrips_multiByteUtf8Reason() {
        val reason = "résumé — 完了"
        val info = roundTripCloseCapsule(7, reason)
        assertEquals(WebTransportCloseInfo(7, reason), info)
    }

    @Test
    fun readCloseSessionValue_rejectsTooShortValue() {
        // A value of fewer than the 4 code bytes is malformed.
        val buffer = BufferFactory.deterministic().allocate(4)
        buffer.writeByte(0)
        buffer.writeByte(0)
        buffer.resetForRead()
        assertFailsWith<Http3StreamException> { WebTransportWire.readCloseSessionValue(buffer, 2) }
    }

    @Test
    fun drainCapsule_encodesTypeAndZeroLength() {
        val size = WebTransportWire.drainSessionCapsuleSize()
        val buffer: PlatformBuffer = BufferFactory.deterministic().allocate(size)
        WebTransportWire.writeDrainSessionCapsule(buffer)
        assertEquals(size, buffer.position(), "writer must emit exactly drainSessionCapsuleSize bytes")
        buffer.resetForRead()

        // Parse it the way the capsule loop does: Type, then a zero-length value.
        val type = VarIntCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(WebTransportWire.WT_DRAIN_SESSION, type)
        val length = VarIntCodec.decode(buffer, DecodeContext.Empty).toInt()
        assertEquals(0, length, "WT_DRAIN_SESSION carries no value")
        assertEquals(0, buffer.remaining(), "no bytes follow the zero-length value")
    }

    @Test
    fun quarterStreamId_dividesByFour() {
        // CONNECT streams are client-initiated bidirectional (id % 4 == 0), so the division is exact.
        assertEquals(0L, WebTransportWire.quarterStreamId(0))
        assertEquals(1L, WebTransportWire.quarterStreamId(4))
        assertEquals(15L, WebTransportWire.quarterStreamId(60))
    }

    @Test
    fun streamTypeConstants_matchDraft() {
        assertEquals(0x54L, WebTransportWire.WT_UNI_STREAM_TYPE)
        assertEquals(0x41L, WebTransportWire.WT_BIDI_STREAM_SIGNAL)
        assertEquals(0x2843L, WebTransportWire.WT_CLOSE_SESSION)
        assertEquals(0x78aeL, WebTransportWire.WT_DRAIN_SESSION)
    }
}
