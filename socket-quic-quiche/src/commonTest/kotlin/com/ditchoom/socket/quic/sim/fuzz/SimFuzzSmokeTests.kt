package com.ditchoom.socket.quic.sim.fuzz

import com.ditchoom.socket.quic.sim.SimEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The W5 CI-safe fuzz smoke suite (RFC_DETERMINISTIC_SIMULATION.md §6): a FIXED seed list — no
 * clock, no ambient randomness in test selection — each run through the full invariant harness
 * ([checkFuzzInvariants]) under virtual time. Green means every invariant holds on all 25
 * adversarial timelines on every platform; a failure prints the seed plus the shrunk minimal
 * timeline as a paste-ready `simFixture` DSL block ([shrinkFuzzCase]).
 *
 * Deeper exploration is the env-gated JVM lane (`SimFuzzDeepRunTests`, `SIM_FUZZ_ITERATIONS`).
 */
class SimFuzzSmokeTests {
    /**
     * The pinned smoke corpus. Deliberately literal (not a range expression) so a future edit is a
     * visible corpus change in review, and chosen once — seeds are never dropped to dodge a failure
     * (flaky-tests-fix-at-root). On the fuzzer's FIRST run, seed 1 (and every keepalive-off seed)
     * exposed the idle-close reader-loop buffer leak fixed in QuicheDriver.run's finally — the
     * shrunk finding and its sibling close-race fix live in ReaderLoopCloseRaceRegressionTests.
     */
    private val smokeSeeds =
        listOf<Long>(
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            15,
            16,
            17,
            18,
            19,
            20,
            21,
            22,
            23,
            24,
            25,
        )

    @Test
    fun generator_isPureFunctionOfSeed() {
        for (seed in smokeSeeds) {
            val a = SimTimelineGenerator(seed).generate()
            val b = SimTimelineGenerator(seed).generate()
            assertEquals(a.fixture.events, b.fixture.events, "seed $seed produced two different timelines")
            assertEquals(a.fixture.duration, b.fixture.duration, "seed $seed produced two different horizons")
            assertEquals(a.keepAliveInterval, b.keepAliveInterval, "seed $seed produced two different driver configs")
        }
    }

    @Test
    fun generator_coversEveryEventFamilyAcrossTheCorpus() {
        val all = smokeSeeds.flatMap { SimTimelineGenerator(it).generate().fixture.events }
        assertTrue(all.any { it is SimEvent.DatagramIn }, "corpus generated no datagrams")
        assertTrue(all.any { it is SimEvent.SendError }, "corpus generated no send errors")
        assertTrue(all.any { it is SimEvent.RecvError }, "corpus generated no recv errors")
        assertTrue(all.any { it is SimEvent.Availability }, "corpus generated no availability flaps")
        assertTrue(all.any { it is SimEvent.Network }, "corpus generated no networkId changes")
        assertTrue(all.any { it is SimEvent.Liveness }, "corpus generated no liveness outcomes")
        // Both driver configs must be represented, or half the state space went dark.
        val configs = smokeSeeds.map { SimTimelineGenerator(it).generate().keepAliveInterval }.toSet()
        assertEquals(2, configs.size, "corpus must cover keepalive-on AND keepalive-off driver configs")
    }

    @Test
    fun smokeSeeds_holdAllInvariants() =
        runTest(timeout = 120.seconds) {
            for (seed in smokeSeeds) {
                runSeedOrFailShrunk(seed)
            }
        }
}

/**
 * Run one seed through the invariant harness; on violation, shrink and fail with the seed, the
 * violations, and the minimal timeline as a committable `simFixture` DSL block. Shared by the
 * smoke suite and the deep-run lane.
 */
internal suspend fun TestScope.runSeedOrFailShrunk(
    seed: Long,
    config: FuzzConfig = FuzzConfig(),
) {
    val case = SimTimelineGenerator(seed).generate(config)
    val verdict = checkFuzzInvariants(case)
    if (!verdict.failed) return
    val shrunk = shrinkFuzzCase(case)
    fail(
        buildString {
            appendLine(
                "seed $seed violated invariants (${case.fixture.events.size} events -> ${shrunk.case.fixture.events.size} after shrink):",
            )
            verdict.violations.forEach { appendLine("  - $it") }
            appendLine("minimal violations:")
            shrunk.violations.forEach { appendLine("  - $it") }
            appendLine("ready-to-paste regression fixture:")
            appendLine(shrunk.fixtureDsl)
            append(verdict.run.trace.render())
        },
    )
}
