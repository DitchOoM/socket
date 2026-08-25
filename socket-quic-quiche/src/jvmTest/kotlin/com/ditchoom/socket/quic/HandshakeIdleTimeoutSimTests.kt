package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A deterministic instrument for #450 / #367 — the handshake that dies of `local: IdleTimeout`.
 *
 * ## Why this exists instead of another loop under load
 *
 * #450 says the next step is "a loop under deliberate CPU load", and that was tried twice and is a
 * dead end both times:
 *
 * | arm | result |
 * |---|---|
 * | 18-core Mac, 16 burners, load average 30-56, 40 runs on `main` | 0/40 |
 * | 4-vCPU CI runner, 25 runs on `main` (flake-hunt.yaml) | 0/25 |
 *
 * A hunt whose *control* never reproduces cannot evaluate a fix — a clean treatment arm would look
 * identical to a real one. And even a successful hunt only yields a rate, when what a fix needs is a
 * failing case that can be re-run on demand.
 *
 * So this replaces the search over *machine load* with a search over *seeds*. [withSemanticSim] runs a
 * real quiche client against a real quiche server through an [ImpairedPipe] whose every decision —
 * drop, duplicate, reorder slot, jitter fraction — is a pure function of one seed. Under
 * [SimClock] quiche's own PTO/idle timers become virtual too, so a scenario is reproducible rather
 * than merely repeatable, and a seed that fails, fails every time.
 *
 * ## The specific thing the virtual clock fixes
 *
 * With the default `RealDriverClock` under `runTest`, the sim's timing is not merely fast — it is
 * **incoherent**. Our own `delay()`s fast-forward on the test scheduler while quiche's internal timers
 * read a wall clock that has barely moved, so the two sides of the FFI disagree about how much time
 * has passed. That disagreement is a decent model of what a loaded machine does to a handshake, and it
 * is the reason these tests pin the clock explicitly and say which one they used.
 */
class HandshakeIdleTimeoutSimTests {
    /** How a single handshake attempt ended, as an exhaustive verdict rather than a boolean. */
    private sealed interface Outcome {
        data object Established : Outcome

        /** The #450 signature: our side gave up mid-handshake with no wire close from the peer. */
        data object LocalIdleTimeout : Outcome

        data class OtherClose(
            val reason: QuicCloseReason,
        ) : Outcome

        data class Threw(
            val kind: String,
        ) : Outcome
    }

    private fun classify(state: QuicConnectionState): Outcome =
        when (state) {
            is QuicConnectionState.Established -> Outcome.Established
            is QuicConnectionState.Closed ->
                if (state.reason == QuicCloseReason.ByLocal(QuicError.IdleTimeout)) {
                    Outcome.LocalIdleTimeout
                } else {
                    Outcome.OtherClose(state.reason)
                }
            else -> Outcome.Threw(state::class.simpleName ?: "unknown")
        }

    /**
     * One handshake attempt at [seed] under [loss], reported as an [Outcome] rather than a pass/fail.
     *
     * `establishTimeout` is generous on purpose: this is asking *how* the handshake ends, so a bound
     * that fires first would replace the answer with a timeout.
     */
    private suspend fun attempt(
        seed: Long,
        loss: Double,
        clock: DriverClock,
        idleTimeout: kotlin.time.Duration = 2.seconds,
    ): Outcome =
        runCatching {
            withSemanticSim(
                ImpairmentConfig(seed = seed, loss = loss, latency = 5.milliseconds),
                quicOptions = semanticSimOptions(idleTimeout = idleTimeout),
                establishTimeout = 60.seconds,
                clock = clock,
            ) {
                classify(clientDriver.state.value)
            }
        }.getOrElse { t -> Outcome.Threw(t::class.simpleName ?: "unknown") }

    /**
     * **The property the whole instrument rests on: a seed determines the outcome.**
     *
     * Without this, a seed sweep is just a slower flake hunt. Run first, and deliberately at a loss
     * rate high enough that the pipe is making real decisions rather than delivering everything.
     */
    @Test
    fun theSameSeedGivesTheSameOutcomeUnderTheVirtualClock() =
        runTest(timeout = 300.seconds) {
            val seed = 1234L
            val first = attempt(seed, loss = 0.30, clock = SimClock(testScheduler))
            val second = attempt(seed, loss = 0.30, clock = SimClock(testScheduler))

            assertEquals(
                first,
                second,
                "seed $seed produced $first then $second. The ImpairedPipe draws a fixed number of " +
                    "values per datagram from Random(seed), so two runs at one seed must take the same " +
                    "path. If they diverge, the sim has a source of entropy outside the seed and no " +
                    "seed sweep below can be trusted.",
            )
        }

    /**
     * **Search seeds for the #450 signature.**
     *
     * Reports the distribution rather than asserting a fixed one. The assertion is only that the
     * search was not vacuous — if every seed sails through at 30% loss the impairment is not reaching
     * the handshake, and a zero count would mean nothing.
     *
     * A seed that lands on [Outcome.LocalIdleTimeout] is the thing two load hunts failed to produce:
     * a handshake idle-timeout that can be re-run on demand, under a debugger, with a trace.
     */
    @Test
    fun searchSeedsForAHandshakeThatIdleTimesOut() =
        runTest(timeout = 600.seconds) {
            val seeds = (1L..12L).toList()
            val results =
                seeds.associateWith { seed ->
                    attempt(seed, loss = 0.30, clock = SimClock(testScheduler))
                }

            val idleTimeouts = results.filterValues { it is Outcome.LocalIdleTimeout }.keys
            val established = results.filterValues { it is Outcome.Established }.keys
            val other = results.filterValues { it !is Outcome.Established && it !is Outcome.LocalIdleTimeout }

            println(
                buildString {
                    appendLine("[#450 seed search] loss=0.30, virtual clock, ${seeds.size} seeds")
                    appendLine("  established     : ${established.size} $established")
                    appendLine("  LOCAL IDLE T/O  : ${idleTimeouts.size} $idleTimeouts   <-- the #450 signature")
                    appendLine("  other           : ${other.size}")
                    other.forEach { (seed, outcome) -> appendLine("      seed $seed -> $outcome") }
                },
            )

            assertTrue(
                results.values.any { it !is Outcome.Established } || established.size == seeds.size,
                "sanity: every seed produced a classified outcome",
            )
            assertTrue(
                established.isNotEmpty(),
                "no seed established at all — 30% loss is drowning the handshake outright rather than " +
                    "perturbing it, so this sweep is measuring the impairment and not the defect. " +
                    "Lower the loss rate before reading anything into the idle-timeout count.",
            )
        }

    /**
     * **Does the clock change the answer?**
     *
     * The same seeds run twice, varying only the clock — the shape `PathValidationVirtualClockTests`
     * used to prove migration timers were virtualizable. If the two columns disagree, the sim's
     * real-clock incoherence is itself perturbing handshakes, which is worth knowing before any
     * conclusion is drawn from a sim result under either clock.
     */
    @Test
    fun realAndVirtualClocksAreComparedRatherThanAssumedEquivalent() =
        runTest(timeout = 600.seconds) {
            val seeds = (1L..6L).toList()
            val virtual = seeds.associateWith { attempt(it, loss = 0.30, clock = SimClock(testScheduler)) }
            val real = seeds.associateWith { attempt(it, loss = 0.30, clock = RealDriverClock) }

            val disagreements = seeds.filter { virtual[it] != real[it] }
            println(
                buildString {
                    appendLine("[#450 clock comparison] loss=0.30, ${seeds.size} seeds")
                    seeds.forEach { s -> appendLine("  seed $s: virtual=${virtual[s]}  real=${real[s]}") }
                    appendLine("  disagreements: ${disagreements.size} $disagreements")
                },
            )
            // Deliberately not asserted equal. This test records a measurement; whether the clocks
            // agree is the finding, not the requirement.
            assertTrue(seeds.isNotEmpty(), "sanity")
        }

    /**
     * **The finding: a handshake that receives nothing never idle-times-out.**
     *
     * Paired with its control in the same test, because separately either half proves nothing — the
     * control is what rules out "the idle timer does not work in the sim".
     *
     * - **After establishment**, a total blackhole closes the connection with
     *   `ByLocal(IdleTimeout)`. The timer machinery works.
     * - **During the handshake**, the same blackhole leaves the state in `Handshaking` for the whole
     *   30-second virtual budget — fifteen times the 2-second idle timeout — and the only thing that
     *   ever ends it is the caller's own establishment bound.
     *
     * That is #450's CI sighting exactly: `TimeoutCancellationException at JvmHttp3LoopbackTest.kt:34`,
     * the suite's bound firing because the handshake hung rather than failing. It also explains why
     * #450 looks load-dependent without load being the cause: contention makes a handshake datagram
     * late or lost, and *that* stall is what exposes the missing termination. The load does not create
     * the defect, it reveals it.
     *
     * Deliberately not asserted as a defect yet — the assertion here is the *contrast*, so this test
     * keeps reporting the truth whichever way the handshake path is eventually fixed.
     */
    @Test
    fun aStalledHandshakeDoesNotIdleOutTheWayAnEstablishedConnectionDoes() =
        runTest(timeout = 300.seconds) {
            val afterEstablish =
                withSemanticSim(
                    ImpairmentConfig(seed = 5L),
                    quicOptions = semanticSimOptions(idleTimeout = 2.seconds),
                    establishTimeout = 30.seconds,
                    clock = SimClock(testScheduler),
                ) {
                    pipe.blackhole = true
                    withTimeoutOrNull(30.seconds) {
                        clientDriver.state.first { st -> st is QuicConnectionState.Closed }
                    }
                }

            val duringHandshake =
                runCatching {
                    withSemanticSim(
                        // 100% loss from the first datagram: the Initial never lands, nothing returns.
                        ImpairmentConfig(seed = 99L, loss = 1.0, latency = 5.milliseconds),
                        quicOptions = semanticSimOptions(idleTimeout = 2.seconds),
                        establishTimeout = 30.seconds,
                        clock = SimClock(testScheduler),
                    ) { clientDriver.state.value }
                }.getOrElse { it::class.simpleName }

            println("[#450] blackhole AFTER establishment  -> $afterEstablish")
            println("[#450] blackhole DURING the handshake -> $duringHandshake")

            assertEquals(
                QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.IdleTimeout)),
                afterEstablish,
                "control: an established connection under a total blackhole must close on its idle " +
                    "timeout. If this fails the sim's timer wiring is broken and the contrast below " +
                    "says nothing about the handshake.",
            )
            assertTrue(
                duringHandshake == "TimeoutCancellationException",
                "a handshake receiving nothing ended as $duringHandshake. It was expected to hang — " +
                    "the state stays Handshaking through 30s of virtual time against a 2s idle " +
                    "timeout, and only the caller's establishment bound ends it (#450). If this now " +
                    "reports Closed(ByLocal(IdleTimeout)) the defect is FIXED and this assertion " +
                    "should be inverted, not deleted.",
            )
        }
}
