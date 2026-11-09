package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import com.ditchoom.socket.testkit.scaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    /**
     * The OS resolver's own answer for a name that does not exist, through the platform connect path.
     * (Our mapping of a resolver's "no address" answer is covered without the network by
     * HostResolverTests.aResolverThatAnswersNoAddressIsAnUnknownHost.)
     *
     * The connect deadline is the resolver's whole budget here, since no address is ever reached. It
     * was 5 s, no longer than one attempt of a stub resolver with the common 5 s per-attempt timeout, so
     * a runner with a slow resolver timed out before the resolver answered: an iOS simulator lane once
     * reported SocketTimeoutException "timed out after 5s" (PR #806). The
     * deadline now covers [DNS_FAILURE_RESOLVER_BUDGET], several resolver attempts, and stays under JS
     * mocha's 30 s test timeout. A timeout is still a failure, and its message records how long the
     * resolver was given and how long it took, so a resolver that never answers is told apart from a
     * mapping bug.
     */
    @Test
    fun dnsFailure_producesSocketUnknownHostException() =
        runTestNoTimeSkipping(timeout = DNS_FAILURE_RESOLVER_BUDGET + 5.seconds) {
            if (!networkCapabilities().transports.contains(TransportKind.TCP)) return@runTestNoTimeSkipping
            val started = TimeSource.Monotonic.markNow()
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig(connectTimeout = DNS_FAILURE_RESOLVER_BUDGET))
                    socket.open(
                        port = 80,
                        hostname = "this.host.does.not.exist.invalid",
                    )
                    socket.close()
                    fail("Should have thrown for invalid hostname")
                } catch (e: SocketException) {
                    e
                }
            val elapsed = started.elapsedNow()
            val outcome =
                when (ex) {
                    is SocketTimeoutException ->
                        "the resolver did not answer within the $DNS_FAILURE_RESOLVER_BUDGET budget (gave up after $elapsed)"
                    else -> "a ${ex::class.simpleName} after $elapsed, which is a mapping bug"
                }
            assertIs<SocketUnknownHostException>(ex, "Expected SocketUnknownHostException; $outcome: ${ex.message}")
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
            val port = REFUSED_PORT
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
                    serverFlow.serveEach { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("hello")
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
            // Self-diagnosing: on failure the message carries this test's phase times, what the server
            // accepted, read and wrote (AcceptLog), the listener's own NW timeline (port probe, listener
            // states, each arriving connection's states) and, as a suppressed exception on the client's
            // failure, the client connection's NW state timeline. None of it is formatted on a pass.
            val started = TimeSource.Monotonic.markNow()
            val phases = mutableListOf<String>()

            fun phase(what: String) {
                phases += "+${started.elapsedNow()} $what"
            }
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            phase("server bound on port ${server.port()}")
            val accepts = AcceptLog()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.serveEach(accepts) { serverClient ->
                        // Send non-TLS data — client expects ServerHello, gets garbage → immediate TLS error
                        serverClient.writeString("NOT A TLS RESPONSE\r\n")
                        accepts.note("server wrote its 20-byte non-TLS reply")
                        // Hold the connection open as before (200 ms), noting what the client sent meanwhile:
                        // a ClientHello starts with 0x16, the TLS handshake record type.
                        val held = TimeSource.Monotonic.markNow()
                        val heard = runCatching { serverClient.read(200.milliseconds) }
                        accepts.note("server heard: ${describeRead(heard)}")
                        (200.milliseconds - held.elapsedNow()).takeIf { it.isPositive() }?.let { delay(it) }
                    }
                }

            phase("client connect started")
            val connectStarted = TimeSource.Monotonic.markNow()
            val ex =
                try {
                    val socket = ClientSocket.allocate(TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds.scaled))
                    socket.open(
                        port = server.port(),
                        hostname = "127.0.0.1",
                    )
                    socket.close()
                    fail("TLS handshake should have failed on non-TLS server")
                } catch (e: SocketException) {
                    e
                }
            phase("client connect ended: ${ex::class.simpleName}")
            // The canonical surface is SSLSocketException — Linux JSSE, macOS
            // Network.framework, K/Native BoringSSL all go through that path.
            // Windows NIO2 has been observed to race the channel-close ahead
            // of the SSL alert, surfacing as SocketClosedException.* instead.
            // Both shapes express the same contract: a TLS handshake against a
            // non-TLS peer fails before the application sees data.
            // TODO(JVM/Windows): map the underlying SSLException through
            // SSL{Handshake,Protocol}Exception even when the channel closes first.
            val connectTook = connectStarted.elapsedNow()

            suspend fun diagnostics() =
                "phases:\n  ${phases.joinToString("\n  ")}\n" +
                    "${accepts.describe()}\n" +
                    "server: ${(server as? ServerSocketDiagnostics)?.diagnostics() ?: "no diagnostics on this platform"}\n" +
                    "client: ${ex.suppressedExceptions.joinToString("\n") { it.message.orEmpty() }.ifEmpty { "no timeline" }}"
            // A slow pass is the next failure's data: CI once took 12 s here against a 5 s bound.
            if (connectTook >
                SLOW_TLS_FAILURE
            ) {
                println("SLOW-TLS-TO-PLAIN-SERVER: ${ex::class.simpleName} after $connectTook\n${diagnostics()}")
            }
            if (!(ex is SSLSocketException || ex is SocketClosedException)) {
                fail(
                    "Expected SSLSocketException or SocketClosedException, got ${ex::class.simpleName}: ${ex.message}\n${diagnostics()}",
                    ex,
                )
            }

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
            val port = REFUSED_PORT
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

    private companion object {
        /** Far above the few milliseconds this takes when nothing stalls. */
        val SLOW_TLS_FAILURE = 2.seconds
    }

    private fun describeRead(heard: Result<com.ditchoom.buffer.flow.ReadResult>): String {
        val result = heard.getOrElse { return "read failed: ${it::class.simpleName}: ${it.message}" }
        if (result !is com.ditchoom.buffer.flow.ReadResult.Data) return result.toString()
        val bytes = result.buffer.readByteArray(result.buffer.remaining())
        return "${bytes.size} bytes, first ${bytes.take(8).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}"
    }
}

/**
 * How long [ExceptionIntegrationTests.dnsFailure_producesSocketUnknownHostException] lets the OS resolver
 * take to say a name does not exist: several 5 s resolver attempts, and under JS mocha's 30 s limit.
 */
private val DNS_FAILURE_RESOLVER_BUDGET = 20.seconds
