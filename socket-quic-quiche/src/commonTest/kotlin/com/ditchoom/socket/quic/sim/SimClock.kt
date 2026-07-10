@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.quic.DriverClock
import com.ditchoom.socket.quic.QuicheCmd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark

/**
 * The TestClock bridge of RFC_DETERMINISTIC_SIMULATION.md §3: a [DriverClock] whose **both**
 * couplings read the kotlinx-coroutines-test virtual clock, so the driver's keepalive/idle timing
 * and the interpreter's event schedule share one time source with zero skew.
 *
 * ## W2 finding: `ManualDriverClock`'s rendezvous protocol is NOT needed under virtual time.
 *
 * `ManualDriverClock` replaces `onTimeout` with a RENDEZVOUS tick channel plus a re-arm token so
 * that, on a REAL multi-threaded dispatcher, `advance()` can hand-fire the timer race-free and
 * return only after the driver's timing branch fully ran. Under `runTest` with the W1
 * `driverContext = EmptyCoroutineContext` seam, the entire driver loop runs single-threaded on the
 * [TestCoroutineScheduler]: `advanceTimeBy(d)` + `runCurrent()` executes the armed `onTimeout`
 * clause AND the whole timing-branch body to its next suspension before returning to the test, so
 * every assertion that follows is already race-free — no rendezvous, no re-arm token. W1's
 * `virtualTime_realClock_keepAliveRunsOnVirtualTime` proved the arm side; [armTimeout] is therefore
 * the plain production clause, byte-identical to `RealDriverClock`.
 *
 * What `RealDriverClock` gets WRONG under virtual time — and the reason this class must exist — is
 * [markNow]: its monotonic mark measures *wall-clock* elapsed, which stays ~0 while the scheduler
 * fast-forwards, so the keepalive deadline (`keepAliveInterval - lastActivity.elapsedNow()`) never
 * shrinks across non-keepalive wakes. With a quiche timer shorter than the keepalive interval the
 * PING silently never fires (see `SimClockTests.realClock_markNowSkew_documentsWhySimClockExists`).
 * Reading [TestCoroutineScheduler.currentTime] here removes that skew — this one method is the
 * load-bearing half of the bridge.
 */
internal class SimClock(
    private val scheduler: TestCoroutineScheduler,
) : DriverClock {
    override fun markNow(): TimeMark {
        val origin = scheduler.currentTime
        return object : TimeMark {
            override fun elapsedNow(): Duration = (scheduler.currentTime - origin).milliseconds
        }
    }

    override fun armTimeout(
        builder: SelectBuilder<QuicheCmd?>,
        wait: Duration,
    ) {
        // Plain production clause: under runTest the select's onTimeout is natively a virtual-time
        // delay on the test scheduler (W1 seam). See the class KDoc for why no rendezvous is needed.
        with(builder) { onTimeout(wait) { null } }
    }
}
