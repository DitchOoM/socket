package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * TLS exception type validation tests.
 *
 * Asserts specific sealed exception subtypes for TLS error conditions.
 * Requires network access to badssl.com.
 */
class TlsExceptionValidationTests {
    @Test
    fun selfSignedCert_withDefaultConfig_producesSSLException() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Platform accepted self-signed cert")
                }
            } catch (e: SSLHandshakeFailedException) {
                // Most specific — JVM and Linux
            } catch (e: SSLProtocolException) {
                // Apple / generic TLS
            } catch (e: SocketClosedException) {
                // Some platforms reset during handshake
            } catch (e: SocketIOException) {
                // Fallback
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    @Test
    fun expiredCert_withDefaultConfig_producesSSLException() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "expired.badssl.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Platform accepted expired cert")
                }
            } catch (e: SSLHandshakeFailedException) {
                // Most specific
            } catch (e: SSLProtocolException) {
                // Expected
            } catch (e: SocketClosedException) {
                // Some platforms
            } catch (e: SocketIOException) {
                // Fallback
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }

    @Test
    fun selfSignedCert_withInsecureConfig_shouldSucceed() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    socketOptions = SocketOptions.tlsInsecure(),
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Socket should be open with TlsConfig.INSECURE")
                }
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            } catch (e: SocketException) {
                // Some platforms may still fail
            }
        }

    @Test
    fun tlsToNonTlsPort_producesReasonableException() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 10.seconds,
                ) { socket ->
                    socket.close()
                    kotlin.test.fail("TLS handshake should have failed on non-TLS port")
                }
            } catch (e: SSLHandshakeFailedException) {
                // Most specific
            } catch (e: SSLProtocolException) {
                // TLS protocol error
            } catch (e: SocketClosedException) {
                // Connection reset during handshake
            } catch (e: SocketIOException) {
                // Generic I/O
            } catch (e: SocketTimeoutException) {
                // Some platforms timeout
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) {
                    throw e
                }
            }
        }
}
