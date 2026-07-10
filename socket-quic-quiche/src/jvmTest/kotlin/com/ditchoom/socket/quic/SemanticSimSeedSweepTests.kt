package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
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
            Cell("loss-8pct/seed-202", ImpairmentConfig(seed = 202L, loss = 0.08)),
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
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }
        }
}
