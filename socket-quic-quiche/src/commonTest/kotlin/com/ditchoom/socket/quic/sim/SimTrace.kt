package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.PathInfo
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.transport.NetworkId
import kotlin.test.fail
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * One **observation** the harness recorded while a timeline ran (RFC_DETERMINISTIC_SIMULATION.md §2:
 * observations are never injected — they are the golden trajectory replay must walk again).
 *
 * All variants are value types with structural equality, so two runs' traces compare with `==`
 * (the determinism bar) — except [ErrorSurfaced] carrying a `QuicError.PlatformError`, whose
 * `Throwable` payload compares by reference; none of the W2 fixtures produce one.
 */
internal sealed interface Observed {
    /** Virtual instant (offset from the run's t0) the observation was recorded at. */
    val at: Duration

    /** The driver sent a UDP datagram ([len] bytes) at the `UdpChannel` seam. */
    data class DatagramOut(
        override val at: Duration,
        val len: Int,
    ) : Observed

    /** The driver fed an inbound packet ([len] bytes) to quiche (`connRecv`). */
    data class DatagramFed(
        override val at: Duration,
        val len: Int,
    ) : Observed

    /** The driver scheduled a reactive-keepalive PING (`connSendAckEliciting`). */
    data class KeepAlivePing(
        override val at: Duration,
    ) : Observed

    /** A `QuicheDriver.state` transition (including the initial value at t0). */
    data class StateChange(
        override val at: Duration,
        val state: QuicConnectionState,
    ) : Observed

    /** A `QuicheDriver.pathState` transition (initial dormant value not recorded). */
    data class PathStateChange(
        override val at: Duration,
        val info: PathInfo,
    ) : Observed

    /** A typed close reason surfaced on the terminal `Closed` state. */
    data class ErrorSurfaced(
        override val at: Duration,
        val error: QuicError,
    ) : Observed

    /** A liveness probe ran and reported [result]. */
    data class LivenessProbed(
        override val at: Duration,
        val result: TransportLiveness.Result,
    ) : Observed

    /** The scripted monitor emitted a `networkId` change (recorded per RFC §5 item 2). */
    data class NetworkChanged(
        override val at: Duration,
        val id: NetworkId,
    ) : Observed

    /** The scripted monitor emitted an `availability` change (recorded per RFC §5 item 2). */
    data class AvailabilityChanged(
        override val at: Duration,
        val value: NetworkAvailability,
    ) : Observed
}

/**
 * The ordered observation trace of one simulation run — the W2 trace oracle. Timestamps are
 * relative to the run's t0 (the [clock] closure subtracts it), so traces from consecutive runs
 * inside one `runTest` compare equal when the behaviour is identical.
 */
internal class SimTrace(
    private val clock: () -> Duration,
) {
    private val recorded = mutableListOf<Observed>()

    /** The observations recorded so far, in order. */
    val events: List<Observed> get() = recorded

    /** Current virtual time, relative to this run's t0. */
    fun now(): Duration = clock()

    fun record(event: Observed) {
        recorded += event
    }

    /** Golden comparison: the full trace must equal [expected], with a readable first-divergence diff. */
    fun assertMatches(expected: List<Observed>) {
        if (recorded == expected) return
        var diverge = 0
        while (diverge < recorded.size && diverge < expected.size && recorded[diverge] == expected[diverge]) {
            diverge++
        }
        fail(
            buildString {
                appendLine("trace diverges from golden at index $diverge:")
                appendLine("  expected: ${expected.getOrNull(diverge) ?: "<end of golden (${expected.size} events)>"}")
                appendLine("  actual:   ${recorded.getOrNull(diverge) ?: "<end of trace (${recorded.size} events)>"}")
                appendLine("golden (${expected.size} events):")
                expected.forEach { appendLine("  $it") }
                append(render())
            },
        )
    }

    /** Golden comparison against another run's trace. */
    fun assertMatches(expected: SimTrace) = assertMatches(expected.events)

    /**
     * Fluent **subsequence** matcher: each step must be satisfied by a later trace event than the
     * previous step's match. [TraceSequence.at] additionally pins the matching event's exact virtual
     * timestamp; [TraceSequence.anyTime] matches at any instant.
     */
    fun assertSequence(block: TraceSequence.() -> Unit) {
        val steps = TraceSequence().apply(block).steps
        var cursor = 0
        for ((index, step) in steps.withIndex()) {
            var matched = false
            while (cursor < recorded.size) {
                val candidate = recorded[cursor]
                cursor++
                if ((step.exactAt == null || candidate.at == step.exactAt) && step.predicate(candidate)) {
                    matched = true
                    break
                }
            }
            if (!matched) {
                val where = step.exactAt?.let { " @ $it" } ?: ""
                fail("assertSequence step $index (\"${step.description}\"$where) not matched by any remaining trace event\n${render()}")
            }
        }
    }

    fun render(): String =
        buildString {
            appendLine("actual trace (${recorded.size} events):")
            recorded.forEach { appendLine("  $it") }
        }
}

/** Builder for [SimTrace.assertSequence]. */
internal class TraceSequence {
    internal class Step(
        val exactAt: Duration?,
        val description: String,
        val predicate: (Observed) -> Boolean,
    )

    internal val steps = mutableListOf<Step>()

    /** Expect a matching event recorded at exactly virtual instant [t]. */
    fun at(
        t: Duration,
        description: String,
        predicate: (Observed) -> Boolean,
    ) {
        steps += Step(t, description, predicate)
    }

    /** Expect a matching event at any instant (after the previous step's match). */
    fun anyTime(
        description: String,
        predicate: (Observed) -> Boolean,
    ) {
        steps += Step(null, description, predicate)
    }
}
