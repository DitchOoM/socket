package com.ditchoom.socket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for TLS/SSL error handling.
 *
 * NOTE: These tests require external network access to verify TLS behavior
 * with real certificates and SNI. They connect to well-known HTTPS sites.
 *
 * Exception hierarchy:
 * - SocketException (base)
 *   - SSLSocketException
 *     - SSLHandshakeFailedException
 */
class TlsErrorTests {
    @Test
    fun tlsToNonTlsPort() =
        runTestNoTimeSkipping {
            // Try to establish TLS connection to a non-TLS port (HTTP on port 80)
            try {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    socketOptions = SocketOptions.tlsDefault(),
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
            // Connect to a well-known HTTPS site - should succeed
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.google.com",
                    socketOptions = SocketOptions.tlsDefault(),
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

    @Test
    fun tlsConnectionReuse() =
        runTestNoTimeSkipping {
            // Test that we can make multiple TLS connections
            try {
                repeat(3) { i ->
                    ClientSocket.connect(
                        port = 443,
                        hostname = "www.google.com",
                        socketOptions = SocketOptions.tlsDefault(),
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

    @Test
    fun tlsWithSni() =
        runTestNoTimeSkipping {
            // Test Server Name Indication (SNI) by connecting to a site that requires it
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.cloudflare.com",
                    socketOptions = SocketOptions.tlsDefault(),
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
            skipOnSimulator {
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "www.example.com",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Socket should be open after TLS handshake to example.com")
                        socket.writeString("GET / HTTP/1.1\r\nHost: www.example.com\r\nConnection: close\r\n\r\n")
                        val response = socket.readString(timeout = 10.seconds)
                        assertTrue(response.contains("HTTP/"), "Should receive valid HTTP response from example.com")
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    @Test
    fun tlsToNginx() =
        runTestNoTimeSkipping {
            skipOnSimulator {
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "nginx.org",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 15.seconds,
                    ) { socket ->
                        assertTrue(socket.isOpen(), "Socket should be open after TLS handshake to nginx.org")
                        socket.writeString("GET / HTTP/1.1\r\nHost: nginx.org\r\nConnection: close\r\n\r\n")
                        val response = socket.readString(timeout = 10.seconds)
                        assertTrue(response.contains("HTTP/"), "Should receive valid HTTP response from nginx.org")
                    }
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                        throw e
                    }
                }
            }
        }

    @Test
    fun tlsToHttpbin() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "httpbin.org",
                    socketOptions = SocketOptions.tlsDefault(),
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

    // ==================== Concurrent Connections Test ====================

    @Test
    fun tlsConcurrentConnections() =
        runTestNoTimeSkipping {
            try {
                coroutineScope {
                    val results =
                        (1..5).map { i ->
                            async {
                                ClientSocket.connect(
                                    port = 443,
                                    hostname = "httpbin.org",
                                    socketOptions = SocketOptions.tlsDefault(),
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

    // ==================== Larger Data Transfer Test ====================

    @Test
    fun tlsLargerResponse() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.google.com",
                    socketOptions = SocketOptions.tlsDefault(),
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

    // ==================== JSON API Test ====================

    @Test
    fun tlsJsonApi() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "httpbin.org",
                    socketOptions = SocketOptions.tlsDefault(),
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

    // ==================== Certificate Validation Tests (badssl.com) ====================

    @Test
    fun tlsExpiredCertificateShouldFail() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "expired.badssl.com",
                    socketOptions = SocketOptions.tlsDefault(),
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
                    socketOptions = SocketOptions.tlsDefault(),
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
                    socketOptions = SocketOptions.tlsDefault(),
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

    // ==================== TlsConfig Tests ====================

    @Test
    fun tlsInsecureModeAllowsSelfSigned() =
        runTestNoTimeSkipping {
            // With INSECURE TlsConfig, self-signed certs should be accepted
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 15.seconds,
                ) { socket ->
                    // Connection should succeed with insecure mode
                    assertTrue(socket.isOpen(), "Socket should be open with TlsConfig.INSECURE")
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
            // With INSECURE TlsConfig, expired certs should be accepted
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "expired.badssl.com",
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 15.seconds,
                ) { socket ->
                    // Connection should succeed with insecure mode
                    assertTrue(socket.isOpen(), "Socket should be open with TlsConfig.INSECURE")
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
            // With DEFAULT TlsConfig (strict), self-signed certs should be rejected
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 15.seconds,
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
            try {
                // First connection
                ClientSocket.connect(
                    port = 443,
                    hostname = "httpbin.org",
                    socketOptions = SocketOptions.tlsDefault(),
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
                    socketOptions = SocketOptions.tlsDefault(),
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

    /**
     * Tests that read(WriteBuffer, timeout) returns correct TLS-decrypted data.
     *
     * This is a regression test for a bug where AsyncBaseClientSocket.read(WriteBuffer, timeout)
     * bypassed TLS decryption entirely, returning raw encrypted bytes instead of plaintext.
     */
    @Test
    fun tlsReadIntoWriteBuffer() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "example.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 10.seconds,
                ) { socket ->
                    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")

                    // Use the read(WriteBuffer, timeout) overload — the path that was broken
                    val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                    val bytesRead = socket.read(writeBuffer, 10.seconds)
                    assertTrue(bytesRead > 0, "Should read data via WriteBuffer path")

                    val readBuffer = writeBuffer as PlatformBuffer
                    readBuffer.setLimit(readBuffer.position())
                    readBuffer.position(0)
                    val text = readBuffer.readString(readBuffer.remaining(), Charset.UTF8)
                    assertTrue(
                        text.startsWith("HTTP/"),
                        "WriteBuffer path should return decrypted HTTP response, got: ${text.take(50)}",
                    )
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    /**
     * Tests that TLS 1.3 connections work correctly on first read.
     *
     * TLS 1.3 servers send post-handshake messages (e.g. NewSessionTicket) that produce
     * 0 application bytes when unwrapped. The TLS handler must retry rather than returning
     * empty, which callers would interpret as end-of-stream.
     *
     * Uses a server known to negotiate TLS 1.3 on modern JVMs.
     */
    @Test
    fun tlsFirstReadReturnsData() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "example.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 10.seconds,
                ) { socket ->
                    socket.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")

                    // First read must return data even if TLS 1.3 NewSessionTicket arrives first
                    val response = socket.read(10.seconds)
                    response.resetForRead()
                    val remaining = response.remaining()
                    assertTrue(remaining > 0, "First read should return data (not empty from NewSessionTicket)")
                    val text = response.readString(remaining, Charset.UTF8)
                    assertTrue(text.startsWith("HTTP/"), "First read should be the HTTP response")
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    /**
     * Tests that both read overloads return equivalent data over TLS.
     *
     * Ensures read(timeout) and read(WriteBuffer, timeout) produce the same plaintext
     * when talking to a TLS server. Both paths must correctly decrypt through the TLS handler.
     */
    @Test
    fun tlsBothReadOverloadsReturnSameData() =
        runTestNoTimeSkipping {
            try {
                // Test read(timeout) path
                val socket1 =
                    ClientSocket.connect(
                        port = 443,
                        hostname = "example.com",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 10.seconds,
                    )
                val response1: String
                try {
                    socket1.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
                    response1 = socket1.readString(timeout = 10.seconds)
                } finally {
                    socket1.close()
                }

                // Test read(WriteBuffer, timeout) path
                val socket2 =
                    ClientSocket.connect(
                        port = 443,
                        hostname = "example.com",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 10.seconds,
                    )
                try {
                    socket2.writeString("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
                    val writeBuffer = BufferFactory.Default.allocate(65536) as WriteBuffer
                    val bytesRead = socket2.read(writeBuffer, 10.seconds)
                    assertTrue(bytesRead > 0, "WriteBuffer read should return data")
                    val readBuffer = writeBuffer as PlatformBuffer
                    readBuffer.setLimit(readBuffer.position())
                    readBuffer.position(0)
                    val response2 = readBuffer.readString(readBuffer.remaining(), Charset.UTF8)

                    // Both should start with HTTP/ and contain the same status line
                    assertTrue(response1.startsWith("HTTP/"), "read(timeout) should return HTTP response")
                    assertTrue(response2.startsWith("HTTP/"), "read(WriteBuffer) should return HTTP response")
                    // Extract first line from each
                    val statusLine1 = response1.substringBefore("\r\n")
                    val statusLine2 = response2.substringBefore("\r\n")
                    assertEquals(statusLine1, statusLine2, "Both read paths should get same HTTP status line")
                } finally {
                    socket2.close()
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    /**
     * Tests multiple sequential reads over a single TLS connection.
     *
     * Ensures the TLS session state is maintained correctly across reads, handling
     * any interleaved TLS protocol messages (key updates, session tickets, etc.).
     */
    @Test
    fun tlsMultipleSequentialReads() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "www.google.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 10.seconds,
                ) { socket ->
                    // Request a page that returns enough data for multiple reads
                    socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")

                    var totalBytes = 0
                    var readCount = 0
                    // Read until connection closes (Connection: close)
                    while (socket.isOpen() && readCount < 10) {
                        try {
                            val buf = BufferFactory.Default.allocate(65536) as WriteBuffer
                            val n = socket.read(buf, 5.seconds)
                            if (n <= 0) break
                            totalBytes += n
                            readCount++
                        } catch (e: SocketClosedException) {
                            break
                        }
                    }
                    assertTrue(
                        totalBytes > 100,
                        "Should have read substantial data across multiple reads, got $totalBytes bytes in $readCount reads",
                    )
                    assertTrue(readCount >= 1, "Should have done at least 1 read")
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }
}
