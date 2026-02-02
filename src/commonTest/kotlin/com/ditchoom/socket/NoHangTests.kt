package com.ditchoom.socket

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Tests to ensure socket operations don't hang indefinitely.
 *
 * These tests verify that all socket operations properly respect timeouts
 * and can be cancelled. A hanging operation would cause these tests to fail
 * due to the strict timeout enforcement.
 *
 * Each test has a maximum duration enforced by both:
 * 1. The operation's own timeout parameter
 * 2. A wrapper withTimeout to catch any operation that ignores its timeout
 */
class NoHangTests {
    // Strict timeout for the entire test - if any operation hangs, this will catch it
    private val testTimeout = 15.seconds

    // Timeout for individual socket operations
    private val operationTimeout = 5.seconds

    @Test
    fun connectDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            // Connect to a valid host - should complete quickly
            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    timeout = operationTimeout,
                ) { socket ->
                    assertTrue(socket.isOpen(), "Socket should be open")
                }
            }
        }

    @Test
    fun connectToNonExistentHostDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            // Connect to a non-routable IP - should timeout, not hang
            withTimeout(testTimeout) {
                try {
                    ClientSocket.connect(
                        port = 80,
                        hostname = "10.255.255.1", // Non-routable IP
                        timeout = operationTimeout,
                    ) { socket ->
                        fail("Should not connect to non-routable IP")
                    }
                } catch (e: SocketException) {
                    // Expected - connection failed or timed out
                } catch (e: TimeoutCancellationException) {
                    // Also acceptable - the withTimeout caught it
                }
            }
        }

    @Test
    fun connectToRefusedPortDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            // Connect to a port that should refuse connections
            withTimeout(testTimeout) {
                try {
                    ClientSocket.connect(
                        port = 1, // Privileged port, likely not listening
                        hostname = "127.0.0.1",
                        timeout = operationTimeout,
                    ) { socket ->
                        fail("Should not connect to refused port")
                    }
                } catch (e: SocketException) {
                    // Expected - connection refused
                } catch (e: Exception) {
                    // Platform-specific exceptions (e.g., java.net.ConnectException) are also acceptable
                    val name = e::class.simpleName?.lowercase() ?: ""
                    if (!name.contains("connect") && !name.contains("socket") && !name.contains("io")) {
                        throw e
                    }
                }
            }
        }

    @Test
    fun readWithTimeoutDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    timeout = operationTimeout,
                ) { socket ->
                    // Don't send anything - server won't respond
                    // Read should timeout, not hang
                    try {
                        socket.read(timeout = 2.seconds)
                        // If we get here, the server sent something (unusual but possible)
                    } catch (e: SocketException) {
                        // Expected - read timed out or connection closed
                    } catch (e: TimeoutCancellationException) {
                        // Also acceptable
                    }
                }
            }
        }

    @Test
    fun writeDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    timeout = operationTimeout,
                ) { socket ->
                    // Write should complete quickly
                    socket.writeString(
                        "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",
                        timeout = operationTimeout,
                    )
                }
            }
        }

    @Test
    fun closeDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                val socket =
                    ClientSocket.connect(
                        port = 80,
                        hostname = "example.com",
                        timeout = operationTimeout,
                    )
                // Close should complete quickly
                socket.close()
            }
        }

    @Test
    fun tlsConnectDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 443,
                    hostname = "example.com",
                    tls = true,
                    timeout = operationTimeout,
                ) { socket ->
                    assertTrue(socket.isOpen(), "TLS socket should be open")
                }
            }
        }

    @Test
    fun tlsHandshakeFailureDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            // Try TLS on a non-TLS port - handshake should fail, not hang
            withTimeout(testTimeout) {
                try {
                    ClientSocket.connect(
                        port = 80, // HTTP port, not HTTPS
                        hostname = "example.com",
                        tls = true,
                        timeout = operationTimeout,
                    ) { socket ->
                        fail("TLS handshake should have failed on non-TLS port")
                    }
                } catch (e: SocketException) {
                    // Expected - handshake failed
                } catch (e: Exception) {
                    // Platform-specific SSL exceptions are also acceptable
                    val name = e::class.simpleName?.lowercase() ?: ""
                    if (!name.contains("ssl") && !name.contains("socket") && !name.contains("io")) {
                        throw e
                    }
                }
            }
        }

    @Test
    fun tlsReadDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 443,
                    hostname = "example.com",
                    tls = true,
                    timeout = operationTimeout,
                ) { socket ->
                    // Send request
                    socket.writeString(
                        "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",
                        timeout = operationTimeout,
                    )
                    // Read response - should not hang
                    val response = socket.readString(timeout = operationTimeout)
                    assertTrue(response.contains("HTTP/"), "Should receive HTTP response")
                }
            }
        }

    @Test
    fun tlsWriteDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 443,
                    hostname = "example.com",
                    tls = true,
                    timeout = operationTimeout,
                ) { socket ->
                    // Write should complete quickly
                    socket.writeString(
                        "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",
                        timeout = operationTimeout,
                    )
                }
            }
        }

    @Test
    fun multipleSequentialConnectionsDoNotHang() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(30.seconds) {
                repeat(3) { i ->
                    ClientSocket.connect(
                        port = 80,
                        hostname = "example.com",
                        timeout = operationTimeout,
                    ) { socket ->
                        socket.writeString(
                            "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",
                            timeout = operationTimeout,
                        )
                        val response = socket.readString(timeout = operationTimeout)
                        assertTrue(response.contains("HTTP/"), "Connection $i should receive response")
                    }
                }
            }
        }

    @Test
    fun readAfterServerClosesDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            withTimeout(testTimeout) {
                ClientSocket.connect(
                    port = 80,
                    hostname = "example.com",
                    timeout = operationTimeout,
                ) { socket ->
                    // Send request with Connection: close
                    socket.writeString(
                        "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n",
                        timeout = operationTimeout,
                    )
                    // Read the response
                    socket.readString(timeout = operationTimeout)
                    // Try to read again after server closes - should not hang
                    try {
                        socket.read(timeout = 2.seconds)
                    } catch (e: SocketException) {
                        // Expected - socket closed
                    } catch (e: SocketClosedException) {
                        // Expected - socket closed
                    }
                }
            }
        }

    @Test
    fun cancelledCoroutineDoesNotHang() =
        runTestNoTimeSkipping(timeout = testTimeout) {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping

            // This test verifies that when a coroutine is cancelled,
            // the socket operations don't hang
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2.seconds) {
                    ClientSocket.connect(
                        port = 80,
                        hostname = "example.com",
                        timeout = 30.seconds, // Long timeout that we'll cancel
                    ) { socket ->
                        // Don't send anything, just wait for read that won't come
                        // The withTimeout should cancel this
                        socket.read(timeout = 30.seconds)
                    }
                }
            }
        }
}
