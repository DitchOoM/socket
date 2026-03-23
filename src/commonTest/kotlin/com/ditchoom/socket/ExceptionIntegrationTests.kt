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
 * Each test catches exactly ONE sealed exception type. If a platform produces a different
 * type, that's a mapping bug — the test should fail, not silently pass.
 */
class ExceptionIntegrationTests {
    // ──────────────────────────────────────────────────────────────────
    // DNS
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun dnsFailure_producesSocketUnknownHostException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "this.host.does.not.exist.invalid")
                    socket.close()
                    fail("Should have thrown for invalid hostname")
                } catch (e: SocketException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex, "Expected SocketUnknownHostException but got ${ex::class.simpleName}: ${ex.message}")
        }

    // ──────────────────────────────────────────────────────────────────
    // Connection refused
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun connectionRefused_producesSocketConnectionException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val port = 59000 + kotlin.random.Random.nextInt(999)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                    socket.close()
                    fail("Should have thrown for connection refused")
                } catch (e: SocketConnectionException) {
                    e
                }
            assertIs<SocketConnectionException>(ex)
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

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown on reading from closed connection")
                } catch (e: SocketClosedException) {
                    e
                }
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
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            kotlinx.coroutines.delay(100)

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown on reading from closed connection")
                } catch (e: SocketClosedException) {
                    e
                }
            assertIs<SocketClosedException>(ex)

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

            kotlinx.coroutines.delay(200)

            // Write enough data to overflow the kernel send buffer and trigger the error.
            // Kernel send buffer is typically ~128 KB; 100 × 8 KB = 800 KB guarantees overflow.
            val ex =
                try {
                    repeat(100) {
                        client.write(
                            "x".repeat(8192).toReadBuffer(Charset.UTF8),
                            1.seconds,
                        )
                        kotlinx.coroutines.delay(5)
                    }
                    fail("Should have thrown when writing to closed connection")
                } catch (e: SocketClosedException) {
                    e
                }
            assertIs<SocketClosedException>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // TLS — handshake failure (local, no external network)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun tlsToNonTlsServer_producesSSLSocketException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        // Send non-TLS data — client expects ServerHello, gets garbage → immediate TLS error
                        serverClient.writeString("NOT A TLS RESPONSE\r\n")
                        kotlinx.coroutines.delay(200)
                        serverClient.close()
                    }
                }

            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(
                        port = server.port(),
                        timeout = 5.seconds,
                        hostname = "127.0.0.1",
                        socketOptions = SocketOptions.tlsDefault(),
                    )
                    socket.close()
                    fail("TLS handshake should have failed on non-TLS server")
                } catch (e: SSLSocketException) {
                    e
                }
            assertIs<SSLSocketException>(ex)

            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // Diagnostic quality
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun connectionRefused_exceptionHasUsefulMessage() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val port = 59100 + kotlin.random.Random.nextInt(899)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketConnectionException) {
                    e
                }
            assertTrue(ex.message.isNotBlank(), "Exception message should not be blank")
        }

    @Test
    fun dnsFailure_hostnamePreservedInException() =
        runTestNoTimeSkipping {
            if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = 80, timeout = 5.seconds, hostname = "nonexistent.test.invalid")
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex, "Expected SocketUnknownHostException but got ${ex::class.simpleName}: ${ex.message}")
            assertTrue(
                ex.hostname == "nonexistent.test.invalid" || ex.message.contains("nonexistent.test.invalid"),
                "Hostname should be preserved, got hostname=${ex.hostname}, message=${ex.message}",
            )
        }
}
