package com.ditchoom.socket.quic

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * Test [DriverClock] that makes the driver's keepalive/idle timing **deterministic** — virtual elapsed
 * time the test controls plus a timer the test fires by hand, instead of the real monotonic clock and
 * `onTimeout`. This is the Tier-1 seam: the keepalive decision (PING vs. hand the timer to quiche vs. do
 * nothing) was previously only reachable through multi-second wall-clock tests; here it's an exact,
 * race-free assertion that runs in microseconds.
 *
 * Usage:
 * ```
 * val clock = ManualDriverClock()
 * val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
 * driver.start(this)
 * clock.advance(1.seconds)   // move time forward AND fire the armed timer, in one race-free step
 * assertEquals(1, api.ackElicitingCount)
 * ```
 *
 * The fire channel is RENDEZVOUS, so [advance] suspends until the driver's `select` has taken the tick —
 * on return the driver has entered the "a timer fired" branch, so there is no settle delay to tune.
 *
 * Lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest` because the driver suites that
 * use it do: `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, and this
 * directory is `srcDir`'d into both, so one copy of the double serves jvm/apple/linux *and* the device
 * lane. It is `internal`, so each compilation gets its own — there is no cross-source-set leak. See
 * DitchOoM/socket#390.
 */
internal class ManualDriverClock : DriverClock {
    private var elapsed: Duration = Duration.ZERO

    /** RENDEZVOUS: a send blocks until the driver's `select` is parked and takes the tick. */
    private val ticks = Channel<Unit>(Channel.RENDEZVOUS)

    /** One token per arm, i.e. each time the loop is about to park in `select`. UNLIMITED so it never drops;
     *  [advance] waits for the *post-fire* re-arm to know the branch fully ran. */
    private val rearmed = Channel<Unit>(Channel.UNLIMITED)

    /** RENDEZVOUS: a send blocks until the driver is parked inside [withBound] and takes the stall. */
    private val stalls = Channel<Unit>(Channel.RENDEZVOUS)

    /** Set once the driver's very first arm token (emitted before any fire) has been consumed. */
    private var initialArmConsumed = false

    override fun markNow(): TimeMark {
        val origin = elapsed
        return object : TimeMark {
            override fun elapsedNow(): Duration = elapsed - origin
        }
    }

    override fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    ) {
        // Ignore [wait]: the test decides when the timer fires. The token announces "armed & about to park";
        // selecting on the rendezvous yields the loop's "a timer fired" sentinel (null) when advance() fires.
        rearmed.trySend(Unit)
        with(builder) { ticks.onReceive { null } }
    }

    /**
     * Runs [block] until it finishes **or** the test fires [stall], ignoring [wait] — for the same
     * reason [armTimeout] ignores it: in this tier the test decides when time passes, and a real
     * `withTimeoutOrNull` here would be a live multi-second wall-clock timer inside a suite whose whole
     * claim is that it has none.
     *
     * Ignoring [wait] must not mean losing the backstop, though. The driver's send bound is what keeps
     * a wedged datapath from parking the whole loop; a clock that quietly dropped it would let a
     * Tier-1 test hang on exactly the defect the bound exists to prevent. So it stays firable by hand,
     * like the timer — [stall] is to [withBound] what [advance] is to [armTimeout].
     */
    override suspend fun <T> withBound(
        wait: Duration,
        block: suspend () -> T,
    ): T? =
        coroutineScope {
            val work = async { block() }
            select {
                work.onAwait { it }
                stalls.onReceive {
                    work.cancel()
                    null
                }
            }
        }

    /**
     * Fire the send bound the driver is currently waiting under, as [withBound] would have on a real
     * clock. RENDEZVOUS like [advance]'s tick, so this suspends until the driver is genuinely parked
     * inside a send — a test cannot fire a bound that is not being awaited and get a false pass.
     */
    suspend fun stall() {
        stalls.send(Unit)
    }

    /**
     * Advance virtual time by [by], then fire the driver's currently-armed timer and **wait until the
     * driver has fully processed the fire** (its timer branch ran and it has looped back to re-arm). On
     * return the timing branch's effect — a keepalive PING, a `connOnTimeout`, or nothing — is observable,
     * so the assertion that follows is race-free.
     *
     * Requires the loop to keep a timer armed (`wait != null`): a keepalive-enabled established connection,
     * or a non-null `connTimeout` on the stub. If a fire makes `wait` become null the driver won't re-arm
     * and this will suspend until the run's timeout.
     */
    suspend fun advance(by: Duration) {
        consumeInitialArm()
        elapsed += by
        ticks.send(Unit) // rendezvous: driver is parked, takes the tick, runs the branch, then re-arms
        rearmed.receive() // the re-arm — branch body has finished, its effect is now observable
    }

    /**
     * Consume the driver's very first arm token — the one emitted before any fire — exactly once, with a
     * **blocking** receive rather than a `tryReceive` drain. `armTimeout` does its `rearmed.trySend` *before*
     * the `select` actually parks, so a `tryReceive` drain on the test thread can run before that send and
     * miss the token; the stale token then pairs with the *next* fire's re-arm wait, returning before the
     * timer branch ran. A blocking receive cannot miss it — it simply waits for the trySend — which is what
     * makes [advance]/[fireExpectingNoRearm] race-free under scheduler pressure. (The driver arms exactly
     * once before the first fire, so there is never more than this one token to clear.)
     */
    private suspend fun consumeInitialArm() {
        if (!initialArmConsumed) {
            rearmed.receive()
            initialArmConsumed = true
        }
    }

    /**
     * Fire the armed timer **without** waiting for a re-arm — for a fire that *terminates* the loop (e.g. an
     * idle-close), after which the driver never parks again. Synchronise on the observable terminal effect
     * instead (e.g. `driver.state.first { it is Closed }`), not on a re-arm that will never come.
     *
     * ⚠️ Still awaits the **tick** handoff, which is a rendezvous: it resumes only once the driver's timer
     * branch reaches its next suspension point. If that branch parks — the shape a wedged send produces,
     * where the branch suspends inside [withBound] and stays there — this call deadlocks against the very
     * condition the test is setting up. Fire it from its own coroutine in that case
     * (`val firing = async { clock.fireExpectingNoRearm(d) }`) and synchronise on the effect.
     * Measured while writing `SendStallBoundTests`: inline hangs, detached does not.
     */
    suspend fun fireExpectingNoRearm(by: Duration) {
        consumeInitialArm()
        elapsed += by
        ticks.send(Unit)
    }
}
