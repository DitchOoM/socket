package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Cross-platform tests for the [ConnectionFailure] / [ConnectionFailureReason] surface (issue #166):
 * establishment/handshake exceptions expose an exhaustive, platform-neutral cause, recoverable off the
 * catch-all [SocketException] without knowing the concrete subtype.
 */
class ConnectionFailureReasonTest {
    @Test
    fun namedSubtypes_carryFixedReasons() {
        assertEquals(ConnectionFailureReason.Refused, SocketConnectionException.Refused("h", 1).reason)
        assertEquals(ConnectionFailureReason.NetworkUnreachable, SocketConnectionException.NetworkUnreachable("x").reason)
        assertEquals(ConnectionFailureReason.HostUnreachable, SocketConnectionException.HostUnreachable("x").reason)
        assertEquals(ConnectionFailureReason.UnknownHost, SocketUnknownHostException("h").reason)
        assertEquals(ConnectionFailureReason.Timeout, SocketTimeoutException("x").reason)
    }

    @Test
    fun other_carriesArbitraryReason() {
        val e = SocketConnectionException.Other(ConnectionFailureReason.OutOfMemory, "oom")
        assertEquals(ConnectionFailureReason.OutOfMemory, e.reason)
    }

    @Test
    fun ssl_defaultsAndOverrides() {
        assertEquals(ConnectionFailureReason.TlsHandshake, SSLHandshakeFailedException("x").reason)
        assertEquals(
            ConnectionFailureReason.TlsBadCertificate,
            SSLHandshakeFailedException("x", reason = ConnectionFailureReason.TlsBadCertificate).reason,
        )
        assertEquals(ConnectionFailureReason.TlsProtocolMismatch, SSLProtocolException("x").reason)
    }

    @Test
    fun reason_isRecoverableFromCatchAll() {
        val thrown: SocketException = SocketConnectionException.Refused("h", 443)
        val reason = (thrown as? ConnectionFailure)?.reason
        assertIs<ConnectionFailureReason.Refused>(reason)
    }
}
