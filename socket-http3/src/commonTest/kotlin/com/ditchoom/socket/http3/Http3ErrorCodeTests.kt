package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the RFC 9114 §8.1 / RFC 9204 §8.3 wire values and the [Http3StreamException] error-code
 * contract. Wrong constants here mean we'd put the wrong code on the wire (CONNECTION_CLOSE /
 * RESET_STREAM), so they're asserted explicitly rather than trusted to the source.
 */
class Http3ErrorCodeTests {
    @Test
    fun http3ErrorCodesMatchRfc9114() {
        assertEquals(0x0100L, Http3ErrorCode.NO_ERROR)
        assertEquals(0x0101L, Http3ErrorCode.GENERAL_PROTOCOL_ERROR)
        assertEquals(0x0102L, Http3ErrorCode.INTERNAL_ERROR)
        assertEquals(0x0103L, Http3ErrorCode.STREAM_CREATION_ERROR)
        assertEquals(0x0104L, Http3ErrorCode.CLOSED_CRITICAL_STREAM)
        assertEquals(0x0105L, Http3ErrorCode.FRAME_UNEXPECTED)
        assertEquals(0x0106L, Http3ErrorCode.FRAME_ERROR)
        assertEquals(0x0107L, Http3ErrorCode.EXCESSIVE_LOAD)
        assertEquals(0x0108L, Http3ErrorCode.ID_ERROR)
        assertEquals(0x0109L, Http3ErrorCode.SETTINGS_ERROR)
        assertEquals(0x010aL, Http3ErrorCode.MISSING_SETTINGS)
        assertEquals(0x010bL, Http3ErrorCode.REQUEST_REJECTED)
        assertEquals(0x010cL, Http3ErrorCode.REQUEST_CANCELLED)
        assertEquals(0x010dL, Http3ErrorCode.REQUEST_INCOMPLETE)
        assertEquals(0x010eL, Http3ErrorCode.MESSAGE_ERROR)
        assertEquals(0x010fL, Http3ErrorCode.CONNECT_ERROR)
        assertEquals(0x0110L, Http3ErrorCode.VERSION_FALLBACK)
    }

    @Test
    fun qpackErrorCodesMatchRfc9204() {
        assertEquals(0x0200L, Http3ErrorCode.QPACK_DECOMPRESSION_FAILED)
        assertEquals(0x0201L, Http3ErrorCode.QPACK_ENCODER_STREAM_ERROR)
        assertEquals(0x0202L, Http3ErrorCode.QPACK_DECODER_STREAM_ERROR)
    }

    @Test
    fun streamExceptionDerivesErrorCodeFromViolation() {
        // The exception's wire error code is derived from its typed violation, never passed loose.
        assertEquals(Http3ErrorCode.GENERAL_PROTOCOL_ERROR, Http3StreamException(Http3Violation.StreamResetByPeer).errorCode)
        assertEquals(
            Http3ErrorCode.FRAME_UNEXPECTED,
            Http3StreamException(Http3Violation.UnexpectedFrame(Http3FrameType.DATA, Http3FrameContext.CONTROL_STREAM)).errorCode,
        )
        assertEquals(Http3ErrorCode.SETTINGS_ERROR, Http3StreamException(Http3Violation.DuplicateSetting(0x01)).errorCode)
    }

    @Test
    fun violationDescribeRendersTypedFields() {
        // describe() builds the diagnostic message from the variant's typed fields (no hand-written strings).
        assertEquals(
            "reserved HTTP/2 frame type 0x2 received",
            Http3StreamException(Http3Violation.ReservedHttp2Frame(0x02)).message,
        )
        assertEquals(
            "push id 9 exceeds the current maximum (4)",
            Http3Violation.PushIdExceedsMax(pushId = 9, max = 4).describe(),
        )
    }

    @Test
    fun violationChainsUnderlyingCause() {
        // A wire-driven failure wrapped into a cause-bearing violation is chained onto the exception.
        val root = IllegalStateException("boom")
        assertEquals(root, Http3StreamException(Http3Violation.QpackDecompressionFailed(root)).cause)
    }
}
