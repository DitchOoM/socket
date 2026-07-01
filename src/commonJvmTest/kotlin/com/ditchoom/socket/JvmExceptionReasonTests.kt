package com.ditchoom.socket

import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for the JVM error-mapper's structured [ConnectionFailureReason] output (issue #166).
 * These call [wrapJvmException] with synthetic platform exceptions — no network — to pin the
 * cause-classification the mapper adds on top of the exception subtype.
 */
class JvmExceptionReasonTests {
    @Test
    fun outOfMemory_mapsToOutOfMemoryReason() {
        val mapped = wrapJvmException(OutOfMemoryError("Java heap space"))
        val other = assertIs<SocketConnectionException.Other>(mapped)
        assertEquals(ConnectionFailureReason.OutOfMemory, other.reason)
    }

    @Test
    fun sslHandshakeWithCertCause_mapsToTlsBadCertificate() {
        val ex = SSLHandshakeException("PKIX path building failed")
        ex.initCause(CertificateException("unable to find valid certification path"))
        val mapped = wrapJvmException(ex)
        val ssl = assertIs<SSLHandshakeFailedException>(mapped)
        assertEquals(ConnectionFailureReason.TlsBadCertificate, ssl.reason)
    }

    @Test
    fun sslHandshakeWithoutCert_mapsToTlsHandshake() {
        val mapped = wrapJvmException(SSLHandshakeException("Received fatal alert: handshake_failure"))
        val ssl = assertIs<SSLHandshakeFailedException>(mapped)
        // message says "handshake_failure" but no cert cause and no cert wording → generic handshake.
        assertEquals(ConnectionFailureReason.TlsHandshake, ssl.reason)
    }

    @Test
    fun refused_carriesRefusedReason() {
        val mapped = wrapJvmException(java.net.ConnectException("Connection refused"), host = "h", port = 1)
        val refused = assertIs<SocketConnectionException.Refused>(mapped)
        assertEquals(ConnectionFailureReason.Refused, refused.reason)
    }
}
