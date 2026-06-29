package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.deterministic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The WebTransport analogue of [Http3ConformanceCorpusTests]: a deterministic, every-platform
 * **error-path corpus** for the WebTransport-over-HTTP/3 wire (draft-ietf-webtrans-http3 §4–§6, RFC 9297
 * Capsule Protocol). Where [WebTransportCapsuleTests] / [WebTransportErrorCodeTests] pin the happy-path
 * round-trips and the error-code remap, this systematically crafts malformed + edge wire images and
 * asserts the **exact** typed outcome the mux produces — the [Http3Violation] (and its RFC 9114 error
 * code) for a malformed capsule, and faithful dispatch/alignment for a stream of capsules.
 *
 * It drives the same leaf codecs the mux's capsule loop uses ([WebTransportWire.readCloseSessionValue] +
 * the Type/Length varint decode + skip-by-length for unknown types — see `WebTransportMux.parseCapsules`),
 * plus the §4.1/§4.2 stream framing and §4.4 datagram Quarter-Stream-ID mapping. All pure, no I/O.
 */
class WebTransportConformanceCorpusTests {
    private fun buffer(capacity: Int): PlatformBuffer = BufferFactory.deterministic().allocate(capacity.coerceAtLeast(1))

    // ---- WT_CLOSE_SESSION value: malformed-length boundary sweep -------------------------------------

    @Test
    fun closeSessionValue_shorterThanCodeIsCloseCapsuleTooShort() {
        // A WT_CLOSE_SESSION value must carry at least the 4-byte Application Error Code (draft §6).
        for (valueLength in 0..3) {
            val buf = buffer(4)
            repeat(valueLength) { buf.writeByte(0x00) } // honour the precondition: `valueLength` bytes present
            buf.resetForRead()
            val ex =
                assertFailsWith<Http3StreamException>("value length $valueLength must be rejected") {
                    WebTransportWire.readCloseSessionValue(buf, valueLength)
                }
            assertIs<Http3Violation.WebTransportCloseCapsuleTooShort>(ex.violation, "exact violation for length $valueLength")
            assertEquals(Http3ErrorCode.GENERAL_PROTOCOL_ERROR, ex.errorCode, "H3_GENERAL_PROTOCOL_ERROR for length $valueLength")
        }
    }

    @Test
    fun closeSessionValue_exactlyFourBytesIsCodeWithEmptyReason() {
        // The minimum well-formed value: 4 code bytes, no reason.
        val buf = buffer(4)
        buf.writeByte(0x12)
        buf.writeByte(0x34)
        buf.writeByte(0x56)
        buf.writeByte(0x78)
        buf.resetForRead()
        assertEquals(WebTransportCloseInfo(0x12345678, ""), WebTransportWire.readCloseSessionValue(buf, 4))
    }

    @Test
    fun closeSessionValue_validCorpus_decodesCodeAndReasonExactly() {
        // (code, reason) corpus across the 32-bit code range + reason shapes, each round-tripped through
        // the real writer then decoded by readCloseSessionValue, asserting the value is consumed exactly.
        val corpus =
            listOf(
                0 to "",
                1 to "x",
                42 to "going away",
                -0x0F0E0D0C to "full u32 code", // 0xF0F1F2F4 as signed Int
                0x7FFFFFFF to "résumé — 完了", // multi-byte UTF-8 reason
                3 to "a".repeat(WebTransportWire.MAX_CLOSE_REASON_BYTES), // §6 max reason (1024 bytes)
            )
        for ((code, reason) in corpus) {
            val reasonBytes = qpackUtf8ByteLength(reason)
            val size = WebTransportWire.closeSessionCapsuleSize(reasonBytes)
            val buf = buffer(size)
            WebTransportWire.writeCloseSessionCapsule(buf, code, reason, reasonBytes)
            buf.resetForRead()
            assertEquals(WebTransportWire.WT_CLOSE_SESSION, VarIntCodec.decode(buf, DecodeContext.Empty))
            val length = VarIntCodec.decode(buf, DecodeContext.Empty).toInt()
            assertEquals(4 + reasonBytes, length, "declared value length for code=$code")
            assertEquals(WebTransportCloseInfo(code, reason), WebTransportWire.readCloseSessionValue(buf, length))
            assertEquals(0, buf.remaining(), "the value was consumed exactly for code=$code")
        }
    }

    // ---- Capsule stream: dispatch, unknown-skip alignment, and a malformed capsule mid-stream ---------

    private sealed interface CapsuleEvent {
        data class Close(
            val info: WebTransportCloseInfo,
        ) : CapsuleEvent

        data object Drain : CapsuleEvent

        data class Unknown(
            val type: Long,
        ) : CapsuleEvent
    }

    /** Decode one capsule (Type, Length, then act on the value) exactly as `WebTransportMux.parseCapsules` does. */
    private fun decodeCapsule(buf: PlatformBuffer): CapsuleEvent {
        val type = VarIntCodec.decode(buf, DecodeContext.Empty)
        val length = VarIntCodec.decode(buf, DecodeContext.Empty).toInt()
        return when (type) {
            WebTransportWire.WT_CLOSE_SESSION -> CapsuleEvent.Close(WebTransportWire.readCloseSessionValue(buf, length))
            WebTransportWire.WT_DRAIN_SESSION -> CapsuleEvent.Drain.also { repeat(length) { buf.readByte() } }
            else -> CapsuleEvent.Unknown(type).also { repeat(length) { buf.readByte() } } // unknown: honour Length
        }
    }

    private fun writeUnknownCapsule(
        buf: PlatformBuffer,
        type: Long,
        value: ByteArray,
    ) {
        VarIntCodec.encode(buf, type, EncodeContext.Empty)
        VarIntCodec.encode(buf, value.size.toLong(), EncodeContext.Empty)
        for (b in value) buf.writeByte(b)
    }

    @Test
    fun capsuleStream_drainThenUnknownSkipThenClose_dispatchesInOrderAndStaysAligned() {
        val buf = buffer(2048)
        WebTransportWire.writeDrainSessionCapsule(buf)
        writeUnknownCapsule(buf, type = 0x1f * 0x40 + 0x21, value = ByteArray(7) { (it + 1).toByte() }) // GREASE-ish
        val reasonBytes = qpackUtf8ByteLength("bye")
        WebTransportWire.writeCloseSessionCapsule(buf, 9, "bye", reasonBytes)
        buf.resetForRead()

        assertEquals(CapsuleEvent.Drain, decodeCapsule(buf))
        assertIs<CapsuleEvent.Unknown>(decodeCapsule(buf)) // value skipped by Length → still aligned
        assertEquals(CapsuleEvent.Close(WebTransportCloseInfo(9, "bye")), decodeCapsule(buf))
        assertEquals(0, buf.remaining(), "every capsule consumed exactly — alignment held across the stream")
    }

    @Test
    fun capsuleStream_malformedCloseMidStream_raisesTheTypedViolation() {
        // A drain, then a WT_CLOSE_SESSION whose declared Length (2) is short of the 4-byte code.
        val buf = buffer(64)
        WebTransportWire.writeDrainSessionCapsule(buf)
        VarIntCodec.encode(buf, WebTransportWire.WT_CLOSE_SESSION, EncodeContext.Empty)
        VarIntCodec.encode(buf, 2L, EncodeContext.Empty) // malformed: < 4
        buf.writeByte(0x00)
        buf.writeByte(0x00)
        buf.resetForRead()

        assertEquals(CapsuleEvent.Drain, decodeCapsule(buf))
        val ex = assertFailsWith<Http3StreamException> { decodeCapsule(buf) }
        assertIs<Http3Violation.WebTransportCloseCapsuleTooShort>(ex.violation)
        assertEquals(Http3ErrorCode.GENERAL_PROTOCOL_ERROR, ex.errorCode)
    }

    // ---- §4.1 / §4.2 stream framing: signal + Session ID --------------------------------------------

    @Test
    fun streamFraming_bidiAndUni_signalThenSessionIdThenAppBytes() {
        // The mux reads the leading varint (the WT bidi signal 0x41 / uni type 0x54), then the Session ID
        // varint, then hands the remaining bytes to the application. Session IDs span the varint widths.
        val signals = listOf(WebTransportWire.WT_BIDI_STREAM_SIGNAL, WebTransportWire.WT_UNI_STREAM_TYPE)
        val sessionIds = longArrayOf(0, 4, 0x3C, 0x4040, 0x4000_0000, 0x4000_0000_0000)
        val app = byteArrayOf(0x68, 0x69) // "hi"
        for (signal in signals) {
            for (sessionId in sessionIds) {
                val buf = buffer(32)
                VarIntCodec.encode(buf, signal, EncodeContext.Empty)
                VarIntCodec.encode(buf, sessionId, EncodeContext.Empty)
                for (b in app) buf.writeByte(b)
                buf.resetForRead()

                assertEquals(signal, VarIntCodec.decode(buf, DecodeContext.Empty), "signal")
                assertEquals(sessionId, VarIntCodec.decode(buf, DecodeContext.Empty), "session id $sessionId")
                assertEquals(app.size, buf.remaining(), "exactly the app bytes follow the WT stream header")
            }
        }
    }

    // ---- §4.4 datagram Quarter Stream ID ------------------------------------------------------------

    @Test
    fun datagram_quarterStreamId_roundTripsForConnectStreamIds() {
        // CONNECT streams are client-initiated bidirectional (id % 4 == 0), so qsid = id/4 and id = qsid*4
        // is exact across the varint widths (the mux multiplies the decoded quarter id back by four).
        for (quarter in longArrayOf(0, 1, 15, 0x40, 0x4000, 0x1000_0000)) {
            val sessionId = quarter * 4
            assertEquals(quarter, WebTransportWire.quarterStreamId(sessionId))
            assertTrue(sessionId % 4 == 0L, "CONNECT-stream id is a multiple of four")
        }
    }
}
