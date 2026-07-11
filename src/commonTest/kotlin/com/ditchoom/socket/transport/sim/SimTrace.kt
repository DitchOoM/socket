package com.ditchoom.socket.transport.sim

import com.ditchoom.socket.ConnectionState
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.transport.NetworkId
import kotlin.test.fail
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * One **observation** recorded while a transport-layer timeline ran — the root-module counterpart
 * of `com.ditchoom.socket.quic.sim.Observed` (see [SimEvent] for the copy/promotion rationale).
 */
internal sealed interface Observed {
    /** Virtual instant (offset from the run's t0) the observation was recorded at. */
    val at: Duration

    /**
     * A `ReconnectingConnection.state` transition, recorded as [describeConnectionState]'s rendering.
     * Rendered rather than the typed value ONLY because `ConnectionState.Disconnected` is an open
     * class carrying a `Throwable` (reference equality) — a typed golden could never compare equal
     * across runs. The rendering keeps the exception's *type* (`Disconnected(SocketIOException)`),
     * so the oracle still distinguishes failure classes; production error surfacing stays typed.
     */
    data class StateChange(
        override val at: Duration,
        val state: String,
    ) : Observed

    /** The connect factory ran (attempt [attempt], 1-based). */
    data class ConnectAttempt(
        override val at: Duration,
        val attempt: Int,
    ) : Observed

    /** A liveness probe ran and reported [result]. */
    data class LivenessProbed(
        override val at: Duration,
        val result: TransportLiveness.Result,
    ) : Observed

    /** The scripted monitor emitted a `networkId` change. */
    data class NetworkChanged(
        override val at: Duration,
        val id: NetworkId,
    ) : Observed

    /** The scripted monitor emitted an `availability` change. */
    data class AvailabilityChanged(
        override val at: Duration,
        val value: NetworkAvailability,
    ) : Observed
}

/** Stable, cross-run-comparable rendering of a [ConnectionState] for [Observed.StateChange]. */
internal fun describeConnectionState(state: ConnectionState): String =
    when (state) {
        is ConnectionState.Initialized -> "Initialized"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Disconnected -> "Disconnected(${state.t?.let { it::class.simpleName } ?: "clean"})"
    }

/**
 * The ordered observation trace of one transport-layer simulation run — copy of the quiche
 * module's `SimTrace` (see [SimEvent] for the promotion note). Timestamps are relative to the
 * run's t0 so consecutive runs inside one `runTest` compare equal.
 */
internal class SimTrace(
    private val clock: () -> Duration,
) {
    private val recorded = mutableListOf<Observed>()

    val events: List<Observed> get() = recorded

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
     * previous step's match; [TraceSequence.at] additionally pins the exact virtual timestamp.
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
