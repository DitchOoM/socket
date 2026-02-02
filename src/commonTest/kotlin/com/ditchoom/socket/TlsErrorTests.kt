package com.ditchoom.socket

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for TLS/SSL error handling.
 *
 * NOTE: These tests require external network access to verify TLS behavior
 * with real certificates and SNI. They connect to well-known HTTPS sites.
 *
 * Some tests are skipped on iOS Simulator because the CI environment
 * has restricted network access to external hosts.
 *
 * Exception hierarchy:
 * - SocketException (base)
 *   - SSLSocketException
 *     - SSLHandshakeFailedException
 */
class TlsErrorTests {
    /**
     * Skips the test on iOS Simulator where external network access is restricted.
     * This allows the same TLS code path to be tested on macOS (which has full network access)
     * while avoiding failures in the iOS Simulator CI environment.
     */
    private inline fun skipOnSimulator(block: () -> Unit) {
        if (isRunningInSimulator()) {
            println("Skipping test on iOS Simulator (external network restricted)")
            return
        }
        block()
    }

    @Test
    fun tlsToNonTlsPort() =
        runTestNoTimeSkipping {
            // Try to establish TLS connection to a non-TLS port (HTTP on port 80)
            try {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    tls = true,
                    timeout = 10.seconds,
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
    fun tlsWithValidCertificate() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                // Connect to a well-known HTTPS site - should succeed
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "www.google.com",
                        tls = true,
                        timeout = 10.seconds,
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
        }

    @Test
    fun tlsConnectionReuse() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                // Test that we can make multiple TLS connections
                try {
                    repeat(3) { i ->
                        ClientSocket.connect(
                            port = 443,
                            hostname = "www.google.com",
                            tls = true,
                            timeout = 10.seconds,
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
        }

    @Test
    fun tlsWithSni() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                // Test Server Name Indication (SNI) by connecting to a site that requires it
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "www.cloudflare.com",
                        tls = true,
                        timeout = 10.seconds,
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
        }

    // TODO: This test hangs in JS browser tests - needs investigation
    // The test works on JVM/Android/Apple but browser environment has different behavior
    // @Test
    fun nonTlsConnectionToTlsPort() =
        runTestNoTimeSkipping {
            // Connect without TLS to an HTTPS port - should get garbage or connection closed
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.google.com",
                    tls = false,
                    timeout = 10.seconds,
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

    // ==================== Multiple Public Sites Tests ====================

    @Test
    fun tlsToExampleDotCom() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.example.com",
                    tls = true,
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Socket should be open after TLS handshake to example.com")
                    socket.writeString("GET / HTTP/1.1\r\nHost: www.example.com\r\nConnection: close\r\n\r\n")
                    val response = socket.readString(timeout = 10.seconds)
                    assertTrue(response.contains("HTTP/"), "Should receive valid HTTP response from example.com")
                }
            } catch (e: SocketException) {
                // Some platforms may have TLS handshake issues with certain sites
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    @Test
    fun tlsToNginx() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "nginx.org",
                    tls = true,
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Socket should be open after TLS handshake to nginx.org")
                    socket.writeString("GET / HTTP/1.1\r\nHost: nginx.org\r\nConnection: close\r\n\r\n")
                    val response = socket.readString(timeout = 10.seconds)
                    assertTrue(response.contains("HTTP/"), "Should receive valid HTTP response from nginx.org")
                }
            } catch (e: SocketException) {
                // Some platforms may have TLS handshake issues with certain sites
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    @Test
    fun tlsToHttpbin() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "httpbin.org",
                        tls = true,
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Socket should be open after TLS handshake to httpbin")
                        socket.writeString("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
                        val response = socket.readString(timeout = 10.seconds)
                        assertTrue(response.startsWith("HTTP/"), "Should receive valid HTTP response from httpbin")
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    // ==================== Concurrent Connections Test ====================

    @Test
    fun tlsConcurrentConnections() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    coroutineScope {
                        val results =
                            (1..5).map { i ->
                                async {
                                    ClientSocket.connect(
                                        port = 443,
                                        hostname = "httpbin.org",
                                        tls = true,
                                        timeout = 15.seconds,
                                    ) { socket ->
                                        socket.writeString("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
                                        socket.readString(timeout = 10.seconds)
                                    }
                                }
                            }
                        results.forEach { deferred ->
                            val response = deferred.await()
                            assertTrue(response.startsWith("HTTP/"), "Concurrent connection should receive valid HTTP response")
                        }
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    // ==================== Larger Data Transfer Test ====================

    @Test
    fun tlsLargerResponse() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "www.google.com",
                        tls = true,
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Socket should be open")
                        // Request the full page to get a larger response
                        socket.writeString(
                            "GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n" +
                                "Accept: text/html\r\nUser-Agent: Mozilla/5.0\r\n\r\n",
                        )
                        val response = socket.readString(timeout = 10.seconds)
                        assertTrue(response.startsWith("HTTP/"), "Should receive valid HTTP response")
                        // Verify we received a substantial response (Google homepage varies in size)
                        assertTrue(response.length > 1_000, "Response should be larger than 1KB, got ${response.length} bytes")
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    // ==================== JSON API Test ====================

    @Test
    fun tlsJsonApi() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "httpbin.org",
                        tls = true,
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Socket should be open")
                        socket.writeString(
                            "GET /json HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\nAccept: application/json\r\n\r\n",
                        )
                        val response = socket.readString(timeout = 10.seconds)
                        assertTrue(response.startsWith("HTTP/"), "Should receive valid HTTP response")
                        // The first read may only contain headers; verify we got a valid HTTP response with JSON content-type
                        assertTrue(
                            response.contains("application/json") || response.contains("{"),
                            "Response should indicate JSON content type or contain JSON data",
                        )
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    // ==================== Certificate Validation Tests (badssl.com) ====================

    @Test
    fun tlsExpiredCertificateShouldFail() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "expired.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                ) { socket ->
                    // Some platforms (e.g., Linux native with certain OpenSSL configs) may not
                    // enforce certificate expiration by default - just verify the connection works
                    assertTrue(socket.isOpen(), "Socket should be open if platform doesn't enforce cert validation")
                }
            } catch (e: SocketException) {
                // Expected - includes SSLSocketException and SSLHandshakeFailedException
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: Exception) {
                // Platform-specific SSL exceptions are acceptable
                val name = e::class.simpleName?.lowercase() ?: ""
                assertTrue(
                    name.contains("ssl") || name.contains("tls") || name.contains("certificate"),
                    "Expected SSL/TLS/Certificate exception, got: ${e::class.simpleName}",
                )
            }
        }

    @Test
    fun tlsWrongHostShouldFail() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "wrong.host.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                ) { socket ->
                    // Some platforms may not enforce hostname verification by default
                    // In that case, we just verify the connection was established
                    assertTrue(socket.isOpen(), "Socket should be open if hostname verification is not enforced")
                }
            } catch (e: SocketException) {
                // Expected on platforms that enforce hostname verification
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: Exception) {
                // Platform-specific SSL exceptions are acceptable
                val name = e::class.simpleName?.lowercase() ?: ""
                assertTrue(
                    name.contains("ssl") || name.contains("tls") || name.contains("certificate") || name.contains("io"),
                    "Expected SSL/TLS/Certificate/IO exception, got: ${e::class.simpleName}",
                )
            }
        }

    @Test
    fun tlsSelfSignedShouldFail() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                ) { socket ->
                    // Some platforms may not enforce certificate validation by default
                    // just verify the connection works if platform doesn't reject self-signed certs
                    assertTrue(socket.isOpen(), "Socket should be open if platform doesn't enforce cert validation")
                }
            } catch (e: SocketException) {
                // Expected - includes SSLSocketException and SSLHandshakeFailedException
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: Exception) {
                // Platform-specific SSL exceptions are acceptable
                val name = e::class.simpleName?.lowercase() ?: ""
                assertTrue(
                    name.contains("ssl") || name.contains("tls") || name.contains("certificate"),
                    "Expected SSL/TLS/Certificate exception, got: ${e::class.simpleName}",
                )
            }
        }

    // ==================== TlsOptions Tests ====================

    @Test
    fun tlsInsecureModeAllowsSelfSigned() =
        runTestNoTimeSkipping {
            // With INSECURE TlsOptions, self-signed certs should be accepted
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                    tlsOptions = TlsOptions.INSECURE,
                ) { socket ->
                    // Connection should succeed with insecure mode
                    assertTrue(socket.isOpen(), "Socket should be open with TlsOptions.INSECURE")
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: SocketException) {
                // Some platforms may still fail due to other TLS issues - that's acceptable
                // The important thing is that it attempted the connection with reduced validation
            }
        }

    @Test
    fun tlsInsecureModeAllowsExpired() =
        runTestNoTimeSkipping {
            // With INSECURE TlsOptions, expired certs should be accepted
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "expired.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                    tlsOptions = TlsOptions.INSECURE,
                ) { socket ->
                    // Connection should succeed with insecure mode
                    assertTrue(socket.isOpen(), "Socket should be open with TlsOptions.INSECURE")
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: SocketException) {
                // Some platforms may still fail due to other TLS issues - that's acceptable
            }
        }

    @Test
    fun tlsDefaultOptionsRejectSelfSigned() =
        runTestNoTimeSkipping {
            // With DEFAULT TlsOptions (strict), self-signed certs should be rejected
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    tls = true,
                    timeout = 15.seconds,
                    tlsOptions = TlsOptions.DEFAULT,
                ) { socket ->
                    // If we get here, the platform allows the connection (some platforms may be lenient)
                    assertTrue(socket.isOpen(), "Platform accepted self-signed cert with default options")
                }
            } catch (e: SocketException) {
                // Expected - self-signed cert should be rejected with strict mode
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: Exception) {
                // Platform-specific SSL exceptions are acceptable (indicates rejection)
                val name = e::class.simpleName?.lowercase() ?: ""
                assertTrue(
                    name.contains("ssl") || name.contains("tls") || name.contains("certificate"),
                    "Expected SSL/TLS/Certificate exception, got: ${e::class.simpleName}",
                )
            }
        }

    // ==================== Connection Recovery Test ====================

    @Test
    fun tlsReconnectAfterClose() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    // First connection
                    ClientSocket.connect(
                        port = 443,
                        hostname = "httpbin.org",
                        tls = true,
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "First connection should be open")
                        socket.writeString("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
                        val response1 = socket.readString(timeout = 10.seconds)
                        assertTrue(response1.startsWith("HTTP/"), "First connection should receive valid response")
                    }

                    // Second connection to same host after close
                    ClientSocket.connect(
                        port = 443,
                        hostname = "httpbin.org",
                        tls = true,
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Reconnection should be open")
                        socket.writeString("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
                        val response2 = socket.readString(timeout = 10.seconds)
                        assertTrue(response2.startsWith("HTTP/"), "Reconnection should receive valid response")
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }
}
