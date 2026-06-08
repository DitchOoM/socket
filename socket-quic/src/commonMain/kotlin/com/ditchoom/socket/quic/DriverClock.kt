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
}
