package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the connectionsByDcid stale-entry bug.
 *
 * The server's receive loop routes packets by DCID. When a connection handler finishes,
 * the driver is destroyed but its entries in connectionsByDcid must also be removed.
 * Without cleanup, stale entries accumulate — a memory leak that can also cause
 * silent packet drops if a late-arriving packet hits a dead driver.
 *
 * These tests verify:
 * 1. Sequential connections work (no cross-connection interference)
 * 2. No-stream connections don't poison subsequent connections
 * 3. connectionsByDcid is properly cleaned up after connection close
 *
 * Reactive: every wait is bounded by a wall-clock timeout but converges within a
 * scheduler tick of the observable event — no fixed `delay()` calls.
 */
class StaleConnectionDiagnosticTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private fun engineOrSkip(): QuicEngine =
        try {
            defaultQuicEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    /** Echo server handler: 3s timeout for acceptStream (health-check connections time out). */
    private suspend fun QuicServer.echoHandler() {
        connections {
            try {
                val stream = withTimeout(3.seconds) { acceptStream() }
                try {
                    while (true) {
                        val data = stream.read(10.seconds)
                        if (data is ReadResult.Data) {
                            stream.write(data.buffer, 5.seconds)
                        } else {
                            break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stream.close()
                }
            } catch (_: Exception) {
                // Health-check connection — no stream opened
            }
        }
    }

    private suspend fun echoRoundTrip(
        engine: QuicEngine,
        server: QuicServer,
        payload: String,
    ): String {
        val result = CompletableDeferred<String>()
        engine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
            val stream = openStream()
            val buf = BufferFactory.Default.allocate(payload.length)
            buf.writeString(payload, Charset.UTF8)
            buf.resetForRead()
            stream.write(buf, 5.seconds)
            val response = stream.read(5.seconds)
            if (response is ReadResult.Data) {
                result.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
            } else {
                result.complete("no_data")
            }
            stream.close()
        }
        return result.await()
    }

    /** Pull the live connectionsByDcid map out of a JvmQuicServer via reflection (test-only). */
    private fun dcidMapOf(server: QuicServer): Map<*, *> {
        val field = server::class.java.getDeclaredField("connectionsByDcid")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(server) as Map<*, *>
    }

    /** True when every entry in the dcid map points to a driver that is still actively accepting commands. */
    private fun Map<*, *>.hasNoStaleDrivers(): Boolean = values.none { (it as QuicheDriver).commands.isClosedForSend }

    // ── Two sequential echo connections through the same server ────────────

    @Test
    fun twoSequentialEchoConnectionsWork() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }
                // server.bind() returns when the socket is bound; the receive loop
                // buffers any racing client packets into acceptedDrivers (unbounded).
                // No "wait for server" delay needed.

                val clientEngine = engineOrSkip()

                assertEquals("first!", echoRoundTrip(clientEngine, server, "first!"))
                assertEquals("second!", echoRoundTrip(clientEngine, server, "second!"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }

    // ── No-stream connection followed by echo (mirrors the Android bug) ───

    @Test
    fun noStreamConnectionThenEchoConnection() =
        runBlocking(Dispatchers.IO) {
            withTimeout(20.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }

                val clientEngine = engineOrSkip()
                val dcidMap = dcidMapOf(server)

                // Connection 1: handshake + no stream + close. Echo handler's acceptStream
                // will time out (3s) after the connect block returns and the connection is
                // closed; we let it run async — we'll wait below for cleanup to settle.
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {}

                // Wait for server-side cleanup to drain the stale driver entries before
                // we open Connection 2. Bound by 5s — well past the 3s acceptStream timeout.
                awaitUntil(5.seconds, "Connection 1's no-stream driver cleaned up from dcidMap") {
                    dcidMap.hasNoStaleDrivers()
                }

                // Connection 2: full echo round-trip
                assertEquals("hello", echoRoundTrip(clientEngine, server, "hello"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }

    // ── 5 no-stream connections, then one real echo ───────────────────────

    @Test
    fun multipleNoStreamConnectionsThenEcho() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }

                val clientEngine = engineOrSkip()
                val dcidMap = dcidMapOf(server)

                for (i in 1..5) {
                    clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {}
                }

                awaitUntil(5.seconds, "all 5 no-stream drivers cleaned up from dcidMap") {
                    dcidMap.hasNoStaleDrivers()
                }
                assertEquals("alive", echoRoundTrip(clientEngine, server, "alive"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }

    // ── connectionsByDcid has no stale entries after connections close ─────

    @Test
    fun connectionsByDcidIsCleanedUpAfterConnectionClose() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }

                val clientEngine = engineOrSkip()
                val dcidMap = dcidMapOf(server)

                // Connection 1: echo round-trip
                echoRoundTrip(clientEngine, server, "test1")
                awaitUntil(5.seconds, "echo driver 1 cleaned up from dcidMap") {
                    dcidMap.hasNoStaleDrivers()
                }

                // Connection 2: handshake + no stream + close. Echo handler's acceptStream
                // times out (3s) and then the receive loop drains the queue.
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {}

                awaitUntil(5.seconds, "no-stream driver cleaned up from dcidMap") {
                    dcidMap.hasNoStaleDrivers()
                }

                // hasNoStaleDrivers() returning true means there are zero entries whose
                // driver has closed; expressed as the original assertion below for clarity.
                val staleCount =
                    dcidMap.values.count { (it as QuicheDriver).commands.isClosedForSend }
                assertEquals(
                    0,
                    staleCount,
                    "connectionsByDcid has $staleCount stale entries pointing to destroyed drivers",
                )

                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }

    // ── Immediate reconnect after no-stream (zero delay between connections) ──

    @Test
    fun immediateReconnectAfterNoStreamConnection() =
        runBlocking(Dispatchers.IO) {
            withTimeout(20.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }

                val clientEngine = engineOrSkip()

                // No-stream, minimal hold time
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {}

                // NO delay — reconnect immediately
                assertEquals("fast!", echoRoundTrip(clientEngine, server, "fast!"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }
}
