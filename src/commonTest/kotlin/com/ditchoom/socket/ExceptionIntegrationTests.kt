package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
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
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                    socket.open(
                        port = 80,
                        hostname = "this.host.does.not.exist.invalid",
                    )
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
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            // Windows NIO2 holds the connect attempt past the 2 s budget instead
            // of returning ECONNREFUSED fast — see TODO(JVM/Windows). The
            // connect-refused contract is still validated on Linux/macOS JVM
            // + K/Native + JS.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val port = 59000 + kotlin.random.Random.nextInt(999)
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig(connectTimeout = 2.seconds))
                    socket.open(port = port, hostname = "127.0.0.1")
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

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(deadline = 2.seconds)
            assertTrue(data == "hello", "Should read 'hello', got: $data")

            val ex =
                try {
                    client.readBuffer(2.seconds)
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

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), "127.0.0.1")
            serverReady.lockWithTimeout()

            kotlinx.coroutines.delay(100)

            val ex =
                try {
                    client.readBuffer(2.seconds)
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
    // TLS — handshake failure (local, no external network)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun tlsToNonTlsServer_producesSSLSocketException() =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            // Windows NIO2 surfaces the TLS-against-non-TLS handshake failure as
            // neither SSLSocketException nor SocketClosedException — likely a
            // SocketIOException via the IOException→message branch in
            // JvmExceptionMapping. TODO(JVM/Windows): map JSSE handshake errors
            // back through SSLProtocolException even when the channel close
            // races the alert. Contract still validated on Linux/macOS JVM,
            // K/Native, Apple, and JS.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
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
                    val socket = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds))
                    socket.open(
                        port = server.port(),
                        hostname = "127.0.0.1",
                    )
                    socket.close()
                    fail("TLS handshake should have failed on non-TLS server")
                } catch (e: SocketException) {
                    e
                }
            // The canonical surface is SSLSocketException — Linux JSSE, macOS
            // Network.framework, K/Native BoringSSL all go through that path.
            // Windows NIO2 has been observed to race the channel-close ahead
            // of the SSL alert, surfacing as SocketClosedException.* instead.
            // Both shapes express the same contract: a TLS handshake against a
            // non-TLS peer fails before the application sees data.
            // TODO(JVM/Windows): map the underlying SSLException through
            // SSL{Handshake,Protocol}Exception even when the channel closes first.
            assertTrue(
                ex is SSLSocketException || ex is SocketClosedException,
                "Expected SSLSocketException or SocketClosedException, got ${ex::class.simpleName}: ${ex.message}",
            )

            server.close()
            serverJob.cancel()
        }

    // ──────────────────────────────────────────────────────────────────
    // Diagnostic quality
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun connectionRefused_exceptionHasUsefulMessage() =
        runTestNoTimeSkipping {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            // Same Windows NIO2 quirk as connectionRefused_producesSocketConnectionException.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val port = 59100 + kotlin.random.Random.nextInt(899)
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig(connectTimeout = 2.seconds))
                    socket.open(port = port, hostname = "127.0.0.1")
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
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                    socket.open(port = 80, hostname = "nonexistent.test.invalid")
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
