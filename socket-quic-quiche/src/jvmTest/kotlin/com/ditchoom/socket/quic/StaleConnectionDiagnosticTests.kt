package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * **Lifecycle:** all server / client work runs inside the top-level
 * [withQuicServer] / [withQuicConnection] helpers — block-scoped construction
 * with `close()` in finally on every exit path. The previous
 * `assumeTrue(CI == null || RUN_FLAKY_TESTS)` gate that hid these tests on CI
 * is gone — the lifecycle gap it worked around (leaked engine scopes starving
 * dispatchers on small runners) is closed by construction.
 *
 * **Delays (issue #305).** The `delay(100)` after each `launch { echoHandler() }` is gone: it waited
 * for nothing, because [withQuicServer] has already bound when its block runs and accepted connections
 * queue in `ServerConnectionRegistry.acceptedDrivers` (`Channel.UNLIMITED`) from the moment of bind.
 * The delays that remain are NOT synchronisation — they are the scenario itself. This suite is about
 * what a connection's *lifetime* does to the routing table, so "hold a no-stream connection open for
 * 1 s", "hold it for 200 ms", "leave a gap between connections" are the inputs under test (contrast
 * [immediateReconnectAfterNoStreamConnection], the zero-gap case, which exists precisely because the
 * gap is a variable). Removing them would collapse distinct scenarios into duplicates.
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

    /** Echo server handler: 3s timeout for acceptStream (health-check connections time out). */
    private suspend fun QuicServer.echoHandler() {
        connections {
            try {
                val stream = withTimeout(3.seconds) { acceptStream() }
                try {
                    while (true) {
                        val data = stream.read(10.seconds)
                        if (data is ReadResult.Data) {
                            try {
                                stream.writeFully(data.buffer, 5.seconds)
                            } finally {
                                // read transfers ownership; write is zero-copy and takes none — without this
                                // free every echoed chunk leaks, and accumulated echo leaks were the #401
                                // corruption's primer. writeFully because a QUIC write may be partial.
                                data.buffer.freeIfNeeded()
                            }
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
        server: QuicServer,
        payload: String,
    ): String {
        val result = CompletableDeferred<String>()
        withQuicConnection("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
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
            skipOnMissingNativeLib(StaleConnectionDiagnosticTests::class) {
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { echoHandler() }

                        try {
                            assertEquals("first!", echoRoundTrip(this@withQuicServer, "first!"))
                            assertEquals("second!", echoRoundTrip(this@withQuicServer, "second!"))
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ── No-stream connection followed by echo (mirrors the Android bug) ───

    @Test
    fun noStreamConnectionThenEchoConnection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StaleConnectionDiagnosticTests::class) {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { echoHandler() }

                        try {
                            // Connection 1: connect but don't open any streams (health-check)
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                delay(1.seconds)
                            }

                            delay(500) // let server-side handler process disconnect

                            // Connection 2: full echo round-trip
                            assertEquals("hello", echoRoundTrip(this@withQuicServer, "hello"))
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ── 5 no-stream connections, then one real echo ───────────────────────

    @Test
    fun multipleNoStreamConnectionsThenEcho() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StaleConnectionDiagnosticTests::class) {
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { echoHandler() }

                        try {
                            for (i in 1..5) {
                                withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                    delay(200)
                                }
                            }

                            delay(500)
                            assertEquals("alive", echoRoundTrip(this@withQuicServer, "alive"))
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ── connectionsByDcid has no stale entries after connections close ─────

    @Test
    fun connectionsByDcidIsCleanedUpAfterConnectionClose() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StaleConnectionDiagnosticTests::class) {
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { echoHandler() }

                        // Access the routing map via reflection — it now lives on the server's
                        // ServerConnectionRegistry (extracted from the per-platform server), so hop
                        // through the `registry` field first, then its private `connectionsByDcid`.
                        val server: QuicServer = this@withQuicServer
                        val registryField = server::class.java.getDeclaredField("registry")
                        registryField.isAccessible = true
                        val registry = registryField.get(server)
                        val dcidMapField = registry::class.java.getDeclaredField("connectionsByDcid")
                        dcidMapField.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val dcidMap = dcidMapField.get(registry) as MutableMap<*, *>

                        try {
                            // Connection 1: echo round-trip
                            echoRoundTrip(server, "test1")
                            delay(1.seconds) // let cleanup propagate

                            // Connection 2: no-stream
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                delay(500)
                            }

                            // Await the condition the assertion depends on instead of budgeting for it
                            // (issue #305). The old `delay(4.seconds)` was sized as "3 s acceptStream
                            // timeout + cleanup": connection 2 opens no stream, so its handler sits in
                            // `withTimeout(3.seconds) { acceptStream() }`, and only when that expires does
                            // it return, close the connection, and let the receive loop drain
                            // `driverCleanupQueue` into `connectionsByDcid`.
                            //
                            // The assertion below — "no entry points at a destroyed driver" — is vacuously
                            // true BEFORE the sweep runs, so the await deliberately does NOT poll it: that
                            // would return on the first tick and let the test pass without cleanup ever
                            // happening. It polls the real post-condition instead. Both connections are
                            // finished, so a correct sweep empties the map entirely; a broken one leaves the
                            // entry behind, burns the whole deadline (longer than the old fixed wait, so
                            // nothing gets less time than before) and falls through to the assertion, which
                            // reports the stale count with its own diagnostic rather than a bare timeout.
                            withTimeoutOrNull(8.seconds) { while (dcidMap.isNotEmpty()) delay(20) }

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
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    // ── Immediate reconnect after no-stream (zero delay between connections) ──

    @Test
    fun immediateReconnectAfterNoStreamConnection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(StaleConnectionDiagnosticTests::class) {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { echoHandler() }

                        try {
                            // No-stream, minimal hold time
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {}

                            // NO delay — reconnect immediately
                            assertEquals("fast!", echoRoundTrip(this@withQuicServer, "fast!"))
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
