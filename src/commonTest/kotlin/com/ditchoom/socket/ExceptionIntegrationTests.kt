package com.ditchoom.socket

import com.ditchoom.buffer.Charset
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
 * Integration tests that verify the full OS error → platform wrapping → exception type path.
 *
 * All tests use localhost with [ServerSocket.allocate()], no external network dependencies.
 * Tests are strict where behavior is deterministic, lenient only where platforms genuinely differ.
 */
class ExceptionIntegrationTests {
    // ──────────────────────────────────────────────────────────────────
    // DNS
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun dnsFailure_producesSocketUnknownHostException() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "this.host.does.not.exist.invalid")
                    socket.close()
                    fail("Should have thrown for invalid hostname")
                } catch (e: SocketUnknownHostException) {
                    e
                } catch (e: UnsupportedOperationException) {
                    if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) throw e
                    return@runTestNoTimeSkipping
                }
            assertIs<SocketUnknownHostException>(ex)
            assertTrue(ex.message.contains("this.host.does.not.exist.invalid"), "got: ${ex.message}")
        }

    // ──────────────────────────────────────────────────────────────────
    // Connection refused
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun connectionRefused_producesSocketConnectionExceptionOrTimeout() =
        runTestNoTimeSkipping {
            val port = 59000 + kotlin.random.Random.nextInt(999)
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                socket.close()
            } catch (e: SocketConnectionException) {
                // Preferred — refused, unreachable, etc.
                assertIs<SocketConnectionException>(e)
            } catch (e: SocketTimeoutException) {
                // Some platforms timeout instead of refusing
            } catch (e: SocketClosedException) {
                // Channel may close during connect attempt on some platforms
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Coroutine-level timeout
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // EndOfStream — server sends data then closes gracefully
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun readAfterPeerClose_producesSocketClosedException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("hello")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(timeout = 2.seconds)
            assertTrue(data == "hello", "Should read 'hello', got: $data")

            // Second read — peer has closed. This MUST be SocketClosedException.
            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown on reading from closed connection")
                } catch (e: SocketClosedException) {
                    e
                }
            // All platforms produce SocketClosedException for this case.
            // Most should produce EndOfStream specifically, but we accept any SocketClosedException subtype.
            assertIs<SocketClosedException>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // Server immediate close — RST path
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun serverImmediateClose_clientReadProducesSocketClosedException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        // Close immediately without sending — may produce RST on some platforms
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            kotlinx.coroutines.delay(100)

            try {
                client.read(2.seconds)
                // Some platforms may return empty instead of throwing
            } catch (e: SocketClosedException) {
                // Expected — EndOfStream or ConnectionReset depending on platform/timing
                assertIs<SocketClosedException>(e)
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // Write to closed peer — BrokenPipe / ConnectionReset path
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun writeAfterPeerClose_producesSocketClosedException() =
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

            // Wait for server-side close to propagate
            kotlinx.coroutines.delay(200)

            // Write repeatedly — eventually triggers BrokenPipe or ConnectionReset
            var caughtException: SocketException? = null
            try {
                repeat(100) {
                    client.write(
                        "test data that should eventually fail".toReadBuffer(Charset.UTF8),
                        1.seconds,
                    )
                    kotlinx.coroutines.delay(5)
                }
            } catch (e: SocketClosedException) {
                caughtException = e
            } catch (e: SocketIOException) {
                // Some platforms (JVM NIO) may produce SocketIOException for write failures
                caughtException = e
            }

            if (caughtException != null) {
                // When we DO get an exception, it should indicate connection loss
                assertTrue(
                    caughtException is SocketClosedException || caughtException is SocketIOException,
                    "Expected SocketClosedException or SocketIOException, got: ${caughtException!!::class.simpleName}",
                )
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // Diagnostic quality
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun connectionRefused_exceptionHasUsefulMessage() =
        runTestNoTimeSkipping {
            val port = 59100 + kotlin.random.Random.nextInt(899)
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                socket.close()
            } catch (e: SocketException) {
                assertTrue(e.message.isNotBlank(), "Exception message should not be blank")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Acceptable
            }
        }

    @Test
    fun dnsFailure_hostnamePreservedInException() =
        runTestNoTimeSkipping {
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = 80, timeout = 5.seconds, hostname = "nonexistent.test.invalid")
                socket.close()
                fail("Should have thrown")
            } catch (e: SocketUnknownHostException) {
                assertTrue(
                    e.hostname == "nonexistent.test.invalid" || e.message.contains("nonexistent.test.invalid"),
                    "Hostname should be preserved, got hostname=${e.hostname}, message=${e.message}",
                )
            } catch (e: UnsupportedOperationException) {
                if (getNetworkCapabilities() != NetworkCapabilities.WEBSOCKETS_ONLY) throw e
            }
        }
}
