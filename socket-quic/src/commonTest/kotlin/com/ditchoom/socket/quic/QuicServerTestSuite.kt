package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared QUIC server test suite. Each platform extends this with a
 * [testTlsConfig] implementation; everything else is inherited.
 *
 * Guarantees test parity — same tests run on JVM, Linux, and Apple.
 *
 * **Lifecycle:** tests call [withQuicServer] / [withQuicConnection] directly.
 * Both helpers own construction and release of every resource (UDP socket,
 * drivers, handler coroutines, parent scope) — the block boundary IS the
 * lifecycle, the engine layer that the old code threaded through is gone.
 * See `socket-quic/DRIVER_REDESIGN.md` → "Engine lifecycle" for the
 * rationale.
 *
 * [wrapTestBody] is the platform extension point for skipping tests when
 * the native quiche binding isn't present — JVM overrides it to translate
 * `UnsatisfiedLinkError` into a JUnit `assumeTrue` skip; native targets
 * inherit the default no-op since their cinterop binding is fixed at
 * compile time.
 */
abstract class QuicServerTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /**
     * Platform hook for skip-on-missing-native-lib semantics. Default
     * passes through; JVM/Android subclasses override to convert
     * `UnsatisfiedLinkError` into a `assumeTrue` skip.
     */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun serverBindsAndReportsPort() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    assertTrue(port > 0, "Server should bind to a real port")
                }
            }
        }

    @Test
    fun serverAcceptsConnection() =
        runQuicTest {
            wrapTestBody {
                // Handler-immediate pattern: see rapidBindConnectCloseCyclesAreClean
                // below for the long story. tl;dr — delay(2.seconds) in the handler
                // deadlocks driver shutdown on GH ubuntu-24.04 because handlers are
                // launched on the engine's scope (not serverJob's). Letting the
                // handler return immediately lets the framework's finally{conn.close()}
                // run on the natural path.
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val handlerRan = CompletableDeferred<Unit>()

                    val serverJob =
                        launch {
                            connections {
                                handlerRan.complete(Unit)
                            }
                        }

                    try {
                        withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                            // Empty — handshake completes, block returns immediately.
                        }
                        kotlinx.coroutines.withTimeout(10.seconds) { handlerRan.await() }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun echoSingleStream() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val echoResult = CompletableDeferred<String>()

                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                val data = stream.read(5.seconds)
                                if (data is ReadResult.Data) {
                                    stream.write(data.buffer, 5.seconds)
                                }
                                stream.close()
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val sendBuf = BufferFactory.deterministic().allocate(11)
                                sendBuf.writeString("hello quic!", Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)

                                val response = stream.read(5.seconds)
                                if (response is ReadResult.Data) {
                                    echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                } else {
                                    echoResult.complete("no_data")
                                }
                                stream.close()
                            }
                        }

                    try {
                        val result = kotlinx.coroutines.withTimeout(10.seconds) { echoResult.await() }
                        assertEquals("hello quic!", result)
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun halfCloseAllowsReadAfterSendFin() =
        runQuicTest {
            wrapTestBody {
                // HTTP/3 request/response shape: the client finishes its send side (FIN) and
                // then reads the response. Before shutdownSend() existed, close() set closed=true
                // and the post-FIN read() threw — this test is the regression guard for the
                // half-close primitive (QuicByteStream/QuicheStreamByteStream.shutdownSend).
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val echoResult = CompletableDeferred<String>()

                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                // Drain the request until the peer's FIN (ReadResult.End).
                                val received = StringBuilder()
                                while (true) {
                                    val r = stream.read(5.seconds)
                                    if (r is ReadResult.Data) {
                                        received.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                    } else {
                                        break
                                    }
                                }
                                val reply = BufferFactory.deterministic().allocate(received.length)
                                reply.writeString(received.toString(), Charset.UTF8)
                                reply.resetForRead()
                                stream.write(reply, 5.seconds)
                                stream.close()
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val sendBuf = BufferFactory.deterministic().allocate(4)
                                sendBuf.writeString("ping", Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)

                                // Half-close: FIN the send side, then keep reading the response.
                                stream.shutdownSend()

                                val response = stream.read(5.seconds)
                                if (response is ReadResult.Data) {
                                    echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                } else {
                                    echoResult.complete("no_data:${response::class.simpleName}")
                                }
                                stream.close()
                            }
                        }

                    try {
                        assertEquals("ping", kotlinx.coroutines.withTimeout(10.seconds) { echoResult.await() })
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun multipleConnections() =
        runQuicTest {
            wrapTestBody {
                // Handler-immediate pattern (see serverAcceptsConnection / rapidBindConnect…).
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val count = 3
                    val handlersRan = CompletableDeferred<Unit>()
                    var connected = 0
                    val lock = Mutex()

                    val serverJob =
                        launch {
                            connections {
                                lock.withLock {
                                    connected++
                                    if (connected >= count) handlersRan.complete(Unit)
                                }
                            }
                        }

                    val clientJobs =
                        (1..count).map {
                            launch {
                                withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                    // Empty block.
                                }
                            }
                        }

                    try {
                        kotlinx.coroutines.withTimeout(10.seconds) { handlersRan.await() }
                    } finally {
                        clientJobs.forEach { it.cancel() }
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun serverCloseIsClean() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    assertTrue(port > 0)
                    // Block exit closes the server — should not throw or hang.
                }
            }
        }

    /**
     * Regression: withQuicServer's exit path must stop the receive loop BEFORE
     * destroying drivers and BEFORE freeing recv buffers. On Linux/io_uring,
     * getting this wrong races with (a) the receive loop routing a packet to
     * an already-destroyed driver (SIGSEGV) or (b) the kernel finishing an
     * in-flight io_uring SQE write into already-freed recv buffers (glibc
     * "malloc(): unsorted double linked list corrupted"). Single-cycle runs
     * in serverAcceptsConnection caught this ~17% of the time; 10 rapid
     * cycles in one test push that to >85%, so a regression fires reliably
     * here.
     */
    @Test
    fun rapidBindConnectCloseCyclesAreClean() =
        runQuicTest {
            wrapTestBody {
                // Each iteration owns its own server lifetime end-to-end via
                // withQuicServer. The old shape reused one engine across all 10
                // cycles to amortize engine-construction overhead; with no engine,
                // there's nothing to amortize — every cycle is independent and
                // pays only the UDP socket + quiche config construction cost.
                //
                // Surgical fix history: handler returns immediately after signaling
                // handlerRan. The connections() impl wraps the handler in
                // try { handler() } finally { conn.close() }, so a returning handler
                // triggers the framework's clean-shutdown path. Client uses an
                // empty block for the same reason. coroutineScope { } inside
                // connections() binds the for-loop to serverJob, so serverJob.cancel()
                // cleanly tears down the iterator AND any in-flight handler.
                repeat(10) {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                        val handlerRan = CompletableDeferred<Unit>()
                        val serverJob =
                            launch {
                                connections {
                                    handlerRan.complete(Unit)
                                    // Return immediately; framework closes conn via finally.
                                }
                            }
                        try {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 5.seconds) {
                                // Empty block.
                            }
                            kotlinx.coroutines.withTimeout(5.seconds) { handlerRan.await() }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
