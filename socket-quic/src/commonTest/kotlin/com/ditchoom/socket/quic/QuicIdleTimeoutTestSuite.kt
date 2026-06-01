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
                        withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds) {
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
     * Activity resets the idle timer: echo every [KEEPALIVE_GAP] for a total well past [KEEPALIVE_IDLE].
     * Each gap is far below the idle timeout, so the connection must stay alive for every round; if the
     * idle timer were not reset by traffic, a mid-loop echo would fail because the connection had closed.
     */
    @Test
    fun activityKeepsConnectionAlivePastIdleTimeout() =
        runQuicTest {
            wrapTestBody {
                val opts = options(KEEPALIVE_IDLE)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        withQuicConnection("127.0.0.1", port, opts, timeout = 10.seconds) {
                            val stream = openStream()
                            for (round in 0 until KEEPALIVE_ROUNDS) {
                                assertEquals(
                                    "ka-$round",
                                    stream.echoOnce("ka-$round"),
                                    "echo $round failed — connection idle-closed despite activity (idle timer not reset)",
                                )
                                delay(KEEPALIVE_GAP)
                            }
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
                val data = stream.read(KEEPALIVE_IDLE + 5.seconds)
                if (data is ReadResult.Data) {
                    stream.write(data.buffer, 5.seconds)
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
            write(out, 5.seconds)
        } finally {
            out.freeNativeMemory()
        }
    }

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        writeString(payload)
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }

    private companion object {
        private val IDLE_TIMEOUT = 2.seconds
        private val READ_TIMEOUT = 10.seconds // > IDLE_TIMEOUT so a working idle-close returns End first

        private val KEEPALIVE_IDLE = 4.seconds
        private val KEEPALIVE_GAP = 1.seconds // far below KEEPALIVE_IDLE — generous margin against jitter
        private const val KEEPALIVE_ROUNDS = 6 // 6 × 1 s = ~6 s of activity, well past the 4 s idle timeout
    }
}
