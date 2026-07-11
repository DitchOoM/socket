@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic.sim

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * The W2 event-timeline interpreter (RFC_DETERMINISTIC_SIMULATION.md §3): walks a sorted list of
 * [SimEvent]s, advances the **one** virtual clock between them (the test scheduler — which
 * [SimClock] also reads, so the driver's timers and the schedule can never skew), and injects each
 * event through the matching [SimHarness] seam.
 *
 * Events with equal timestamps keep their declaration order (`sortedBy` is stable). After each
 * injection the scheduler runs current tasks to a fixpoint, so the code under test fully reacts to
 * event N before event N+1 — the property that makes traces reproducible run-over-run.
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
            is SimEvent.DatagramIn -> harness.udp.deliver(event.payloadHex, event.from)
            is SimEvent.SendError -> harness.udp.injectSendError(event.error)
            is SimEvent.RecvError -> harness.udp.injectRecvError(event.error)
            is SimEvent.Availability -> harness.monitor.set(event.value)
            is SimEvent.Network -> harness.monitor.setNetworkId(event.id)
            is SimEvent.Liveness -> harness.liveness.script(event.result)
        }
    }
}

/**
 * A named, committed input timeline — the replayable unit (field capture, shrunk fuzz finding, or
 * the hand-written W2 goldens in `sim/fixtures/`). Pure inputs: driver configuration (keepalive
 * interval, scripted quiche timers) lives with the test that runs the fixture.
 */
internal class SimFixture(
    val name: String,
    val events: List<SimEvent>,
    val duration: Duration,
)

/**
 * Fixture DSL (spec form):
 * ```
 * val fixture =
 *     simFixture("name") {
 *         at(Duration.ZERO) datagramIn "c0ffee"
 *         at(12.milliseconds) network NetworkId.KindOnly(NetworkKind.Cellular)
 *         runFor(5.seconds)
 *     }
 * ```
 * The fixture's total duration is `max(runFor, last event time)` — the interpreter keeps advancing
 * virtual time to it after the last event, so pure-timer trajectories (keepalive, idle timeout)
 * need no events at all.
 */
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
        infix fun datagramIn(payloadHex: String) {
            builder.events += SimEvent.DatagramIn(t, payloadHex)
        }

        infix fun sendError(error: SimError) {
            builder.events += SimEvent.SendError(t, error)
        }

        infix fun recvError(error: SimError) {
            builder.events += SimEvent.RecvError(t, error)
        }

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
