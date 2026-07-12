package com.ditchoom.socket.webtransport

import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import com.ditchoom.socket.ConnectionFailureReason
import com.ditchoom.socket.SSLHandshakeFailedException
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Deterministic unit tests for the native adapter seam [toNeutralWebTransportFailure] — the highest-touch
 * mapping that replaces the old `WebTransportException("...: ${t.message}")` rewrap the neutral mapper
 * then string-matched. Each representative UNDERLYING failure is fed in and the typed neutral
 * [WebTransportFailure] it maps to is asserted, so TLS-vs-cert-vs-generic classification is pinned by
 * TYPE (and, for QUIC crypto, by the typed TLS alert code) — never by message text — and stays identical
 * across runs.
 *
 * (The http3 [com.ditchoom.socket.http3.WebTransportException] input branch is proven end-to-end by the
 * live loopback suite — connectWebTransport throwing the typed failure — since that exception's
 * constructor is internal to socket-http3 and can't be fabricated here.)
 */
class WebTransportFailureMappingTest {
    @Test
    fun sslHandshake_badCertificateReason_mapsToTlsHandshakeCert() {
        val f =
            SSLHandshakeFailedException("untrusted root", reason = ConnectionFailureReason.TlsBadCertificate)
                .toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.TlsHandshake>(f)
        assertEquals(true, f.badCertificate)
    }

    @Test
    fun sslHandshake_genericReason_mapsToTlsHandshakeNotCert() {
        val f =
            SSLHandshakeFailedException("handshake_failure", reason = ConnectionFailureReason.TlsHandshake)
                .toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.TlsHandshake>(f)
        assertEquals(false, f.badCertificate)
    }

    @Test
    fun certificatePinning_mapsToTlsHandshakeCert() {
        val f = CertificateHashPinningException(CertificateHashPinningFailure.NoPeerCertificate).toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.TlsHandshake>(f)
        assertEquals(true, f.badCertificate)
    }

    @Test
    fun quicCryptoError_certAlert_mapsToTlsHandshakeCert() {
        // TLS alert 45 = certificate_expired → a certificate rejection.
        val f = QuicCloseException(QuicError.CryptoError(45), "cert expired").toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.TlsHandshake>(f)
        assertEquals(true, f.badCertificate)
    }

    @Test
    fun quicCryptoError_handshakeAlert_mapsToTlsHandshakeNotCert() {
        // TLS alert 40 = handshake_failure → a generic handshake failure, not a cert rejection.
        val f = QuicCloseException(QuicError.CryptoError(40), "handshake failure").toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.TlsHandshake>(f)
        assertEquals(false, f.badCertificate)
    }

    @Test
    fun quicNonCryptoClose_mapsToSessionError() {
        val f = QuicCloseException(QuicError.IdleTimeout, "idle timeout").toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.SessionError>(f)
    }

    @Test
    fun genericThrowable_mapsToSessionError() {
        val f = RuntimeException("boom").toNeutralWebTransportFailure()
        assertIs<WebTransportFailure.SessionError>(f)
    }
}
