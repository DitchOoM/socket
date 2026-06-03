package com.ditchoom.socket.quic

import com.ditchoom.socket.SocketClosedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuicErrorTests {
    // --- Transport error code mapping (RFC 9000 §20.1) ---

    @Test
    fun fromTransportCode_noError() {
        val error = QuicError.fromTransportCode(0x0)
        assertIs<QuicError.NoError>(error)
        assertEquals(0x0L, error.code)
    }

    @Test
    fun fromTransportCode_internalError() {
        val error = QuicError.fromTransportCode(0x1)
        assertIs<QuicError.InternalError>(error)
        assertEquals(0x1L, error.code)
    }

    @Test
    fun fromTransportCode_connectionRefused() {
        val error = QuicError.fromTransportCode(0x2)
        assertIs<QuicError.ConnectionRefused>(error)
        assertEquals(0x2L, error.code)
    }

    @Test
    fun fromTransportCode_flowControlError() {
        val error = QuicError.fromTransportCode(0x3)
        assertIs<QuicError.FlowControlError>(error)
        assertEquals(0x3L, error.code)
    }

    @Test
    fun fromTransportCode_streamLimitError() {
        val error = QuicError.fromTransportCode(0x4)
        assertIs<QuicError.StreamLimitError>(error)
        assertEquals(0x4L, error.code)
    }

    @Test
    fun fromTransportCode_streamStateError() {
        val error = QuicError.fromTransportCode(0x5)
        assertIs<QuicError.StreamStateError>(error)
        assertEquals(0x5L, error.code)
    }

    @Test
    fun fromTransportCode_finalSizeError() {
        val error = QuicError.fromTransportCode(0x6)
        assertIs<QuicError.FinalSizeError>(error)
        assertEquals(0x6L, error.code)
    }

    @Test
    fun fromTransportCode_frameEncodingError() {
        val error = QuicError.fromTransportCode(0x7)
        assertIs<QuicError.FrameEncodingError>(error)
        assertEquals(0x7L, error.code)
    }

    @Test
    fun fromTransportCode_transportParameterError() {
        val error = QuicError.fromTransportCode(0x8)
        assertIs<QuicError.TransportParameterError>(error)
        assertEquals(0x8L, error.code)
    }

    @Test
    fun fromTransportCode_connectionIdLimitError() {
        val error = QuicError.fromTransportCode(0x9)
        assertIs<QuicError.ConnectionIdLimitError>(error)
        assertEquals(0x9L, error.code)
    }

    @Test
    fun fromTransportCode_protocolViolation() {
        val error = QuicError.fromTransportCode(0xA)
        assertIs<QuicError.ProtocolViolation>(error)
        assertEquals(0xAL, error.code)
    }

    @Test
    fun fromTransportCode_invalidToken() {
        val error = QuicError.fromTransportCode(0xB)
        assertIs<QuicError.InvalidToken>(error)
        assertEquals(0xBL, error.code)
    }

    @Test
    fun fromTransportCode_cryptoBufferExceeded() {
        val error = QuicError.fromTransportCode(0xD)
        assertIs<QuicError.CryptoBufferExceeded>(error)
        assertEquals(0xDL, error.code)
    }

    @Test
    fun fromTransportCode_keyUpdateError() {
        val error = QuicError.fromTransportCode(0xE)
        assertIs<QuicError.KeyUpdateError>(error)
        assertEquals(0xEL, error.code)
    }

    @Test
    fun fromTransportCode_aeadLimitReached() {
        val error = QuicError.fromTransportCode(0xF)
        assertIs<QuicError.AeadLimitReached>(error)
        assertEquals(0xFL, error.code)
    }

    @Test
    fun fromTransportCode_noViablePath() {
        val error = QuicError.fromTransportCode(0x10)
        assertIs<QuicError.NoViablePath>(error)
        assertEquals(0x10L, error.code)
    }

    // --- Crypto errors (0x100 + TLS alert) ---

    @Test
    fun fromTransportCode_cryptoError_handshakeFailure() {
        // TLS alert 40 = handshake_failure
        val error = QuicError.fromTransportCode(0x100L + 40)
        assertIs<QuicError.CryptoError>(error)
        assertEquals(40, error.tlsAlert)
        assertEquals(0x128L, error.code)
    }

    @Test
    fun fromTransportCode_cryptoError_certificateExpired() {
        // TLS alert 45 = certificate_expired
        val error = QuicError.fromTransportCode(0x100L + 45)
        assertIs<QuicError.CryptoError>(error)
        assertEquals(45, error.tlsAlert)
    }

    @Test
    fun fromTransportCode_cryptoError_rangeStart() {
        val error = QuicError.fromTransportCode(0x100L)
        assertIs<QuicError.CryptoError>(error)
        assertEquals(0, error.tlsAlert)
    }

    @Test
    fun fromTransportCode_cryptoError_rangeEnd() {
        val error = QuicError.fromTransportCode(0x1FFL)
        assertIs<QuicError.CryptoError>(error)
        assertEquals(0xFF, error.tlsAlert)
    }

    // --- Unknown codes ---

    @Test
    fun fromTransportCode_unknownCode_mapsToUnknownTransport_preservingCode() {
        val error = QuicError.fromTransportCode(0xFFFF)
        assertIs<QuicError.UnknownTransport>(error)
        // The wire code survives decoding instead of collapsing to InternalError.
        assertEquals(0xFFFFL, error.code)
    }

    @Test
    fun fromTransportCode_unassignedCodeAboveCryptoRange_mapsToUnknownTransport() {
        // 0x200 is above the CRYPTO_ERROR range (0x100..0x1ff) and currently unassigned.
        val error = QuicError.fromTransportCode(0x200)
        assertIs<QuicError.UnknownTransport>(error)
        assertEquals(0x200L, error.code)
    }

    @Test
    fun fromTransportCode_applicationError_0xC() {
        // RFC 9000 §20.1: 0x0c is APPLICATION_ERROR (transport-level), not reserved.
        val error = QuicError.fromTransportCode(0xC)
        assertIs<QuicError.TransportApplicationError>(error)
        assertEquals(0xCL, error.code)
    }

    // --- Direct construction ---

    @Test
    fun applicationError_preservesCode() {
        val error = QuicError.ApplicationError(42)
        assertEquals(42L, error.code)
        assertEquals(42L, error.applicationCode)
    }

    @Test
    fun platformError_hasNegativeCode() {
        val error = QuicError.PlatformError(RuntimeException("boom"))
        assertEquals(-1L, error.code)
        assertEquals("boom", error.cause.message)
    }

    @Test
    fun cryptoError_codeIncludesOffset() {
        val error = QuicError.CryptoError(48) // unknown_ca
        assertEquals(0x100L + 48, error.code)
    }

    // --- Thrown channel carries the structured reason ---

    @Test
    fun quicCloseException_isSocketClosedException_andCarriesError() {
        val ex = QuicCloseException(QuicError.ProtocolViolation, "boom")
        // Caught uniformly with TCP/TLS connection-lost errors...
        assertIs<SocketClosedException>(ex)
        // ...while the structured protocol reason survives the throw.
        assertIs<QuicError.ProtocolViolation>(ex.quicError)
        assertEquals("boom", ex.message)
    }
}
