package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                // Client-only. With the server gate on, a client that closes with
                // ByLocal(IdleTimeout) — the outcome this sweep exists to count — was converted into
                // a TimeoutCancellationException by the eagerly-accepted server and classified Threw.
                gate = EstablishmentGate.ClientOnly,
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
     * **A stalled handshake DOES terminate, and it terminates the same way an idle connection does.**
     *
     * This test previously asserted the opposite, and was wrong. It asserted on a
     * `TimeoutCancellationException` from [withSemanticSim]'s *joint* establishment gate and never
     * read the client's state, so "the handshake hangs" was inferred rather than measured. What
     * actually hangs is the sim's eagerly-accepted server, which has no timers because it has never
     * sent or received — a state `SharedQuicheServer` cannot reach, since it only creates a conn from
     * a received Initial.
     *
     * The control was real but pointed at the wrong thing: it proved the timer machinery worked, while
     * the broken component was the gate. An instrument's negative space is only believable once the
     * instrument has been shown to report a known-true positive.
     *
     * quiche arms the idle timer on the first ack-eliciting **send** (RFC 9000 §10.1's restart clause),
     * so a client that sent an Initial into a blackhole closes at `max(idleTimeout, 3×PTO)` — which is
     * exactly the `local: IdleTimeout` in #450's local sighting.
     */
    @Test
    fun aStalledHandshakeTerminatesOnItsIdleTimeoutJustLikeAnIdleConnection() =
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
                withSemanticSim(
                    // 100% loss from the first datagram: the Initial never lands, nothing returns.
                    ImpairmentConfig(seed = 99L, loss = 1.0, latency = 5.milliseconds),
                    quicOptions = semanticSimOptions(idleTimeout = 2.seconds),
                    establishTimeout = 30.seconds,
                    clock = SimClock(testScheduler),
                    // The server never leaves Handshaking here — see [EstablishmentGate]. Waiting on
                    // it is what previously turned this measurement into a fabricated finding.
                    gate = EstablishmentGate.ClientOnly,
                ) { clientDriver.state.value }

            println("[#450] blackhole AFTER establishment  -> $afterEstablish")
            println("[#450] blackhole DURING the handshake -> $duringHandshake")

            val expected = QuicConnectionState.Closed(QuicCloseReason.ByLocal(QuicError.IdleTimeout))
            assertEquals(
                expected,
                afterEstablish,
                "control: an established connection under a total blackhole closes on its idle timeout",
            )
            assertEquals(
                expected,
                duringHandshake,
                "a handshake receiving nothing must ALSO close on its idle timeout, and by the same " +
                    "typed reason — that is what #450's local sighting recorded. Measured directly " +
                    "from the client rather than inferred from an establishment gate, because " +
                    "inferring it from the gate is how this test previously reported the opposite.",
            )
        }

    /** How [QuicheDriver.awaitEstablished] ended one stalled handshake, with the virtual time it took. */
    private data class GateOutcome(
        val thrown: QuicCloseReason,
        val state: QuicConnectionState,
        val elapsed: kotlin.time.Duration,
    )

    /**
     * Drive [QuicheDriver.awaitEstablished] itself — the production gate every connect facade calls —
     * against a real quiche client whose Initials fall into a total blackhole, and report how it ended.
     * Virtual clock, so `elapsed` is exact rather than approximate.
     */
    private suspend fun TestScope.stalledHandshakeThrough(
        bound: kotlin.time.Duration,
        idleTimeout: kotlin.time.Duration,
    ): GateOutcome =
        withSemanticSim(
            // 100% loss from the first datagram. The path latency is immaterial under a blackhole —
            // nothing is ever delivered — but it is deliberately not zero: a timing fix measured at
            // RTT≈0 has been wrong here before, and this keeps the sim's clock model honest.
            ImpairmentConfig(seed = 99L, loss = 1.0, latency = 30.milliseconds),
            quicOptions = semanticSimOptions(idleTimeout = idleTimeout),
            establishTimeout = 60.seconds,
            clock = SimClock(testScheduler),
            // The block IS the wait: the gate must not consume the handshake before the driver's own
            // bound gets to observe it.
            gate = EstablishmentGate.None,
        ) {
            val started = testScheduler.currentTime
            val failure = assertFailsWith<QuicCloseException> { clientDriver.awaitEstablished(bound) }
            GateOutcome(
                thrown = failure.closeReason,
                state = clientDriver.state.value,
                elapsed = (testScheduler.currentTime - started).milliseconds,
            )
        }

    /**
     * **The retitled #480: which timer ends a stalled handshake, and what it reports.**
     *
     * Two timers race a handshake that receives nothing — quiche's idle timer and the caller's
     * establishment bound — and whichever is shorter must end it, *typed*, at its own deadline:
     *
     * | shorter timer | reason                          | ends at            |
     * |---|---|---|
     * | the idle timeout (2s vs a 30s bound) | `ByLocal(IdleTimeout)`     | quiche's idle deadline |
     * | the caller's bound (5s vs a 30s idle) | `ByLocal(HandshakeTimeout(5s))` | exactly the bound |
     *
     * The first row is the control and already held; it is what proves the timer machinery works and
     * that this fix leaves the idle path alone. The second row is the production shape — the default
     * idle timeout is 30s and connect bounds are 15s — and it used to end as the caller's bare
     * `TimeoutCancellationException` with the connection torn down as `Closed(Unspecified)`: no typed
     * reason on either channel, and a `CancellationException` that a `launch` completes *cancelled* on
     * rather than failed. The bound is still the caller's; what changes is that it now closes the
     * connection the way every other establishment failure does.
     */
    @Test
    fun theShorterOfTheIdleTimeoutAndTheCallersBoundEndsAStalledHandshakeTyped() =
        runTest(timeout = 300.seconds) {
            val idleShorter = stalledHandshakeThrough(bound = 30.seconds, idleTimeout = 2.seconds)
            val boundShorter = stalledHandshakeThrough(bound = 5.seconds, idleTimeout = 30.seconds)

            println("[#480] idle 2s  < bound 30s -> $idleShorter")
            println("[#480] bound 5s < idle 30s  -> $boundShorter")

            // Control: the idle timer still owns the close when it is the shorter one, and it fires
            // before the bound. (quiche floors the idle timeout at 3×PTO — RFC 9000 §10.1 — so the
            // exact instant is quiche's, not ours; only its ordering against the bound is asserted.)
            val idle = QuicCloseReason.ByLocal(QuicError.IdleTimeout)
            assertEquals(idle, idleShorter.thrown, "control: awaitEstablished reports quiche's idle close typed")
            assertEquals(QuicConnectionState.Closed(idle), idleShorter.state, "control: the state channel agrees")
            assertTrue(
                idleShorter.elapsed >= 2.seconds && idleShorter.elapsed < 30.seconds,
                "control: the idle timer (not the 30s bound) ended it, at ${idleShorter.elapsed}",
            )

            // The fix: the bound ends it, typed, at exactly the bound — not at the idle timeout, and
            // not as the caller's cancellation.
            val bound = QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(5.seconds))
            assertEquals(
                bound,
                boundShorter.thrown,
                "a handshake stalled past the caller's bound must fail with the bound as a typed local " +
                    "reason — this is the retitled #480",
            )
            assertEquals(
                QuicConnectionState.Closed(bound),
                boundShorter.state,
                "the connection must be CLOSED with that same reason, not left Handshaking or torn down " +
                    "as Unspecified: the state channel is the single source of truth for the reason",
            )
            assertEquals(
                5.seconds,
                boundShorter.elapsed,
                "the caller's bound is still the caller's: it fires at exactly the bound, not at the idle timeout",
            )
        }
}
