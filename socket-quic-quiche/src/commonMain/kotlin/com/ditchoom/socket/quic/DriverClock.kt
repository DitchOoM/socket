package com.ditchoom.socket.quic

import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.selects.onTimeout
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The [QuicheDriver]'s two couplings to wall-clock time, isolated behind one seam so a test can drive
 * both deterministically **without** giving up the real `Dispatchers.Default` I/O the reactive loop
 * relies on:
 *
 *  - [markNow] — the monotonic mark the keepalive deadline is measured from (`lastActivity`); and
 *  - [armTimeout] — the `select` clause that wakes the loop when the next quiche/keepalive timer is due.
 *
 * Production uses [RealDriverClock]: monotonic time and `onTimeout`, i.e. exactly the behaviour the
 * driver had before the seam existed. A test clock can return a controllable [TimeMark] and replace the
 * timeout with a manually-fired rendezvous, turning the keepalive/idle timing path — previously only
 * reachable through multi-second wall-clock integration tests — into exact, race-free assertions. See
 * `ManualDriverClock` in commonTest.
 */
interface DriverClock {
    /** A fresh mark for measuring elapsed inactivity. Production: `TimeSource.Monotonic.markNow()`. */
    fun markNow(): TimeMark

    /**
     * Register the driver's timer on [builder] — the in-progress `select<QuicheCmd?>` that also races the
     * command channel. The registered clause MUST produce `null` (the loop's "a timer fired" sentinel)
     * when [wait] is due. Production registers `onTimeout(wait) { null }`; a manual clock can instead
     * select on a rendezvous channel the test fires by hand, ignoring [wait].
     */
    fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    )

    /**
     * The time to push into quiche's C library **immediately before** each connection operation, so
     * quiche's own internal `Instant::now()` reads (loss/PTO/RTT/pacing/congestion) see the same clock
     * the rest of the driver does. This is the Kotlin half of the caller-clock patch (RFC §6.1): the
     * patched libquiche routes all 72 internal clock reads through a per-thread virtual clock that this
     * value drives via [QuicheApi.setThreadVirtualTimeNanos].
     *
     * Production returns [DriverTime.Real] — nothing is pushed, quiche keeps its own wall clock, and the
     * decorator that would sync it is never even installed (zero cost, zero behaviour change). A
     * Tier-A simulation clock returns [DriverTime.Virtual] carrying the current virtual-time reading so
     * quiche becomes fully caller-clocked and loss/PTO/timeout scenarios go bit-exact.
     */
    fun quicheTime(): DriverTime = DriverTime.Real
}

/**
 * Which clock quiche's C library should read for a connection operation. A sealed choice, not a nullable
 * `Long?`, so "use the real wall clock" (production) and "use this exact virtual instant" (sim) are two
 * distinct, exhaustively-handled cases — never an overloaded sentinel.
 */
sealed interface DriverTime {
    /** Production: quiche keeps its own internal `Instant::now()`; nothing is injected. */
    object Real : DriverTime

    /**
     * Simulation: quiche's internal clock is pinned to [nanos] — a monotonic reading in nanoseconds
     * measured from libquiche's fixed per-process anchor (absolute value is irrelevant; quiche only ever
     * subtracts two readings). Pushed via [QuicheApi.setThreadVirtualTimeNanos] on the same thread and in
     * the same synchronous frame as the quiche call, so no thread hop or virtual-time advance can slip
     * between the push and the read.
     */
    data class Virtual(val nanos: Long) : DriverTime
}

/** Production clock: monotonic time + coroutine `onTimeout`. Behaviour-identical to the pre-seam loop. */
object RealDriverClock : DriverClock {
    override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()

    override fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    ) {
        with(builder) { onTimeout(wait) { null } }
    }

    override fun quicheTime(): DriverTime = DriverTime.Real
}
