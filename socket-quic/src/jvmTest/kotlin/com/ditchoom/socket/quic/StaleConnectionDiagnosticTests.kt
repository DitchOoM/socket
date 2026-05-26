package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private fun engineOrSkip(): QuicEngine {
        // Skip on GH Actions CI — all tests in this file fail their 10s connect()
        // withTimeout on ubuntu-24.04 hosted runners. See ServerConnectionTimingTest
        // for the long diagnostic story; same symptom. Bypass via
        // RUN_FLAKY_TESTS=1 for the isolation diagnostic step.
        assumeTrue(
            "CI: late-suite handshake hang (see TODO.md)",
            System.getenv("CI") == null || System.getenv("RUN_FLAKY_TESTS") == "1",
        )
        return try {
            defaultQuicEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }
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

    // ── Two sequential echo connections through the same server ────────────

    @Test
    fun twoSequentialEchoConnectionsWork() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val serverJob = launch(Dispatchers.IO) { server.echoHandler() }
                delay(100)

                val clientEngine = engineOrSkip()

                assertEquals("first!", echoRoundTrip(clientEngine, server, "first!"))
                assertEquals("second!", echoRoundTrip(clientEngine, server, "second!"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
                serverEngine.close()
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
                delay(100)

                val clientEngine = engineOrSkip()

                // Connection 1: connect but don't open any streams (health-check)
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                    delay(1.seconds)
                }

                delay(500) // let server-side handler process disconnect

                // Connection 2: full echo round-trip
                assertEquals("hello", echoRoundTrip(clientEngine, server, "hello"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
                serverEngine.close()
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
                delay(100)

                val clientEngine = engineOrSkip()

                for (i in 1..5) {
                    clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                        delay(200)
                    }
                }

                delay(500)
                assertEquals("alive", echoRoundTrip(clientEngine, server, "alive"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
                serverEngine.close()
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
                delay(100)

                // Access connectionsByDcid via reflection
                val dcidMapField = server::class.java.getDeclaredField("connectionsByDcid")
                dcidMapField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dcidMap = dcidMapField.get(server) as MutableMap<*, *>

                val clientEngine = engineOrSkip()

                // Connection 1: echo round-trip
                echoRoundTrip(clientEngine, server, "test1")
                delay(1.seconds) // let cleanup propagate

                // Connection 2: no-stream
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                    delay(500)
                }

                delay(4.seconds) // 3s acceptStream timeout + cleanup

                // All entries should be cleaned up — no stale drivers
                var staleCount = 0
                for ((_, value) in dcidMap.entries) {
                    @Suppress("USELESS_IS_CHECK")
                    val driver = value as QuicheDriver
                    if (driver.commands.isClosedForSend) staleCount++
                }

                assertEquals(
                    0,
                    staleCount,
                    "connectionsByDcid has $staleCount stale entries pointing to destroyed drivers",
                )

                serverJob.cancel()
                server.close()
                clientEngine.close()
                serverEngine.close()
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
                delay(100)

                val clientEngine = engineOrSkip()

                // No-stream, minimal hold time
                clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {}

                // NO delay — reconnect immediately
                assertEquals("fast!", echoRoundTrip(clientEngine, server, "fast!"))

                serverJob.cancel()
                server.close()
                clientEngine.close()
                serverEngine.close()
            }
        }
}
