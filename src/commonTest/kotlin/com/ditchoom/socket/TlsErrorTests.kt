package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for TLS/SSL error handling.
 *
 * Exception hierarchy:
 * - SocketException (base)
 *   - SSLSocketException
 *     - SSLHandshakeFailedException
 */
class TlsErrorTests {

    @Test
    fun tlsToNonTlsPort() = runTestNoTimeSkipping {
        // Try to establish TLS connection to a non-TLS port (HTTP on port 80)
        try {
            ClientSocket.connect(
                port = 80,
                hostname = "example.com",
                tls = true,
                timeout = 10.seconds
            ) { socket ->
                // If we get here, something is wrong
                socket.close()
                fail("TLS handshake should have failed on non-TLS port")
            }
        } catch (e: SocketException) {
            // Expected - covers SSLSocketException, SSLHandshakeFailedException, connection reset
        } catch (e: UnsupportedOperationException) {
            // Skip on platforms that don't support TLS (browser)
            if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                throw e
            }
        } catch (e: Exception) {
            // JVM may throw javax.net.ssl.SSLException directly
            val className = e::class.simpleName?.lowercase() ?: ""
            if (className.contains("ssl") || className.contains("socket") || className.contains("io")) {
                // Expected
            } else {
                throw e
            }
        }
    }

    @Test
    fun tlsWithValidCertificate() = runTestNoTimeSkipping {
        // Connect to a well-known HTTPS site - should succeed
        try {
            ClientSocket.connect(
                port = 443,
                hostname = "www.google.com",
                tls = true,
                timeout = 10.seconds
            ) { socket ->
                assertTrue(socket.isOpen(), "Socket should be open after TLS handshake")
                // Send a simple HTTP request
                socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                val response = socket.readString(timeout = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "Should receive valid HTTP response")
            }
        } catch (e: UnsupportedOperationException) {
            // Skip on platforms that don't support TLS (browser)
            if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                throw e
            }
        }
    }

    @Test
    fun tlsConnectionReuse() = runTestNoTimeSkipping {
        // Test that we can make multiple TLS connections
        try {
            repeat(3) { i ->
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.google.com",
                    tls = true,
                    timeout = 10.seconds
                ) { socket ->
                    assertTrue(socket.isOpen(), "Socket $i should be open")
                    socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                    val response = socket.readString(timeout = 5.seconds)
                    assertTrue(response.startsWith("HTTP/"), "Should receive valid HTTP response for connection $i")
                }
            }
        } catch (e: UnsupportedOperationException) {
            // Skip on platforms that don't support TLS (browser)
            if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                throw e
            }
        }
    }

    @Test
    fun tlsWithSni() = runTestNoTimeSkipping {
        // Test Server Name Indication (SNI) by connecting to a site that requires it
        try {
            ClientSocket.connect(
                port = 443,
                hostname = "www.cloudflare.com",
                tls = true,
                timeout = 10.seconds
            ) { socket ->
                assertTrue(socket.isOpen(), "Socket should be open with SNI")
                socket.writeString("GET / HTTP/1.1\r\nHost: www.cloudflare.com\r\nConnection: close\r\n\r\n")
                val response = socket.readString(timeout = 5.seconds)
                assertTrue(response.contains("HTTP/"), "Should receive valid HTTP response with SNI")
            }
        } catch (e: UnsupportedOperationException) {
            // Skip on platforms that don't support TLS (browser)
            if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                throw e
            }
        }
    }

    @Test
    fun nonTlsConnectionToTlsPort() = runTestNoTimeSkipping {
        // Connect without TLS to an HTTPS port - should get garbage or connection closed
        try {
            ClientSocket.connect(
                port = 443,
                hostname = "www.google.com",
                tls = false,
                timeout = 10.seconds
            ) { socket ->
                // Send plain HTTP - server will likely close connection or send TLS alert
                socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                try {
                    val response = socket.readString(timeout = 5.seconds)
                    // If we get here, we got some response (probably TLS alert bytes)
                    // The response won't be valid HTTP
                    assertTrue(!response.startsWith("HTTP/1.1 200"), "Should not get valid HTTP from TLS port without TLS")
                } catch (e: SocketException) {
                    // Expected - server closed connection due to protocol mismatch
                }
            }
        } catch (e: UnsupportedOperationException) {
            // Skip on platforms that don't support raw sockets (browser)
            if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                throw e
            }
        } catch (e: SocketException) {
            // Expected - server may close immediately
        }
    }
}
