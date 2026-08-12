package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
        // Budget covers several withLiveQuicConnection attempts: on the virtualized macos-26 CI loopback a
        // connection can come up drain-storm-wedged (handshakes through a transient NW path flap, then
        // passes no bytes), so a warmup probe retries a fresh connection before the real idle-out wait. The
        // server echoes (so the warmup can round-trip), then goes idle once the client stops sending.
        runQuicTest(timeout = 50.seconds) {
            wrapTestBody {
                val opts = options(IDLE_TIMEOUT)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        withLiveQuicConnection(
                            "127.0.0.1",
                            port,
                            opts,
                            timeout = 10.seconds.scaled,
                            reason = "idle-timeout connection never came up live",
                        ) { confirmLive ->
                            val stream = openStream()
                            // Warmup round-trip proves the connection isn't drain-storm-wedged. A wedge here
                            // retries a FRESH connection; the real idle-out assertion can only surface after
                            // confirmLive() and is never retried.
                            if (stream.echoOnce("warmup") != "warmup") retryConnection()
                            confirmLive()
                            // Connection proven live — now go idle. With no traffic the idle timer fires and
                            // the pending read returns End. If idle-timeout didn't fire, the read blocks to its
                            // own (longer) timeout and throws — failing the test.
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
        // Budget covers several withLiveQuicConnection attempts: on the virtualized macos-26 CI loopback a
        // connection can come up drain-storm-wedged (handshakes through a transient NW path flap, then
        // passes no bytes), so the warmup probe retries a fresh connection before the real keepalive wait.
        runQuicTest(timeout = 50.seconds) {
            wrapTestBody {
                val opts = options(KEEPALIVE_IDLE).copy(keepAliveInterval = KEEPALIVE_INTERVAL)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        withLiveQuicConnection(
                            "127.0.0.1",
                            port,
                            opts,
                            timeout = 10.seconds.scaled,
                            reason = "keepalive connection never came up live",
                        ) { confirmLive ->
                            val stream = openStream()
                            // Warmup round-trip proves the connection isn't drain-storm-wedged. A wedge here
                            // retries a FRESH connection; the real keepalive assertion can only surface after
                            // confirmLive() and is never retried.
                            if (stream.echoOnce("warmup") != "warmup") retryConnection()
                            confirmLive()
                            // Connection proven live — go completely idle for longer than the idle timeout.
                            // Reactive keepalive must hold the connection open.
                            delay(KEEPALIVE_IDLE_WAIT)
                            // The connection can DIE during this idle wait from a macos-26 CI drain-storm
                            // ENETDOWN flap (POSIXErrorCode 50, a virtualized-runner artifact) — a death
                            // AFTER confirmLive() that withLiveQuicConnection's establishment-only retry
                            // can't catch. Retry a FRESH connection when the post-idle echo shows the
                            // connection died (a thrown close/stream exception, or no echo came back). This
                            // does NOT mask a real keepalive regression: a broken keepalive idle-closes
                            // DETERMINISTICALLY at KEEPALIVE_IDLE on EVERY attempt, so it exhausts all
                            // retries and still fails (as the withLiveQuicConnection AssertionError); only an
                            // intermittent flap clears on a fresh connection. A wrong (non-empty) echo value
                            // is a real corruption bug and still fails the assertEquals below, unretried.
                            val echo =
                                try {
                                    stream.echoOnce("still-alive")
                                } catch (e: QuicCloseException) {
                                    retryConnection()
                                } catch (e: QuicStreamException) {
                                    retryConnection()
                                }
                            if (echo == "no_data") retryConnection()
                            assertEquals(
                                "still-alive",
                                echo,
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

    /**
     * Reactive keepalive must hold a connection alive across MANY idle-timeout windows, not just
     * one extra interval past the first. [activityKeepsConnectionAlivePastIdleTimeout] only proves
     * survival to 1.5× the idle window (`KEEPALIVE_IDLE_WAIT` = 9s over a 6s window) — that is not
     * enough wall-clock to distinguish "the reactive loop keeps rescheduling PINGs forever" from "the
     * loop fires once, then a subsequent PING/ACK round-trip doesn't reset the timer the same way" —
     * the latter would still pass a 1.5×-window check but fail here.
     *
     * This test goes idle for well over 4× the idle timeout, with a keepalive interval a full 4×
     * under it (matching a downstream report: keepAlive bought roughly one extra interval instead of
     * resetting the idle timer indefinitely). No application traffic runs at all during the wait — the
     * ONLY thing keeping the connection alive is the driver's own reactive PING schedule.
     */
    @Test
    fun keepAliveSurvivesManyIdleTimeoutWindows() =
        runQuicTest(timeout = 40.seconds) {
            wrapTestBody {
                val opts = options(MANY_WINDOWS_IDLE_TIMEOUT).copy(keepAliveInterval = MANY_WINDOWS_KEEPALIVE_INTERVAL)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        withLiveQuicConnection(
                            "127.0.0.1",
                            port,
                            opts,
                            timeout = 10.seconds.scaled,
                            reason = "many-windows keepalive connection never came up live",
                        ) { confirmLive ->
                            val stream = openStream()
                            // Warmup round-trip proves the connection isn't drain-storm-wedged. A wedge here
                            // retries a FRESH connection; the real assertion can only surface after
                            // confirmLive() and is never retried.
                            if (stream.echoOnce("warmup") != "warmup") retryConnection()
                            confirmLive()
                            // Go idle for several multiples of the idle timeout — long enough that a
                            // keepalive which only survives ONE extension (then idle-closes on schedule)
                            // fails deterministically inside this wait, not just at its edge.
                            delay(MANY_WINDOWS_IDLE_WAIT)
                            val echo =
                                try {
                                    stream.echoOnce("still-alive")
                                } catch (e: QuicCloseException) {
                                    retryConnection()
                                } catch (e: QuicStreamException) {
                                    retryConnection()
                                }
                            if (echo == "no_data") retryConnection()
                            val cycles =
                                MANY_WINDOWS_IDLE_WAIT.inWholeMilliseconds.toDouble() /
                                    MANY_WINDOWS_IDLE_TIMEOUT.inWholeMilliseconds
                            assertEquals(
                                "still-alive",
                                echo,
                                "connection idle-closed despite keepalive after $MANY_WINDOWS_IDLE_WAIT " +
                                    "(${cycles}x the idle timeout) — the reactive PING stopped resetting the idle timer " +
                                    "after the first cycle",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The REAL production bug (root-caused from a live tcpdump, 2026-08): [QuicByteStream.readPolicy]
     * governs a *stream-level* read deadline that is completely independent of the *connection's* idle
     * timer. The connection stays healthy the whole test — [keepAliveSurvivesManyIdleTimeoutWindows]
     * above already proves keepalive resets the connection's idle timer indefinitely — but a stream
     * whose peer writes once and then goes silent (no more stream data, though the CONNECTION keeps
     * exchanging keepalive PINGs) starves a no-arg `stream.read()` at
     * [QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE] (15s) regardless of [QuicOptions.idleTimeout] /
     * [QuicOptions.keepAliveInterval], because a PING carries no stream data and therefore never
     * resets it. From the outside this is indistinguishable from "the QUIC idle timeout isn't
     * honored" — a connection that tears itself down and redials on a fixed ~15s cadence — which is
     * exactly the symptom that shipped: [QuicOptions.persistentStreams] defaults to `false`, so this
     * is the pre-existing, unchanged behavior for every caller that hasn't opted in.
     */
    @Test
    fun oneSidedStreamSilenceStarvesTheDefaultReadPolicy() =
        runQuicTest(timeout = 45.seconds) {
            wrapTestBody {
                // persistentStreams defaults to false (unset here) — the request/response Bounded(15s) shape.
                val opts = options(STREAM_SILENCE_IDLE_TIMEOUT).copy(keepAliveInterval = STREAM_SILENCE_KEEPALIVE_INTERVAL)
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { writeOnceThenGoSilent() }
                    try {
                        withLiveQuicConnection(
                            "127.0.0.1",
                            port,
                            opts,
                            timeout = (STREAM_SILENCE_WAIT + 15.seconds).scaled,
                            reason = "stream-silence connection never came up live",
                        ) { confirmLive ->
                            val stream = openStream()
                            stream.writeString("hello") // materializes the stream both ways
                            val first = stream.read(5.seconds.scaled)
                            assertTrue(first is ReadResult.Data, "expected the server's first write, got $first")
                            first.buffer.freeIfNeeded()
                            confirmLive()
                            // The connection itself is fine (keepalive keeps its idle timer reset). This
                            // no-arg read() consults the STREAM's own readPolicy, which nothing about the
                            // connection-level keepalive ever touches — it must starve at the default
                            // 15-second deadline despite the healthy connection underneath it.
                            assertFailsWith<TimeoutCancellationException>(
                                "expected the default Bounded(15s) stream readPolicy to starve this read " +
                                    "while the connection itself stayed healthy (keepalive kept its idle timer " +
                                    "reset) — if this doesn't throw, the stream-level deadline stopped being " +
                                    "enforced independently of the connection's idle timer",
                            ) {
                                stream.read()
                            }
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The fix for [oneSidedStreamSilenceStarvesTheDefaultReadPolicy]: [QuicOptions.persistentStreams]
     * switches every stream's policy to "wait forever", delegating liveness to the connection's own
     * idle timer exactly as [ReadPolicy.UntilClosed][com.ditchoom.buffer.flow.ReadPolicy.UntilClosed]
     * documents. The identical one-sided silence that starves the previous test's read must instead
     * simply wait here, and the peer's second write — sent only after
     * [QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE] has long since passed — must still arrive.
     */
    @Test
    fun persistentStreamsSurviveOneSidedSilenceBeyondTheDefaultReadDeadline() =
        runQuicTest(timeout = 45.seconds) {
            wrapTestBody {
                val opts =
                    options(STREAM_SILENCE_IDLE_TIMEOUT).copy(
                        keepAliveInterval = STREAM_SILENCE_KEEPALIVE_INTERVAL,
                        persistentStreams = true,
                    )
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { writeTwiceWithLongGap() }
                    try {
                        withLiveQuicConnection(
                            "127.0.0.1",
                            port,
                            opts,
                            timeout = (STREAM_SILENCE_WAIT + 15.seconds).scaled,
                            reason = "persistent-stream connection never came up live",
                        ) { confirmLive ->
                            val stream = openStream()
                            stream.writeString("hello")
                            val first = stream.read(5.seconds.scaled)
                            assertTrue(first is ReadResult.Data, "expected the server's first write, got $first")
                            first.buffer.freeIfNeeded()
                            confirmLive()
                            val second = stream.read()
                            assertTrue(second is ReadResult.Data, "persistentStreams did not hold the read open: $second")
                            val text = second.buffer.readString(second.buffer.remaining(), Charset.UTF8)
                            second.buffer.freeIfNeeded()
                            assertEquals("bye", text, "wrong payload arrived on the held-open read")
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun QuicServer.writeOnceThenGoSilent() {
        connections {
            val stream = acceptStream()
            stream.writeString("hi")
            // Stay open but silent on the STREAM — the client's read() above must observe the
            // stream-level timeout on its own; this server never closes or errors anything.
            delay((STREAM_SILENCE_WAIT + 10.seconds).scaled)
            stream.close()
        }
    }

    private suspend fun QuicServer.writeTwiceWithLongGap() {
        connections {
            val stream = acceptStream()
            stream.writeString("hi")
            // Silent for longer than QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE (15s, unscaled — a
            // fixed production constant) before writing again.
            delay(STREAM_SILENCE_WAIT)
            stream.writeString("bye")
            stream.close()
        }
    }

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

        // Many-windows keepalive: short idle timeout + a keepalive interval a full 4x under it, held idle
        // for 4.5x the idle timeout — long enough for ~17 keepalive cycles, so a keepalive that only
        // survives its first extension (then reverts to closing on schedule) fails deterministically.
        private val MANY_WINDOWS_IDLE_TIMEOUT = 2.seconds.scaled
        private val MANY_WINDOWS_KEEPALIVE_INTERVAL = 500.milliseconds.scaled
        private val MANY_WINDOWS_IDLE_WAIT = 9.seconds.scaled // 4.5x MANY_WINDOWS_IDLE_TIMEOUT

        // One-sided stream silence: the connection's own idle timeout/keepalive, short so the "connection
        // stays healthy" half of the story is cheap to hold.
        private val STREAM_SILENCE_IDLE_TIMEOUT = 2.seconds.scaled
        private val STREAM_SILENCE_KEEPALIVE_INTERVAL = 500.milliseconds.scaled

        // Deliberately NOT .scaled: QuicheStreamByteStream.DEFAULT_STREAM_DEADLINE (15s) is a fixed
        // production constant, not itself scaled by testTimeScale() — this margin is measured against
        // that fixed value, not against a scaled idle timeout.
        private val STREAM_SILENCE_WAIT = 18.seconds
    }
}
