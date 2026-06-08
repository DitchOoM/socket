package com.ditchoom.socket.quic

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectBuilder
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
 */
internal class ManualDriverClock : DriverClock {
    private var elapsed: Duration = Duration.ZERO

    /** RENDEZVOUS: a send blocks until the driver's `select` is parked and takes the tick. */
    private val ticks = Channel<Unit>(Channel.RENDEZVOUS)

    /** One token per arm, i.e. each time the loop is about to park in `select`. UNLIMITED so it never drops;
     *  [advance] drains stale tokens then waits for the *post-fire* re-arm to know the branch fully ran. */
    private val rearmed = Channel<Unit>(Channel.UNLIMITED)

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
        // Drop any stale "armed" tokens so the wait below pairs with the re-arm caused by THIS fire.
        while (rearmed.tryReceive().isSuccess) { /* drain */ }
        elapsed += by
        ticks.send(Unit) // rendezvous: driver is parked, takes the tick, runs the branch, then re-arms
        rearmed.receive() // the re-arm — branch body has finished, its effect is now observable
    }

    /**
     * Fire the armed timer **without** waiting for a re-arm — for a fire that *terminates* the loop (e.g. an
     * idle-close), after which the driver never parks again. Synchronise on the observable terminal effect
     * instead (e.g. `driver.state.first { it is Closed }`), not on a re-arm that will never come.
     */
    suspend fun fireExpectingNoRearm(by: Duration) {
        while (rearmed.tryReceive().isSuccess) { /* drain */ }
        elapsed += by
        ticks.send(Unit)
    }
}
