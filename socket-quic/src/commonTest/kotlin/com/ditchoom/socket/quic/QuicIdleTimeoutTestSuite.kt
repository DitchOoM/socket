package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **idle-timeout / keepalive** test suite (issue #87, suite #5). Verifies the two halves of QUIC
 * idle behaviour that were previously untested:
 *  - an idle connection times out and transitions cleanly to closed (a pending read returns
 *    [ReadResult.End], and `withQuicConnection` returns normally — not a thrown read-timeout, not an
 *    abrupt cancellation);
 *  - **activity resets the idle timer**: a connection kept busy with traffic spaced closer than the
 *    idle timeout stays alive well past that timeout (the QUIC keepalive property — the library exposes
 *    no PING interval, so application traffic stands in for it).
 *
 * Same 3-tier shape as the other suites: commonTest abstract + per-platform [testTlsConfig]; Android has
 * a self-contained parallel copy (`AndroidQuicIdleTimeoutTests`).
 *
 * **Determinism.** The idle test asserts the *kind* of result (clean End vs a thrown read-timeout), not
 * a wall-clock value, so it's robust: if idle-timeout never fired, the read would block to its own
 * (longer) timeout and the test would fail. The keepalive test uses a gap far below the idle timeout
 * (generous margin) so scheduling jitter can't make it flake.
 */
abstract class QuicIdleTimeoutTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private fun options(idleTimeout: Duration) = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = idleTimeout)

    // ---- tests -------------------------------------------------------------------------------------

    /**
     * An idle connection must time out and close cleanly. The server accepts the stream then stays in
     * the connection (so it doesn't close on handler-return); with no traffic, the idle timer fires and
     * the client's pending read returns [ReadResult.End]. If idle-timeout didn't fire, the read would
     * block to its own (longer) timeout and throw — failing the test.
     */
    @Test
    fun idleConnectionTimesOutWithCleanEnd() =
        runQuicTest {
            wrapTestBody {
                val opts = options(IDLE_TIMEOUT)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                acceptStream()
                                awaitCancellation()
                            }
                        }
                    try {
                        withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds.scaled) {
                            val stream = openStream()
                            stream.writeString("hi") // establish the stream on the server, then go idle
                            val result = stream.read(READ_TIMEOUT)
                            assertTrue(
                                result is ReadResult.End,
                                "idle timeout should close the stream cleanly (End) within $READ_TIMEOUT, got $result",
                            )
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * Reactive keepalive (RFC 9000 §10.1.2): with [QuicOptions.keepAliveInterval] set below the idle
     * timeout, an **otherwise-idle** connection — no application traffic at all — stays alive past the
     * idle timeout because the driver schedules ack-eliciting PINGs on its own timer.
     *
     * Deterministic by construction: we prime the stream, then go fully idle and wait *once* for well
     * over the idle timeout before a single liveness round-trip. A longer wait (a slow/loaded runner)
     * only strengthens the test — there is no upper-bounded gap that scheduling jitter can blow past, the
     * way the old "echo every N ms for K rounds" version had. Without keepalive the idle timer closes the
     * connection during the wait and the final echo fails; with it, the echo round-trips.
     */
    @Test
    fun activityKeepsConnectionAlivePastIdleTimeout() =
        runQuicTest {
            wrapTestBody {
                val opts = options(KEEPALIVE_IDLE).copy(keepAliveInterval = KEEPALIVE_INTERVAL)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds.scaled) {
                            val stream = openStream()
                            // Establish the stream on the server, then go completely idle for longer than
                            // the idle timeout. Reactive keepalive must hold the connection open.
                            assertEquals("warmup", stream.echoOnce("warmup"), "warmup echo failed before idle wait")
                            delay(KEEPALIVE_IDLE_WAIT)
                            assertEquals(
                                "still-alive",
                                stream.echoOnce("still-alive"),
                                "connection idle-closed despite keepalive — the reactive PING did not reset the idle timer",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun QuicServer.echoEveryStream() {
        connections {
            val stream = acceptStream()
            while (true) {
                // Read backstop must outlast the client's full idle wait, or the server breaks out of the
                // echo loop before the keepalive round-trip — tie it to KEEPALIVE_IDLE_WAIT, not the window.
                val data = stream.read(KEEPALIVE_IDLE_WAIT + 5.seconds.scaled)
                if (data is ReadResult.Data) {
                    stream.write(data.buffer, 5.seconds.scaled)
                } else {
                    break
                }
            }
            stream.close()
        }
    }

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

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        writeString(payload)
        val resp = read(5.seconds.scaled)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }

    private companion object {
        // Every value is `.scaled` (>= 1.0, uniform) so a loaded CI runner gets proportionally more
        // wall-clock without altering any timing relationship — see [Duration.scaled]. Ratios, not
        // absolutes, carry the assertions here.
        private val IDLE_TIMEOUT = 2.seconds.scaled
        private val READ_TIMEOUT = 10.seconds.scaled // 5× IDLE_TIMEOUT so a working idle-close returns End first

        // Keepalive: the PING interval is kept a full 6× under the idle window. The previous 4s/1s (4×)
        // was two-sided — a scheduler-starved runner could delay the 1s PING past the 4s window and the
        // connection would idle-close (a false failure). At 6× even several consecutive starved intervals
        // still land a PING inside the window before the idle timer fires.
        private val KEEPALIVE_IDLE = 6.seconds.scaled
        private val KEEPALIVE_INTERVAL = 1.seconds.scaled // PING every 1 s — 5 s slack under the 6 s idle window
        private val KEEPALIVE_IDLE_WAIT = 9.seconds.scaled // idle 1.5× the window so a broken keepalive closes
    }
}
