package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * W4 Tier-B semantic simulator tests (RFC_DETERMINISTIC_SIMULATION.md §4): REAL quiche client ↔
 * REAL quiche server over the seeded in-memory [ImpairedPipe]. See [withSemanticSim]'s docs for
 * the virtual-time finding; [same_seed_same_datagram_count] documents the determinism finding.
 */
class SemanticSimTests {
    /** Skip (JUnit assumption) when the bundled quiche native lib isn't available — standard quiche-jvmTest discipline. */
    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun handshake_completes_under_10pct_loss() =
        runQuicTest(timeout = 30.seconds) {
            skipOnMissingNativeLib {
                withSemanticSim(
                    ImpairmentConfig(seed = 42L, loss = 0.10),
                    establishTimeout = 15.seconds,
                ) {
                    // Real quiche loss recovery (PTO retransmits) carried the handshake through 10% loss.
                    assertIs<QuicConnectionState.Established>(clientDriver.state.value, "client not established under 10% loss")
                    assertIs<QuicConnectionState.Established>(serverDriver.state.value, "server not established under 10% loss")
                    assertTrue(pipe.clientStats.sent > 0, "client sent no datagrams")
                    assertTrue(pipe.serverStats.sent > 0, "server sent no datagrams")
                }
            }
        }

    @Test
    fun echo_stream_survives_reorder_and_dup() =
        runQuicTest(timeout = 30.seconds) {
            skipOnMissingNativeLib {
                withSemanticSim(
                    ImpairmentConfig(
                        seed = 7L,
                        reorderWindow = 4,
                        duplicateProb = 0.25,
                        latency = 2.milliseconds,
                        jitter = 3.milliseconds,
                    ),
                    establishTimeout = 15.seconds,
                ) {
                    val payload = "hello semantic sim"
                    val serverJob =
                        launch {
                            val stream = server.acceptStream()
                            val data = stream.read(10.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 10.seconds)
                                data.buffer.freeIfNeeded()
                            }
                            stream.close()
                        }
                    try {
                        val stream = client.openStream()
                        val sendBuf = BufferFactory.network().allocate(payload.length)
                        sendBuf.writeString(payload, Charset.UTF8)
                        sendBuf.resetForRead()
                        try {
                            stream.write(sendBuf, 10.seconds)
                        } finally {
                            sendBuf.freeNativeMemory()
                        }
                        val response = stream.read(10.seconds)
                        assertIs<ReadResult.Data>(response, "echo read returned no data through reorder+dup pipe")
                        val echoed = response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                        response.buffer.freeIfNeeded()
                        assertEquals(payload, echoed, "payload corrupted by reorder/duplication")
                        stream.close()
                        // The impairment model actually engaged (seeded, so stable per seed).
                        assertTrue(
                            pipe.clientStats.duplicated + pipe.serverStats.duplicated > 0,
                            "scenario never duplicated a datagram — impairment model not exercised",
                        )
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The determinism proof, at the strength that actually holds for REAL quiche on real
     * dispatchers (documented nondeterminism finding):
     *
     * - **Stable across same-seed runs:** the pipe's impairment decision sequence — same seed →
     *   the shorter run's decision trace is byte-for-byte a prefix of the longer's (one shared
     *   `Random(seed)` consumed in arrival order with a fixed draw count per datagram) — plus the
     *   scenario outcome (establishment + echo).
     * - **NOT stable, and why:** exact per-side datagram counts. Two irreducible sources observed:
     *   (1) real-quiche entropy — TLS key shares/session keys come from BoringSSL's own RNG (our
     *   W1 `Random` seam covers only scid/reset tokens), so handshake packet contents differ per
     *   run and coalescing/ACK boundaries can shift; (2) real-time scheduling — quiche's internal
     *   `Instant::now()` clocking (see [withSemanticSim] docs) makes delayed-ACK/PTO timers race
     *   packet arrival differently per run, occasionally adding/removing a retransmission or a
     *   standalone ACK. The assertion below therefore requires trace-prefix equality
     *   unconditionally, and only sanity-bounds the count drift instead of requiring equality.
     *
     * Measured (9 local same-seed pairs, seed=1234): per-side counts drifted at most ±1
     * (10/10 vs 11/9 — a trailing ACK attributed to one side or the other), while the combined
     * total (20) and the decision-trace length were identical in every sample. The bound below is
     * deliberately looser than the observed drift so a loaded CI runner's extra PTO retransmission
     * can't flake it (flaky-tests-fix-at-root directive); a structural regression (e.g. an
     * unseeded pipe) would blow through it immediately.
     */
    @Test
    fun same_seed_same_datagram_count() =
        runQuicTest(timeout = 60.seconds) {
            skipOnMissingNativeLib {
                val run1 = runSeededEchoScenario(seed = 1234L)
                val run2 = runSeededEchoScenario(seed = 1234L)

                // Impairment decisions: strictly deterministic per seed — prefix equality.
                val shorter = if (run1.decisions.size <= run2.decisions.size) run1.decisions else run2.decisions
                val longer = if (run1.decisions.size <= run2.decisions.size) run2.decisions else run1.decisions
                assertEquals(
                    shorter,
                    longer.subList(0, shorter.size),
                    "same seed must reproduce the identical impairment decision sequence",
                )

                // Datagram counts: bounded drift, not equality (see kdoc for the two entropy sources).
                assertCountsClose("client", run1.clientSent, run2.clientSent)
                assertCountsClose("server", run1.serverSent, run2.serverSent)
                println(
                    "SemanticSim determinism: run1(client=${run1.clientSent}, server=${run1.serverSent}) " +
                        "run2(client=${run2.clientSent}, server=${run2.serverSent}) " +
                        "decisions(run1=${run1.decisions.size}, run2=${run2.decisions.size})",
                )
            }
        }

    @Test
    fun heavy_loss_hits_idle_timeout() =
        runQuicTest(timeout = 45.seconds) {
            skipOnMissingNativeLib {
                withSemanticSim(
                    ImpairmentConfig(seed = 5L),
                    quicOptions = semanticSimOptions(idleTimeout = 2.seconds),
                    establishTimeout = 15.seconds,
                ) {
                    assertIs<QuicConnectionState.Established>(clientDriver.state.value)
                    // Total blackhole from now on: nothing crosses the pipe in either direction.
                    pipe.blackhole = true
                    val clientClosed =
                        withTimeout(20.seconds) {
                            clientDriver.state.first { it is QuicConnectionState.Closed }
                        } as QuicConnectionState.Closed
                    assertEquals(QuicError.IdleTimeout, clientClosed.error, "client close reason must be the typed IdleTimeout")
                    val serverClosed =
                        withTimeout(20.seconds) {
                            serverDriver.state.first { it is QuicConnectionState.Closed }
                        } as QuicConnectionState.Closed
                    assertEquals(QuicError.IdleTimeout, serverClosed.error, "server close reason must be the typed IdleTimeout")
                }
            }
        }

    /**
     * The virtual-time deliverable (see [withSemanticSim] kdoc): a LOSSLESS handshake between two
     * REAL quiche endpoints completes fully under `runTest` virtual time — it is a pure event
     * cascade (channel sends/receives on the single-threaded test scheduler), never blocked on a
     * quiche timer. Timer-dependent paths (loss recovery, idle timeout) cannot be virtualized
     * because quiche's C API is internally clocked by `Instant::now()`, which is why every
     * impaired scenario above runs under `runQuicTest` real dispatchers instead.
     */
    @Test
    fun lossless_handshake_completes_under_virtual_time() =
        runTest(timeout = 60.seconds) {
            try {
                withSemanticSim(
                    ImpairmentConfig(seed = 99L),
                    establishTimeout = 5.seconds, // virtual-time bound: fires only if the cascade stalls
                ) {
                    assertIs<QuicConnectionState.Established>(clientDriver.state.value, "client (virtual time)")
                    assertIs<QuicConnectionState.Established>(serverDriver.state.value, "server (virtual time)")
                }
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }
        }

    private class ScenarioResult(
        val clientSent: Int,
        val serverSent: Int,
        val decisions: List<ImpairedPipe.Decision>,
    )

    /** One seeded scenario: 5%-loss handshake + one echo round-trip, then a short quiesce and a stats snapshot. */
    private suspend fun runSeededEchoScenario(seed: Long): ScenarioResult =
        withSemanticSim(
            ImpairmentConfig(seed = seed, loss = 0.05),
            establishTimeout = 15.seconds,
        ) {
            val stream = client.openStream()
            val payload = "determinism probe"
            val sendBuf = BufferFactory.network().allocate(payload.length)
            sendBuf.writeString(payload, Charset.UTF8)
            sendBuf.resetForRead()
            try {
                stream.write(sendBuf, 10.seconds)
            } finally {
                sendBuf.freeNativeMemory()
            }
            val serverStream = server.acceptStream()
            val inbound = serverStream.read(10.seconds)
            assertIs<ReadResult.Data>(inbound)
            serverStream.write(inbound.buffer, 10.seconds)
            inbound.buffer.freeIfNeeded()
            val echoed = stream.read(10.seconds)
            assertIs<ReadResult.Data>(echoed)
            echoed.buffer.freeIfNeeded()
            stream.close()
            serverStream.close()
            // Let trailing ACK/retransmit flushes settle before snapshotting (reduces, but cannot
            // eliminate, the timing component of the count drift documented on the test above).
            delay(500.milliseconds)
            ScenarioResult(pipe.clientStats.sent, pipe.serverStats.sent, pipe.decisions())
        }

    private fun assertCountsClose(
        side: String,
        a: Int,
        b: Int,
    ) {
        val drift = kotlin.math.abs(a - b)
        val bound = maxOf(6, maxOf(a, b) / 4) // ≤25% (min 6 datagrams) — generous vs the 0-3 typically observed
        assertTrue(
            drift <= bound,
            "$side same-seed datagram count drifted beyond timing noise: $a vs $b (bound $bound) — " +
                "a structural determinism regression, not BoringSSL/timer jitter",
        )
    }
}
