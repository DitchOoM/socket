package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
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
 * Tests that isolate and verify the server connection handler timing.
 * Pattern: launch client in background, assert server behavior from test scope.
 * Never await server signals from inside the client's connect {} block.
 *
 * **Lifecycle:** Engines come from [withQuicServerEngine] / [withQuicEngine]
 * via the [withEngines] helper — scope-only construction guarantees `close()`
 * on every exit path (including exceptional and cancellation paths). The
 * previous `assumeTrue(CI == null || RUN_FLAKY_TESTS)` gate that hid these
 * tests on CI is dropped: it papered over an engine-leak threshold that is
 * now closed by construction (see `socket-quic/DRIVER_REDESIGN.md` →
 * "Engine lifecycle").
 */
class ServerConnectionTimingTest {
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
        get() =
            QuicTlsConfig(
                certChainPath = certPath("cert.crt"),
                privKeyPath = certPath("cert.key"),
            )

    /**
     * Scope-only engine pair. Native-lib absence skips the test
     * (`assumeTrue`); everything else runs the block inside both engine
     * scopes so `close()` happens regardless of how the block exits.
     */
    private suspend fun <R> withEngines(block: suspend (QuicServerEngine, QuicEngine) -> R): R {
        try {
            return withQuicServerEngine { serverEngine ->
                withQuicEngine { clientEngine ->
                    block(serverEngine, clientEngine)
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }
    }

    @Test
    fun serverHandlerRunsOnClientConnect() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                withEngines { serverEngine, clientEngine ->
                    val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                    val handlerRan = CompletableDeferred<Unit>()

                    // Handler-immediate pattern (see QuicServerTestSuite for context).
                    val serverJob =
                        launch(Dispatchers.IO) {
                            server.connections {
                                handlerRan.complete(Unit)
                            }
                        }

                    try {
                        clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                            // Empty.
                        }

                        withTimeout(10.seconds) { handlerRan.await() }
                    } finally {
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }

    @Test
    fun serverAcceptsStreamFromClient() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                withEngines { serverEngine, clientEngine ->
                    val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                    val streamAccepted = CompletableDeferred<Long>()

                    // Handler-immediate pattern (see QuicServerTestSuite for context).
                    val serverJob =
                        launch(Dispatchers.IO) {
                            server.connections {
                                val stream = acceptStream()
                                streamAccepted.complete(stream.streamId.id)
                                stream.close()
                            }
                        }

                    val clientJob =
                        launch(Dispatchers.IO) {
                            clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val buf =
                                    com.ditchoom.buffer.BufferFactory.Default
                                        .allocate(5)
                                buf.writeString("hello", Charset.UTF8)
                                buf.resetForRead()
                                stream.write(buf, 5.seconds)
                                stream.close()
                            }
                        }

                    try {
                        val streamId = withTimeout(10.seconds) { streamAccepted.await() }
                        assertEquals(0L, streamId, "First client stream should be ID 0")
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }

    @Test
    fun scopeBasedEchoRoundTrip() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                withEngines { serverEngine, clientEngine ->
                    val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                    val echoResult = CompletableDeferred<String>()

                    // Server: accept stream, read, echo back
                    val serverJob =
                        launch(Dispatchers.IO) {
                            server.connections {
                                val stream = acceptStream()
                                val data = stream.read(5.seconds)
                                if (data is com.ditchoom.buffer.flow.ReadResult.Data) {
                                    stream.write(data.buffer, 5.seconds)
                                }
                                stream.close()
                            }
                        }
                    delay(100)

                    // Client: open stream, write, read echo
                    val clientJob =
                        launch(Dispatchers.IO) {
                            clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val buf =
                                    com.ditchoom.buffer.BufferFactory.Default
                                        .allocate(5)
                                buf.writeString("hello", Charset.UTF8)
                                buf.resetForRead()
                                stream.write(buf, 5.seconds)

                                val response = stream.read(5.seconds)
                                if (response is com.ditchoom.buffer.flow.ReadResult.Data) {
                                    echoResult.complete(
                                        response.buffer.readString(response.buffer.remaining(), Charset.UTF8),
                                    )
                                } else {
                                    echoResult.complete("no_data")
                                }
                                stream.close()
                            }
                        }

                    try {
                        val result = withTimeout(10.seconds) { echoResult.await() }
                        assertEquals("hello", result)
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                        server.close()
                    }
                }
            }
        }
}
