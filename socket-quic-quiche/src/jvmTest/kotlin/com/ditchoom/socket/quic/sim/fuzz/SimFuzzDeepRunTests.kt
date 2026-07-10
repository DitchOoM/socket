package com.ditchoom.socket.quic.sim.fuzz

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The W5 deep-exploration lane (RFC_DETERMINISTIC_SIMULATION.md §6): the same generator + invariant
 * harness as `SimFuzzSmokeTests`, but over an env-sized seed range — the knob CI leaves small and a
 * soak/investigation run turns up (the `SOCKET_READBUF_BENCHMARK` / `QUIC_TEST_TIME_SCALE` env-gating
 * pattern; Gradle forwards the environment to the test JVM, no build-config wiring needed):
 *
 * ```
 * SIM_FUZZ_ITERATIONS=5000 ./gradlew :socket-quic-quiche:jvmTest --tests '*SimFuzzDeepRunTests*'
 * ```
 *
 * Seeds are `DEEP_RUN_SEED_BASE + 0 until iterations` — fixed base, disjoint from the smoke corpus,
 * so a deep run is itself fully reproducible and any failure names the exact seed (plus the shrunk
 * minimal timeline) in its message. Everything runs under virtual time: ~5 ms per seed, so even
 * 5000 iterations stay well inside the test timeout.
 */
class SimFuzzDeepRunTests {
    @Test
    fun deepRun_envSizedSeedRange_holdsAllInvariants() =
        runTest(timeout = 30.minutes) {
            val iterations = System.getenv("SIM_FUZZ_ITERATIONS")?.toIntOrNull() ?: DEFAULT_ITERATIONS
            for (i in 0 until iterations) {
                runSeedOrFailShrunk(DEEP_RUN_SEED_BASE + i)
            }
        }

    private companion object {
        /** Default kept small so the routine jvmTest lane stays fast; CI never needs the env var. */
        const val DEFAULT_ITERATIONS = 100

        /** Disjoint from the 1-25 smoke corpus, so deep-run findings are always fresh seeds. */
        const val DEEP_RUN_SEED_BASE = 1_000_000L
    }
}
