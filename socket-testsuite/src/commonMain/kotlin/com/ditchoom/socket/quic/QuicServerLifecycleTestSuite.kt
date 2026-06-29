package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Platform-neutral lifecycle invariants for [withQuicServer] / [QuicServer].
 *
 * Mirrors the [QuicServerTestSuite] / [JvmQuicServerTestSuite] split — platform
 * subclasses override [testTlsConfig] (and optionally [wrapTestBody] for
 * skip-on-missing-native-lib) and inherit every test below. Internal-state
 * assertions (scope cancellation, child coroutine counts) stay in the JVM-only
 * subclass because they need reflection; everything here is externally
 * observable — close returns, handlers terminate, cycles don't hang.
 *
 * **Lifecycle:** with the engine layer gone, the per-call parent scope is
 * created and cancelled by [withQuicServer] itself. There's nothing left to
 * leak — these tests verify the externally observable consequences of that
 * invariant.
 */
abstract class QuicServerLifecycleTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /**
     * Platform hook for skip-on-missing-native-lib semantics. Default
     * passes through; JVM/Android subclasses override.
     */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    protected val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun serverCloseReturnsWithinTwoSeconds() =
        runQuicTest {
            wrapTestBody {
                // Test that exiting the withQuicServer block (which closes the server)
                // completes promptly even with no client activity.
                withTimeout(2.seconds) {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                        // Empty — block exit closes the server. The withTimeout asserts
                        // the close path returns within 2s.
                    }
                }
            }
        }

    @Test
    fun closeWhileConnectionsBlockingDoesNotDeadlock() =
        runQuicTest {
            wrapTestBody {
                // connections() suspends on an empty channel forever. Must not
                // prevent withQuicServer's exit path from returning — regardless
                // of whether the handler coroutine has been scheduled yet.
                var handlerJob: kotlinx.coroutines.Job? = null
                withTimeout(2.seconds) {
                    withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                        handlerJob =
                            launch {
                                connections {
                                    // Unreachable — no client ever arrives in this test.
                                }
                            }
                        // Block exits immediately, which triggers server.close() inside
                        // the helper; that must unblock the connections() handler.
                    }
                }

                // Handler should unblock via the server's scope cancellation.
                val result = withTimeoutOrNull(2.seconds) { handlerJob!!.join() }
                assertNotNull(result, "connections() handler did not terminate after withQuicServer exit")
            }
        }

    @Test
    fun multipleBindCloseCyclesCompletePromptly() =
        runQuicTest {
            wrapTestBody {
                // Each cycle individually bounded; if any one hangs, the per-cycle
                // timeout fails with a clear signal rather than exhausting the
                // whole suite's wall clock.
                repeat(10) {
                    withTimeout(2.seconds) {
                        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                            // Empty — bind + immediate close.
                        }
                    }
                }
            }
        }

    /**
     * A peer consuming peer-initiated streams ([QuicScope.streams] / [QuicScope.acceptStream], which
     * share one incoming-streams channel) must be released when the connection ends, not hang until
     * the caller's own timeout.
     *
     * Here the connection ends via idle timeout — the close signal Network.framework delivers
     * reliably. The client establishes a stream, then collects streams() while idle; when the idle
     * timer fires, the connection closes and the flow must complete. On every quiche-backed platform
     * this always held (the driver closes its incoming-streams channel on close); on Apple the
     * post-handshake connection state used to be dropped, so the channel was never closed and a
     * streams() collector / parked acceptStream() hung forever. The quiche platforms keep this
     * regression honest cross-platform.
     *
     * Collecting streams() (vs a single acceptStream) also tolerates Network.framework delivering a
     * hidden phantom initial stream to the client; it is simply drained, so the test asserts exactly
     * the close-unblocks-consumers contract and nothing incidental.
     */
    @Test
    fun streamsFlowCompletesWhenConnectionCloses() =
        runQuicTest {
            wrapTestBody {
                val opts =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = 3.seconds,
                    )
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                // Accept the client's stream then stay, so the connection closes via
                                // the idle timer (not a handler return).
                                acceptStream()
                                kotlinx.coroutines.awaitCancellation()
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds.scaled) {
                            openStream().writeString("hi") // establish a stream on the server, then go idle
                            var completed = false
                            // If streams() never completes, this withTimeout throws and the test fails loudly.
                            withTimeout(15.seconds.scaled) {
                                streams().collect { stream -> runCatching { stream.close() } } // drain any phantom
                                completed = true
                            }
                            assertTrue(
                                completed,
                                "streams() must complete when the connection idles out, not hang",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun QuicByteStream.writeString(payload: String) {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        try {
            write(out, 5.seconds.scaled)
        } finally {
            out.freeNativeMemory()
        }
    }
}
