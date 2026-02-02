package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Linux-specific TLS tests.
 */
class LinuxTlsTests {
    @Test
    fun tlsConnectionRequiresCACertificates() =
        runTestNoTimeSkipping {
            // Verify that TLS connections work (implicitly tests CA loading succeeds)
            // If CA loading threw an exception, this test would fail
            ClientSocket.connect(
                port = 443,
                hostname = "www.google.com",
                tls = true,
                timeout = 10.seconds,
            ) { socket ->
                assertTrue(socket.isOpen(), "TLS socket should be open")
                socket.writeString("GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                val response = socket.readString(timeout = 5.seconds)
                assertTrue(response.startsWith("HTTP/"), "Should receive HTTP response over TLS")
            }
        }

    @Test
    fun sslSocketExceptionContainsUsefulMessage() {
        // Verify SSLSocketException can be constructed with descriptive message
        val exception = SSLSocketException("Failed to load CA certificates")
        val msg = exception.message
        assertTrue(msg != null && msg.contains("CA certificates"))
    }
}
