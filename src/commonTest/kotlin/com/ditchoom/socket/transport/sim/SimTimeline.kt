@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.transport.sim

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.transport.MockNetworkMonitor
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * Transport-layer copy of the W2 timeline interpreter — same mechanics as
 * `com.ditchoom.socket.quic.sim.SimTimeline` (see [SimEvent] for the copy/promotion rationale):
 * sort events, advance the one virtual clock between them, inject each through its seam, and run
 * the scheduler to a fixpoint after every injection so run-over-run traces are identical.
 */
internal class SimTimeline(
    private val events: List<SimEvent>,
    private val runUntil: Duration = Duration.ZERO,
) {
    constructor(fixture: SimFixture) : this(fixture.events, fixture.duration)

    suspend fun TestScope.run(harness: SimHarness) {
        val t0 = testScheduler.currentTime.milliseconds
        for (event in events.sortedBy { it.at }) {
            advanceTo(t0 + event.at)
            inject(event, harness)
            testScheduler.runCurrent()
        }
        advanceTo(t0 + runUntil)
    }

    private fun TestScope.advanceTo(target: Duration) {
        val now = testScheduler.currentTime.milliseconds
        if (target > now) {
            testScheduler.advanceTimeBy(target - now)
            // advanceTimeBy runs tasks strictly BEFORE the target instant; run the ones AT it too.
            testScheduler.runCurrent()
        }
    }

    private fun inject(
        event: SimEvent,
        harness: SimHarness,
    ) {
        when (event) {
            is SimEvent.Availability -> harness.monitor.set(event.value)
            is SimEvent.Network -> harness.monitor.setNetworkId(event.id)
            is SimEvent.Liveness -> harness.liveness.script(event.result)
        }
    }
}

/**
 * The transport-layer seams a timeline injects through. Reuses the root module's existing
 * [MockNetworkMonitor] (the quiche engine carries its own copy — this module's stays canonical).
 */
internal class SimHarness(
    val monitor: MockNetworkMonitor,
    val liveness: SimLiveness,
    val trace: SimTrace,
)

/**
 * Scripted [TransportLiveness] (#222 seam): [SimEvent.Liveness] enqueues the outcome the **next**
 * probe reports; an unscripted probe reports [TransportLiveness.Result.Unknown]. Every probe is
 * recorded as [Observed.LivenessProbed].
 */
internal class SimLiveness(
    private val trace: SimTrace,
) : TransportLiveness {
    private val scripted = ArrayDeque<TransportLiveness.Result>()

    fun script(result: TransportLiveness.Result) {
        scripted.addLast(result)
    }

    override suspend fun probe(): TransportLiveness.Result {
        val result = scripted.removeFirstOrNull() ?: TransportLiveness.Result.Unknown
        trace.record(Observed.LivenessProbed(trace.now(), result))
        return result
    }
}

/** A named, committed input timeline — see the quiche engine's `SimFixture` for the full contract. */
internal class SimFixture(
    val name: String,
    val events: List<SimEvent>,
    val duration: Duration,
)

/** Fixture DSL — transport-layer verbs only; same shape as the quiche engine's `simFixture`. */
internal fun simFixture(
    name: String,
    block: SimFixtureBuilder.() -> Unit,
): SimFixture {
    val builder = SimFixtureBuilder().apply(block)
    val lastEventAt = builder.events.maxOfOrNull { it.at } ?: Duration.ZERO
    return SimFixture(name, builder.events.toList(), maxOf(builder.duration, lastEventAt))
}

internal class SimFixtureBuilder {
    internal val events = mutableListOf<SimEvent>()
    internal var duration: Duration = Duration.ZERO

    /** Schedule an event at virtual instant [t] via the returned scope's infix verbs. */
    fun at(t: Duration): At = At(t, this)

    /** Keep the simulation advancing until [total] past t0, even after the last event. */
    fun runFor(total: Duration) {
        duration = total
    }

    class At internal constructor(
        private val t: Duration,
        private val builder: SimFixtureBuilder,
    ) {
        infix fun availability(value: NetworkAvailability) {
            builder.events += SimEvent.Availability(t, value)
        }

        infix fun network(id: NetworkId) {
            builder.events += SimEvent.Network(t, id)
        }

        infix fun liveness(result: TransportLiveness.Result) {
            builder.events += SimEvent.Liveness(t, result)
        }
    }
}
