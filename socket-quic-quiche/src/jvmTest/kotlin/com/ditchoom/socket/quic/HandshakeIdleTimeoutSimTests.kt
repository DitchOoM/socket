package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.sim.SimClockChoice
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
 * drop, duplicate, reorder slot, jitter fraction — is a pure function of one seed. Under the
 * scheduler's clock quiche's own PTO/idle timers are virtual too, so a scenario is reproducible rather
 * than merely repeatable, and a seed that fails, fails every time.
 *
 * ## The clock, and why these tests still name it
 *
 * Under `runTest` the sim's clock is the scheduler's by default since #497 — before that it was the
 * wall clock, and the sim's timing was not merely fast but **incoherent**: our `delay()`s fast-forwarded
 * on the test scheduler while quiche's timers read a wall clock that had barely moved, and that alone
 * flipped 4 of 6 handshake outcomes here (see
 * [theDefaultClockUnderRunTestAgreesWithTheExplicitVirtualClockOnEverySeed]). The tests below pass
 * [SimClockChoice.Virtual] anyway: their subject is a timer, and pinning the clock at the call site
 * turns a stray real dispatcher into a construction error instead of a different experiment.
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
        clock: SimClockChoice,
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
            val first = attempt(seed, loss = 0.30, clock = SimClockChoice.Virtual)
            val second = attempt(seed, loss = 0.30, clock = SimClockChoice.Virtual)

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
     *
     * **Read the count as a signature, not as a mechanism.** Seed 4's entry here has been bisected —
     * see [seed4IsTwoLostInitialsInsideATwoSecondIdleBudget_notALoopbackStall] — and it is two
     * consecutive losses of one Initial inside a 2s idle budget: correct RFC 9000 §10.1 behaviour, at
     * the binomial rate 30% loss implies, and it establishes under the 10s idle timeout
     * `Http3LoopbackTestSuite` actually uses. It reproduces #450's *report*, not #450.
     */
    @Test
    fun searchSeedsForAHandshakeThatIdleTimesOut() =
        runTest(timeout = 600.seconds) {
            val seeds = (1L..12L).toList()
            val results =
                seeds.associateWith { seed ->
                    attempt(seed, loss = 0.30, clock = SimClockChoice.Virtual)
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
     * **The #497 regression instrument: a sim built the default way under `runTest` runs on the same
     * clock as one that pins the scheduler's clock — on every seed.**
     *
     * This test used to compare the pinned virtual clock against the wall clock and record, without
     * asserting, that the two disagreed. The record (2026-08-28, `origin/main` = 91588acf, and again at
     * a0b70624 the day after):
     *
     * ```
     * seed 1: virtual=Established       real=Threw(kind=TimeoutCancellationException)
     * seed 2: virtual=Established       real=Threw(kind=TimeoutCancellationException)
     * seed 3: virtual=Established       real=Established
     * seed 4: virtual=LocalIdleTimeout  real=Threw(kind=TimeoutCancellationException)
     * seed 5: virtual=Established       real=Established
     * seed 6: virtual=Established       real=Threw(kind=TimeoutCancellationException)
     * disagreements: 4 [1, 2, 4, 6]
     * ```
     *
     * The mechanism, measured on seed 1: under the wall clock the driver armed quiche's timer 61 times
     * and quiche reported 985–998ms still to go to its first PTO on *every* arm, because only 11ms of
     * wall time had passed across all of them — while each arm's `onTimeout` advanced the scheduler by
     * that same ~995ms, so the 60s establishment bound expired without the lost Initial ever being
     * retransmitted. On the scheduler's clock the PTO fires at virtual 999ms and the handshake completes
     * at 1009ms. The "real" column was never a wall-clock result; it was the two sides of the FFI
     * disagreeing about how much time had passed.
     *
     * That column can no longer be produced: asking for the wall clock under `runTest` is a
     * construction error ([SemanticSimClockGuardTests]). What this test asserts instead is the property
     * the fix establishes — the default is the scheduler's clock — using the same six seeds, so the
     * flips above are exactly what it catches if the default ever silently reverts.
     *
     * **The pinned column is anchored to the recorded table, not just compared with the default.**
     * A mutation that put *both* drivers back on the wall clock regardless of the resolved choice made
     * the two columns agree with each other — every seed flipped identically, `disagreements: 0` — and
     * only the anchor caught it. Two arms that agree because both are broken is a harness trap this
     * repository has walked into before; the reference column has to be pinned to something outside
     * the comparison.
     */
    @Test
    fun theDefaultClockUnderRunTestAgreesWithTheExplicitVirtualClockOnEverySeed() =
        runTest(timeout = 600.seconds) {
            val seeds = (1L..6L).toList()
            val pinned = seeds.associateWith { attempt(it, loss = 0.30, clock = SimClockChoice.Virtual) }
            val default = seeds.associateWith { attempt(it, loss = 0.30, clock = SimClockChoice.OfCallingDispatcher) }

            val disagreements = seeds.filter { pinned[it] != default[it] }
            println(
                buildString {
                    appendLine("[#497 default-clock check] loss=0.30, ${seeds.size} seeds")
                    seeds.forEach { s -> appendLine("  seed $s: pinned-virtual=${pinned[s]}  default=${default[s]}") }
                    appendLine("  disagreements: ${disagreements.size} $disagreements")
                },
            )

            // The anchor: what these six seeds do on the scheduler's clock, as recorded in the KDoc
            // table and by searchSeedsForAHandshakeThatIdleTimesOut. Deterministic per seed
            // (theSameSeedGivesTheSameOutcomeUnderTheVirtualClock), so a change here means quiche or the
            // pipe changed — a finding to read, not a table to re-record blindly.
            val recordedUnderTheSchedulersClock =
                mapOf(
                    1L to Outcome.Established,
                    2L to Outcome.Established,
                    3L to Outcome.Established,
                    4L to Outcome.LocalIdleTimeout,
                    5L to Outcome.Established,
                    6L to Outcome.Established,
                )
            assertEquals(
                recordedUnderTheSchedulersClock,
                pinned,
                "the pinned-virtual column is the reference and it moved: either the virtual clock is no " +
                    "longer reaching quiche (the M1 shape: both columns flip together), or quiche/the pipe " +
                    "changed behaviour on these seeds",
            )
            assertEquals(
                pinned,
                default,
                "a sim built the default way under runTest must drive quiche from the scheduler's clock " +
                    "(#497); seeds $disagreements flipped outcome against the pinned virtual clock",
            )
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
                    clock = SimClockChoice.Virtual,
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
                    clock = SimClockChoice.Virtual,
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

    /** One attempt plus the pipe's own record of every datagram it carried and how it fared. */
    private data class TracedAttempt(
        val outcome: Outcome,
        val settledAtMs: Long,
        /** Every datagram offered to the pipe, its virtual instant relative to the first one. */
        val datagrams: List<Pair<Long, ImpairedPipe.Observation>>,
    ) {
        fun clientDatagramAt(index: Int): Long = datagrams.filter { it.second.side == "C" }[index].first
    }

    /**
     * One handshake attempt with the wire attached: which datagram each side put on the pipe, when,
     * and whether the pipe delivered it. [forceDrop] overrides the seeded fate of a datagram by index
     * *without* changing the RNG draw sequence, so a single decision can be pinned in isolation.
     */
    private suspend fun TestScope.tracedAttempt(
        seed: Long,
        loss: Double,
        idleTimeout: kotlin.time.Duration,
        forceDrop: Set<Int> = emptySet(),
    ): TracedAttempt =
        withSemanticSim(
            ImpairmentConfig(seed = seed, loss = loss, latency = 5.milliseconds, forceDrop = forceDrop),
            quicOptions = semanticSimOptions(idleTimeout = idleTimeout),
            establishTimeout = 60.seconds,
            clock = SimClockChoice.Virtual,
            // The block IS the wait: a gate would consume the handshake before it can be watched.
            gate = EstablishmentGate.None,
        ) {
            val started = testScheduler.currentTime
            val settled =
                withTimeoutOrNull(60.seconds) {
                    clientDriver.state.first { it !is QuicConnectionState.Handshaking }
                }
            TracedAttempt(
                outcome = settled?.let(::classify) ?: Outcome.Threw("StillHandshaking"),
                settledAtMs = testScheduler.currentTime - started,
                datagrams = pipe.observations().map { (it.atMs - started) to it },
            )
        }

    /** QUIC v1 long header, packet type Initial: form=1, fixed=1, type=00 (RFC 9000 §17.2.2). */
    private fun ImpairedPipe.Observation.isInitial(): Boolean = (bytes[0].toInt() and 0xf0) == 0xc0

    /**
     * **What seed 4 actually is — and, decisively, what it is not.**
     *
     * [searchSeedsForAHandshakeThatIdleTimesOut] finds seed 4 producing `ByLocal(IdleTimeout)` during
     * the handshake, which is #450's *signature*. This test bisects that seed down to the single
     * decision behind it, and the answer is that it is not #450's *mechanism*:
     *
     * | drop script (loss = 0, so the RNG decides nothing) | idle 2s | idle 10s |
     * |---|---|---|
     * | nothing                          | Established, 10ms   | Established, 10ms   |
     * | the client's first Initial       | Established, 1009ms | Established, 1009ms |
     * | its first PTO retransmission     | Established, 1029ms | Established, 1029ms |
     * | **both**                         | **LocalIdleTimeout, 2997ms** | **Established, 3007ms** |
     *
     * So the trigger is exactly two consecutive losses of the *same* Initial — at 30% loss a
     * p = 0.09 event, and 1 of 12 seeds hitting it is the binomial rate, not a defect — and what turns
     * that into a close is the **2s idle timeout the sim picked**, not anything in the stack.
     *
     * The arithmetic is a photo finish, which is why it looks alarming and is not. quiche floors the
     * idle timeout at 3×PTO (RFC 9000 §10.1), so with PTO = 999ms the deadline is 2997ms; and §10.1
     * restarts the idle timer on a send only when *no other ack-eliciting packet has been sent since
     * the last receive*, so the 999ms retransmission does not push it out. Meanwhile the PTO backoff
     * schedules the third Initial at 999 + 1998 = **2997ms** — the same instant. The connection dies
     * one tick before the transmission that would have saved it, exactly as the RFC specifies.
     *
     * Raise the idle timeout to the 10s that `Http3LoopbackTestSuite` actually configures and the very
     * same two drops establish at 3007ms. **A loopback path loses nothing for 10s**, so this seed
     * cannot be, and does not model, the field defect in #450 / #367: it is the idle timer working.
     *
     * Kept as an assertion rather than deleted because the seed sweep above will keep reporting
     * `LOCAL IDLE T/O: 1 [4]` forever, and without this the next reader draws the same wrong
     * conclusion from it.
     */
    @Test
    fun seed4IsTwoLostInitialsInsideATwoSecondIdleBudget_notALoopbackStall() =
        runTest(timeout = 300.seconds) {
            val seeded = tracedAttempt(seed = 4L, loss = 0.30, idleTimeout = 2.seconds)
            val dropped = seeded.datagrams.filter { it.second.dropped }.map { it.second }

            println(
                buildString {
                    appendLine("[#450 seed 4] outcome=${seeded.outcome} at ${seeded.settledAtMs}ms")
                    seeded.datagrams.forEach { (at, o) ->
                        appendLine("  #${o.index} t=${at}ms ${o.side} ${if (o.dropped) "DROP" else "ok"} len=${o.len}")
                    }
                },
            )

            assertEquals(
                Outcome.LocalIdleTimeout,
                seeded.outcome,
                "seed 4 is the sweep's one idle-timeout seed; if that moved, quiche or the pipe changed",
            )
            assertEquals(
                2,
                seeded.datagrams.size,
                "seed 4 puts exactly two datagrams on the wire before it gives up: ${seeded.datagrams}",
            )
            assertEquals(2, dropped.size, "both of them are dropped — nothing ever reaches the server")
            assertTrue(
                dropped.all { it.side == "C" && it.isInitial() },
                "both losses are the CLIENT's Initial (the first and its PTO retransmission), not a " +
                    "server reply: ${dropped.map { it.side to it.len }}",
            )

            // Minimality, with the RNG taken out of the picture (loss = 0 + an explicit drop script):
            // either loss alone is survivable; it takes both.
            val first = tracedAttempt(4L, loss = 0.0, idleTimeout = 2.seconds, forceDrop = setOf(0))
            val second = tracedAttempt(4L, loss = 0.0, idleTimeout = 2.seconds, forceDrop = setOf(1))
            val both = tracedAttempt(4L, loss = 0.0, idleTimeout = 2.seconds, forceDrop = setOf(0, 1))
            println("[#450 seed 4] drop #0 -> $first")
            println("[#450 seed 4] drop #1 -> $second")
            println("[#450 seed 4] drop #0+#1 -> ${both.outcome} at ${both.settledAtMs}ms")

            assertEquals(Outcome.Established, first.outcome, "losing only the first Initial is survivable")
            assertEquals(Outcome.Established, second.outcome, "losing only the retransmission is survivable")
            assertEquals(
                Outcome.LocalIdleTimeout,
                both.outcome,
                "it takes BOTH losses — that is the whole of what seed 4 perturbs",
            )
            assertEquals(
                seeded.settledAtMs,
                both.settledAtMs,
                "the scripted pair reproduces the seeded run exactly, so the script IS the seed's mechanism",
            )

            // The load-bearing half: the same two losses under the idle timeout the loopback suite
            // actually configures. Nothing about the stack changes — only the budget.
            val atTenSeconds = tracedAttempt(4L, loss = 0.0, idleTimeout = 10.seconds, forceDrop = setOf(0, 1))
            println("[#450 seed 4] drop #0+#1 at idle=10s -> ${atTenSeconds.outcome} at ${atTenSeconds.settledAtMs}ms")

            assertEquals(
                Outcome.Established,
                atTenSeconds.outcome,
                "the identical drop script establishes at the 10s idle timeout Http3LoopbackTestSuite " +
                    "configures. Seed 4 is an idle BUDGET being exceeded, not a stall — so it does not " +
                    "model #450, whose loopback path loses nothing for 10s.",
            )
            assertEquals(
                both.settledAtMs,
                atTenSeconds.clientDatagramAt(2),
                "the photo finish: the third Initial leaves at exactly the instant the 2s-idle run gave " +
                    "up. One tick of budget separates seed 4 from a completed handshake.",
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
            clock = SimClockChoice.Virtual,
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
