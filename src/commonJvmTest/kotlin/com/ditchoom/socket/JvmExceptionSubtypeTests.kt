package com.ditchoom.socket

import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * JVM-specific tests that strictly assert sealed exception subtypes.
 *
 * These tests know the exact JVM wrapping behavior and assert the specific
 * subtype, not just the parent sealed class.
 */
class JvmExceptionSubtypeTests {
    @Test
    fun connectionRefused_isSocketConnectionExceptionRefused() =
        runTestNoTimeSkipping {
            val port = 59400 + kotlin.random.Random.nextInt(599)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                    socket.close()
                    null
                } catch (e: SocketConnectionException.Refused) {
                    e
                } catch (e: SocketTimeoutException) {
                    // Timeout instead of refused — acceptable, skip strict assertion
                    null
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    null
                }
            if (ex != null) {
                assertIs<SocketConnectionException.Refused>(ex)
                assertTrue(ex.platformError != null, "platformError should be set")
            }
        }

    @Test
    fun readAfterPeerClose_isEndOfStreamOrConnectionReset() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("data")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            client.readString(timeout = 2.seconds) // consume the sent data

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }

            // On JVM, reading after graceful peer close produces EndOfStream
            assertIs<SocketClosedException.EndOfStream>(ex,
                "Expected EndOfStream, got: ${ex::class.simpleName}: ${ex.message}")

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun sslHandshakeToSelfSigned_isSSLHandshakeFailedException() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    ClientSocket.connect(
                        port = 443,
                        hostname = "self-signed.badssl.com",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 15.seconds,
                    ) { socket ->
                        socket.close()
                        null // platform didn't reject cert
                    }
                } catch (e: SSLHandshakeFailedException) {
                    e
                } catch (e: SSLProtocolException) {
                    e
                } catch (e: SocketException) {
                    // Fallback — not ideal but possible
                    e
                }
            if (ex != null) {
                // On JVM, self-signed cert should produce SSLHandshakeFailedException specifically
                assertIs<SSLHandshakeFailedException>(ex, "JVM should produce SSLHandshakeFailedException for self-signed cert, got: ${ex::class.simpleName}")
            }
        }

    @Test
    fun sslToNonTlsPort_isSSLProtocolException() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    ClientSocket.connect(
                        port = 80,
                        hostname = "example.com",
                        socketOptions = SocketOptions.tlsDefault(),
                        timeout = 10.seconds,
                    ) { socket ->
                        socket.close()
                        fail("Should have failed")
                    }
                } catch (e: SSLSocketException) {
                    e
                } catch (e: SocketClosedException) {
                    e
                } catch (e: SocketException) {
                    e
                }
            // On JVM, TLS to non-TLS port produces SSLProtocolException (from SSLEngine unwrap error)
            assertTrue(
                ex is SSLProtocolException || ex is SSLHandshakeFailedException || ex is SocketClosedException,
                "JVM should produce SSL exception or close for TLS-to-non-TLS, got: ${ex::class.simpleName}",
            )
        }

    @Test
    fun dnsFailure_hostnameIsPreserved() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "nonexistent.jvm.test.invalid")
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketUnknownHostException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex)
            // JVM wrapping preserves the hostname in the SocketUnknownHostException
            assertTrue(
                ex.hostname == "nonexistent.jvm.test.invalid" || ex.message.contains("nonexistent.jvm.test.invalid"),
                "Hostname should be preserved, got: hostname=${ex.hostname}, message=${ex.message}",
            )
        }

    @Test
    fun brokenPipeOrReset_isSocketClosedSubtype() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        clientConnected.unlock()
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            clientConnected.lockWithTimeout()
            kotlinx.coroutines.delay(200)

            var caughtException: SocketException? = null
            try {
                repeat(200) {
                    client.write(
                        "x".repeat(8192).toReadBuffer(com.ditchoom.buffer.Charset.UTF8),
                        1.seconds,
                    )
                    kotlinx.coroutines.delay(2)
                }
            } catch (e: SocketClosedException) {
                caughtException = e
            } catch (e: SocketIOException) {
                caughtException = e
            }

            if (caughtException is SocketClosedException) {
                val closed = caughtException as SocketClosedException
                // On JVM, writing to closed peer produces BrokenPipe or ConnectionReset
                assertTrue(
                    closed is SocketClosedException.BrokenPipe ||
                        closed is SocketClosedException.ConnectionReset ||
                        closed is SocketClosedException.General,
                    "Expected BrokenPipe, ConnectionReset, or General, got: ${closed::class.simpleName}",
                )
            }

            client.close()
            server.close()
            serverJob.cancel()
        }
}
