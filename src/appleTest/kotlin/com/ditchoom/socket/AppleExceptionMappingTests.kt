package com.ditchoom.socket

import com.ditchoom.socket.nwhelpers.SocketErrorTypeDns
import com.ditchoom.socket.nwhelpers.SocketErrorTypePosix
import com.ditchoom.socket.nwhelpers.SocketErrorTypeTls
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for Apple NWSocketWrapper.mapSocketException().
 *
 * Tests the pure mapping logic from SocketErrorType + errorString → sealed SocketException subtypes.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleExceptionMappingTests {
    // ==================== DNS errors ====================

    @Test
    fun dnsError_mapsToSocketUnknownHostException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypeDns, "DNS error: nodomain")
        assertIs<SocketUnknownHostException>(result)
        assertTrue(result.message.contains("DNS error: nodomain"))
    }

    @Test
    fun dnsError_withNullMessage_mapsToSocketUnknownHostException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypeDns, null)
        assertIs<SocketUnknownHostException>(result)
    }

    // ==================== TLS errors ====================

    @Test
    fun tlsError_handshake_mapsToSSLHandshakeFailedException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypeTls, "errSSLXCertChainInvalid: Invalid certificate chain")
        assertIs<SSLHandshakeFailedException>(result)
        assertTrue(result.message.contains("certificate"))
    }

    @Test
    fun tlsError_protocol_mapsToSSLProtocolException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypeTls, "errSSLProtocol: SSL protocol error")
        assertIs<SSLProtocolException>(result)
    }

    @Test
    fun tlsError_withNullMessage_mapsToSSLProtocolException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypeTls, null)
        assertIs<SSLProtocolException>(result)
    }

    // ==================== POSIX errors ====================

    @Test
    fun posixError_connectionRefused_mapsToSocketConnectionRefusedException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 111: Connection refused")
        assertIs<SocketConnectionException.Refused>(result)
    }

    @Test
    fun posixError_timedOut_mapsToSocketTimeoutException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 110: Connection timed out")
        assertIs<SocketTimeoutException>(result)
    }

    @Test
    fun posixError_connectionReset_mapsToSocketClosedConnectionReset() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 104: Connection reset by peer")
        assertIs<SocketClosedException.ConnectionReset>(result)
    }

    @Test
    fun posixError_brokenPipe_mapsToSocketClosedBrokenPipe() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 32: Broken pipe")
        assertIs<SocketClosedException.BrokenPipe>(result)
    }

    @Test
    fun posixError_networkUnreachable_mapsToNetworkUnreachable() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 101: Network is unreachable")
        assertIs<SocketConnectionException.NetworkUnreachable>(result)
    }

    @Test
    fun posixError_hostUnreachable_mapsToHostUnreachable() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 113: No route to host (Host is unreachable)")
        assertIs<SocketConnectionException.HostUnreachable>(result)
    }

    @Test
    fun posixError_generic_mapsToSocketIOException() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 99: Something else")
        assertIs<SocketIOException>(result)
    }

    // ==================== Unknown errors ====================

    @Test
    fun unknownErrorType_mapsToSocketIOException() {
        val result = NWSocketWrapper.mapSocketException(99, "some unknown error")
        assertIs<SocketIOException>(result)
    }

    // ==================== General ====================

    @Test
    fun posixError_notConnected_mapsToSocketClosedBrokenPipe() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 57: Socket is not connected")
        assertIs<SocketClosedException.BrokenPipe>(result)
    }

    // ==================== Fallback unreachable ====================

    @Test
    fun posixError_bareUnreachable_mapsToNetworkUnreachable() {
        val result = NWSocketWrapper.mapSocketException(SocketErrorTypePosix, "POSIX error 65: Destination unreachable")
        assertIs<SocketConnectionException.NetworkUnreachable>(result)
    }
}
