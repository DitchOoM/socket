@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim.fuzz

import com.ditchoom.socket.SocketException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.TrackingBufferFactory
import com.ditchoom.socket.quic.sim.Observed
import com.ditchoom.socket.quic.sim.QuicSimRun
import com.ditchoom.socket.quic.sim.SimIoException
import com.ditchoom.socket.quic.sim.fixtures.SIM_IDLE_TIMEOUT
import com.ditchoom.socket.quic.sim.runQuicSim
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The W5 invariant harness (RFC_DETERMINISTIC_SIMULATION.md §6 item 2), Tier A: run one generated
 * timeline through the [runQuicSim] engine under **virtual time** and check properties that must
 * hold for EVERY timeline — no example assertions:
 *
 * 1. **No buffer leaks** — a fresh [TrackingBufferFactory] is the run's leaf factory; after the
 *    driver is destroyed every allocation must have been freed (the 150-created/0-freed class).
 * 2. **State-machine legality** — the observed [QuicConnectionState] sequence only takes
 *    transitions from the explicit `legalTransition` table (derived from the
 *    `QuicConnectionState` KDoc: Idle→Handshaking→Established→Draining/Closed, Handshaking→Closed,
 *    Established→Closed; Closed is terminal).
 * 3. **Typed errors only** — every surfaced close reason is a typed [QuicError]; a
 *    [QuicError.PlatformError] must wrap a typed transport fault ([SocketException] or the sim's
 *    own [SimIoException]), never a bare Exception or an error-string (standing directive).
 * 4. **Termination** — the run reaches quiescence: virtual time never overshoots the timeline
 *    horizon by more than [TERMINATION_GRACE] (a driver that keeps re-arming timers after teardown
 *    would drag `advanceUntilIdle` past it), and `runQuicSim` itself returned (a genuine hang is
 *    caught by the test's wall-clock timeout).
 * 5. **Determinism** — the same case replayed twice inside the same scope yields `==` traces
 *    (the W2 determinism bar, now enforced over arbitrary generated timelines).
 *
 * Returns the violation messages (empty = all invariants hold) plus the first run, so callers can
 * render the trace. Never throws on a violation — the shrinker needs the boolean signal.
 */
internal suspend fun TestScope.checkFuzzInvariants(case: FuzzCase): FuzzVerdict {
    val first = runOnce(case)
    val violations = first.violations.toMutableList()
    // Invariant 5 — replay determinism. Only meaningful when the first run itself was orderly;
    // shrinking chases the first four invariants with the second run's cost included, so the
    // check function is one shape for both callers.
    val second = runOnce(case)
    if (first.run.trace.events != second.run.trace.events) {
        val a = first.run.trace.events
        val b = second.run.trace.events
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        violations +=
            "determinism: replaying the identical timeline diverged at trace index $i " +
            "(run1=${a.getOrNull(i)}, run2=${b.getOrNull(i)})"
    }
    return FuzzVerdict(violations, first.run)
}

internal class FuzzVerdict(
    val violations: List<String>,
    val run: QuicSimRun,
) {
    val failed: Boolean get() = violations.isNotEmpty()
}

private class SingleRun(
    val run: QuicSimRun,
    val violations: List<String>,
)

private suspend fun TestScope.runOnce(case: FuzzCase): SingleRun {
    val violations = mutableListOf<String>()
    val tracking = TrackingBufferFactory()
    val startedAt = testScheduler.currentTime.milliseconds
    val run =
        runQuicSim(
            fixture = case.fixture,
            keepAliveInterval = case.keepAliveInterval,
            clientMode = true, // real reader loop — DatagramIn/RecvError events need a consumer
            bufferFactory = tracking,
        ) {
            // Idle timer armed and lethal, like the W2 goldens: without keepalive (per-case seeded)
            // a quiet 30 s stretch genuinely closes the connection with the typed IdleTimeout.
            connTimeout = SIM_IDLE_TIMEOUT
            closeOnTimeout = true
            timedOut = true
            // Send-pressure model: every packet fed to quiche and every keepalive PING arms one
            // outbound datagram on the next flush, so SendError events have real sends to kill
            // (the bare stub never emits, which would leave the send path dead code under fuzz).
            onConnRecv = { connSendOnce = FUZZ_DATAGRAM_LEN }
            onAckEliciting = { connSendOnce = FUZZ_DATAGRAM_LEN }
        }
    val elapsed = testScheduler.currentTime.milliseconds - startedAt

    // Invariant 4 — termination/quiescence.
    val horizon = case.fixture.duration + TERMINATION_GRACE
    if (elapsed > horizon) {
        violations +=
            "termination: run consumed $elapsed of virtual time — past the ${case.fixture.duration} horizon + $TERMINATION_GRACE grace"
    }

    // Invariant 1 — buffer accounting.
    try {
        tracking.assertNoLeaks()
    } catch (leak: AssertionError) {
        violations += "buffer-leak: ${leak.message}"
    }

    // Invariant 2 — state-machine legality.
    val states =
        run.trace.events
            .filterIsInstance<Observed.StateChange>()
            .map { it.state }
    states.firstOrNull()?.let { initial ->
        if (initial !is QuicConnectionState.Idle && initial !is QuicConnectionState.Handshaking) {
            violations += "state-machine: run started in $initial (must start Idle or Handshaking)"
        }
    }
    states.zipWithNext().forEach { (from, to) ->
        if (!legalTransition(from, to)) {
            violations += "state-machine: illegal transition $from -> $to"
        }
    }

    // Invariant 3 — typed errors only.
    run.trace.events.filterIsInstance<Observed.ErrorSurfaced>().forEach { surfaced ->
        val error = surfaced.error
        if (error is QuicError.PlatformError) {
            val cause = error.cause
            if (cause !is SocketException && cause !is SimIoException) {
                violations +=
                    "typed-errors: PlatformError wraps ${cause::class.simpleName ?: "an anonymous Throwable"} " +
                    "(\"${cause.message}\") — must be a SocketException/SimIoException, never a bare Exception"
            }
        }
        if (error is QuicError.InternalError && error.detail.isBlank()) {
            violations += "typed-errors: InternalError with a blank detail — an error-string in disguise"
        }
    }

    return SingleRun(run, violations)
}

/** The legal [QuicConnectionState] transitions (KDoc of `QuicConnectionState`). Closed is terminal. */
private fun legalTransition(
    from: QuicConnectionState,
    to: QuicConnectionState,
): Boolean =
    when (from) {
        is QuicConnectionState.Idle -> to is QuicConnectionState.Handshaking
        is QuicConnectionState.Handshaking -> to is QuicConnectionState.Established || to is QuicConnectionState.Closed
        is QuicConnectionState.Established -> to is QuicConnectionState.Draining || to is QuicConnectionState.Closed
        is QuicConnectionState.Draining -> to is QuicConnectionState.Closed
        is QuicConnectionState.Closed -> false
    }

/** Size the stub reports for each fuzz-armed outbound datagram (any positive length works; stable for traces). */
private const val FUZZ_DATAGRAM_LEN = 1200

/**
 * Virtual-time slack past the fixture horizon before a run counts as non-quiescent. The interpreter
 * itself stops advancing at the horizon; only post-destroy timer churn can move the clock further.
 */
private val TERMINATION_GRACE: Duration = 1.seconds
