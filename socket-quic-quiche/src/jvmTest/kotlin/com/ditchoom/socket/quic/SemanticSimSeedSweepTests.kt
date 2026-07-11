package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The W5 Tier-B seed sweep (RFC_DETERMINISTIC_SIMULATION.md §6 over §4): REAL quiche client ↔
 * server through the seeded [ImpairedPipe], swept over a small FIXED grid of seeds × impairment
 * shapes (loss / reorder / duplication), asserting the W4 invariants on every cell:
 *
 * - **established-or-typed-timeout**: the handshake either completes (both drivers `Established`)
 *   or the establishment bound fires as a typed `TimeoutCancellationException` — never a hang, never
 *   a stringly failure. For this grid's mild impairments (loss ≤ 8% — W4 proved 10% establishes),
 *   establishment is REQUIRED; a timeout here is a real regression, not noise.
 * - **teardown leaves nothing behind**: `withSemanticSim` returning is itself the no-hang proof —
 *   its finally block joins driver destruction and pipe close on every exit path.
 *
 * Real time by necessity (quiche's PTO/idle timers are internally `Instant::now()`-clocked — the
 * RFC §4 Tier-B correction), so the grid stays small and bounded: the whole sweep must finish well
 * inside a CI minute; deep event-ordering exploration belongs to the virtual-time Tier-A lane
 * (`SimFuzzSmokeTests` / `SimFuzzDeepRunTests`).
 */
class SemanticSimSeedSweepTests {
    private class Cell(
        val name: String,
        val config: ImpairmentConfig,
    )

    /** Fixed grid — seeds and shapes are pinned; a failure names its cell + seed for exact replay. */
    private val grid =
        listOf(
            Cell("loss-8pct/seed-101", ImpairmentConfig(seed = 101L, loss = 0.08)),
            Cell("loss-8pct/seed-203", ImpairmentConfig(seed = 203L, loss = 0.08)),
            Cell(
                "reorder-5/seed-303",
                ImpairmentConfig(seed = 303L, reorderWindow = 5, latency = 2.milliseconds, jitter = 3.milliseconds),
            ),
            Cell("dup-30pct/seed-404", ImpairmentConfig(seed = 404L, duplicateProb = 0.3)),
            Cell(
                "mixed/seed-505",
                ImpairmentConfig(
                    seed = 505L,
                    loss = 0.05,
                    reorderWindow = 3,
                    duplicateProb = 0.15,
                    latency = 1.milliseconds,
                    jitter = 2.milliseconds,
                ),
            ),
        )

    @Test
    fun seedSweep_everyCellEstablishes_noHangs() =
        runQuicTest(timeout = 90.seconds) {
            try {
                for (cell in grid) {
                    withSemanticSim(cell.config, establishTimeout = 20.seconds) {
                        assertIs<QuicConnectionState.Established>(
                            clientDriver.state.value,
                            "client not established in cell ${cell.name}",
                        )
                        assertIs<QuicConnectionState.Established>(
                            serverDriver.state.value,
                            "server not established in cell ${cell.name}",
                        )
                        assertTrue(pipe.clientStats.sent > 0, "client sent nothing in cell ${cell.name}")
                        assertTrue(pipe.serverStats.sent > 0, "server sent nothing in cell ${cell.name}")

                        // W3↔W4 cross-check: the stats bindings must report REAL traffic on a real
                        // impaired connection — on every backend this sweep runs under (JNI + FFM,
                        // both of which bind the stats FFI, so null here is a binding regression).
                        val client = assertNotNull(clientDriver.stats().connStats, "client connStats null in ${cell.name}")
                        val server = assertNotNull(serverDriver.stats().connStats, "server connStats null in ${cell.name}")
                        assertTrue(
                            client.sent > 0 && client.recv > 0,
                            "client stats() reports no traffic in cell ${cell.name}: $client",
                        )
                        assertTrue(
                            server.sent > 0 && server.recv > 0,
                            "server stats() reports no traffic in cell ${cell.name}: $server",
                        )

                        // Loss cells must not pass vacuously: the pipe must have actually dropped
                        // datagrams, and quiche must have NOTICED — a handshake that established
                        // through drops did so by retransmitting, so lost+retrans reaches nonzero.
                        // A tail drop can take one PTO to be *declared* lost, so poll briefly
                        // instead of snapshotting the instant establishment completes.
                        if (cell.config.loss > 0.0) {
                            val dropped = pipe.clientStats.dropped + pipe.serverStats.dropped
                            assertTrue(
                                dropped > 0,
                                "cell ${cell.name} configured loss=${cell.config.loss} but the pipe " +
                                    "dropped nothing — the impairment is not exercising anything",
                            )
                            var noticed = quicheNoticedLoss()
                            val deadline = System.nanoTime() + 5_000_000_000L
                            while (!noticed && System.nanoTime() < deadline) {
                                kotlinx.coroutines.delay(100.milliseconds)
                                noticed = quicheNoticedLoss()
                            }
                            assertTrue(
                                noticed,
                                "cell ${cell.name}: pipe dropped $dropped datagrams but neither " +
                                    "side's quiche stats show lost/retrans > 0 within 5s — stats " +
                                    "bindings and impairment are disconnected",
                            )
                        }
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }
        }

    /** True when either side's quiche loss detection has registered the pipe's drops. */
    private suspend fun SemanticSimScope.quicheNoticedLoss(): Boolean {
        val c = clientDriver.stats().connStats
        val s = serverDriver.stats().connStats
        return (c != null && c.lost + c.retrans > 0) || (s != null && s.lost + s.retrans > 0)
    }
}
