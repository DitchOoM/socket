package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [wrapNodeError] — the centralized Node.js → SocketException mapping.
 *
 * Tests every branch with synthetic Node.js error objects, no network I/O needed.
 * Mirrors WrapJvmExceptionTests (JVM) and AppleExceptionMappingTests (Apple).
 */
class WrapNodeErrorMappingTests {
    // Helper to create a Node.js-style error object with a code property
    private fun nodeError(code: String, message: String = "Error: $code"): dynamic {
        val err = js("({})")
        err.code = code
        err.message = message
        // toString() returns the message
        err.toString = js("(function() { return this.message; })")
        return err
    }

    private fun nodeErrorNoCode(message: String): dynamic {
        val err = js("({})")
        err.message = message
        err.toString = js("(function() { return this.message; })")
        return err
    }

    // ==================== ECONNREFUSED ====================

    @Test
    fun econnrefused_mapsToSocketConnectionRefused() {
        val result = wrapNodeError(nodeError("ECONNREFUSED", "connect ECONNREFUSED 127.0.0.1:8080"), "127.0.0.1")
        assertIs<SocketConnectionException.Refused>(result)
    }

    // ==================== ETIMEDOUT ====================

    @Test
    fun etimedout_mapsToSocketTimeoutException() {
        val result = wrapNodeError(nodeError("ETIMEDOUT", "connect ETIMEDOUT 10.0.0.1:443"), "10.0.0.1")
        assertIs<SocketTimeoutException>(result)
        assertTrue(result.message.contains("timed out", ignoreCase = true))
    }

    // ==================== ECONNRESET ====================

    @Test
    fun econnreset_mapsToConnectionReset() {
        val result = wrapNodeError(nodeError("ECONNRESET", "read ECONNRESET"), null)
        assertIs<SocketClosedException.ConnectionReset>(result)
    }

    // ==================== EPIPE ====================

    @Test
    fun epipe_mapsToBrokenPipe() {
        val result = wrapNodeError(nodeError("EPIPE", "write EPIPE"), null)
        assertIs<SocketClosedException.BrokenPipe>(result)
    }

    // ==================== ENETUNREACH ====================

    @Test
    fun enetunreach_mapsToNetworkUnreachable() {
        val result = wrapNodeError(nodeError("ENETUNREACH", "connect ENETUNREACH 192.0.2.1:80"), null)
        assertIs<SocketConnectionException.NetworkUnreachable>(result)
    }

    // ==================== EHOSTUNREACH ====================

    @Test
    fun ehostunreach_mapsToHostUnreachable() {
        val result = wrapNodeError(nodeError("EHOSTUNREACH", "connect EHOSTUNREACH 198.51.100.1:80"), null)
        assertIs<SocketConnectionException.HostUnreachable>(result)
    }

    // ==================== DNS (getaddrinfo) ====================

    @Test
    fun getaddrinfo_mapsToSocketUnknownHostException() {
        val result = wrapNodeError(nodeErrorNoCode("getaddrinfo ENOTFOUND bad.host"), "bad.host")
        assertIs<SocketUnknownHostException>(result)
    }

    // ==================== TLS errors ====================

    @Test
    fun errTls_mapsToSSLProtocolException() {
        val result = wrapNodeError(nodeErrorNoCode("ERR_TLS_CERT_ALTNAME_INVALID"), null)
        assertIs<SSLProtocolException>(result)
    }

    @Test
    fun sslError_mapsToSSLProtocolException() {
        val result = wrapNodeError(nodeErrorNoCode("SSL routines:ssl3_get_record:wrong version number"), null)
        assertIs<SSLProtocolException>(result)
    }

    // ==================== Fallback ====================

    @Test
    fun unknownError_mapsToSocketIOException() {
        val result = wrapNodeError(nodeErrorNoCode("something unexpected"), null)
        assertIs<SocketIOException>(result)
    }

    @Test
    fun errorWithUnknownCode_mapsToSocketIOException() {
        val result = wrapNodeError(nodeError("ENOENT", "ENOENT: no such file"), null)
        assertIs<SocketIOException>(result)
    }

    // ==================== General (no direct mapping — tested via integration) ====================

    @Test
    fun closedSocketRead_errorWithNoCode_mapsToSocketIOException() {
        // wrapNodeError has no explicit "General" path — unknown errors become SocketIOException
        // SocketClosedException.General is produced by the read/write paths in NodeSocketClient,
        // not by wrapNodeError. This test documents that wrapNodeError's fallback is SocketIOException.
        val result = wrapNodeError(nodeErrorNoCode("channel closed"), null)
        assertIs<SocketIOException>(result)
    }
}
