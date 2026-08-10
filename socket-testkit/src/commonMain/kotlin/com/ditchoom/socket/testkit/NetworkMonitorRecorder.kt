package com.ditchoom.socket.testkit

import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.MonitorMechanism
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkMonitorScript
import com.ditchoom.socket.NetworkObservation
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.ObservationSequence
import com.ditchoom.socket.ReachResolution
import com.ditchoom.socket.ScriptedNetworkMonitor
import com.ditchoom.socket.permits
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Records a live [NetworkMonitor]'s emissions as replayable [TraceEvent.Net] input events (preceded by
 * one [TraceEvent.NetCapability]) on a neutral [TraceSink] — the capture half of the network-observation
 * record/replay loop (RFC_UNIFIED_NETWORK_TEST_HARNESS §7). The recorded trace round-trips back into a
 * [NetworkMonitorScript] via [networkMonitorScriptFromTrace], so a flap seen once on a real device
 * replays deterministically through a [ScriptedNetworkMonitor] forever after.
 *
 * It lives in `:socket-testkit` rather than next to [ScriptedNetworkMonitor] in `:network-monitor`
 * because it needs [TraceSink], which sits one module downstream — a recorder in `:network-monitor`
 * would force that lean module to depend on the testkit (a dependency cycle). The scripted monitor and
 * the script value type stay upstream so production consumers get the fake without the recorder.
 *
 * All stamps are offsets from the recorder's origin, taken from [clock] — inject
 * `{ testScheduler.currentTime.milliseconds }` under `runTest` so a recording follows the same virtual
 * clock the rest of the harness runs on; the default is a monotonic wall clock for live capture.
 */
class NetworkMonitorRecorder(
    private val sink: TraceSink,
    private val clock: () -> Duration = monotonicClock(),
) {
    private val origin: Duration = clock()

    private fun now(): Duration = clock() - origin

    /** Record a `NetworkMonitor.state` observation at the current instant. */
    fun state(state: NetworkState) {
        emitObservation(state, dropped = 0)
    }

    /**
     * One observation, preceded by a [TraceEvent.NetGap] when [dropped] earlier observations were lost
     * before it. Both lines carry the **same** stamp: the gap is not an event that happened at its own
     * instant, it is a property of the observation that follows it, and one stamp keeps the recorded
     * stream monotonic without inventing a time for the losses.
     *
     * `dropped == 0` writes no gap line at all — an intact stream says nothing rather than claiming a
     * confirmed zero, which is what makes a trace recorded before gap lines existed and a gap-free one
     * recorded today mean the same thing on replay.
     */
    private fun emitObservation(
        state: NetworkState,
        dropped: Long,
    ) {
        val at = now()
        if (dropped > 0) sink.emit(TraceEvent.NetGap(at, dropped))
        sink.emit(TraceEvent.Net(at, state))
    }

    /** Record the monitor's [MonitorCapability] at the current instant (once — it never changes). */
    fun capability(capability: MonitorCapability) {
        sink.emit(TraceEvent.NetCapability(now(), capability))
    }

    /**
     * Records [monitor]'s [capability][NetworkMonitor.capability], then collects its
     * [observations][NetworkMonitor.observations] in [scope], recording every one — including the
     * current value delivered on subscription, which becomes the script's initial state on replay.
     * Returns the [Job]; cancel it (or [scope]) to stop recording.
     *
     * **One collector, deliberately.** The previous two-flow recorder launched one collector per flow,
     * so two independently-stamped streams interleaved by scheduling rather than by time, and the
     * 2026-07-29 device capture duly emitted an earlier availability line *after* a later identity line.
     * A single flow cannot do that: the stream is monotonic by construction, not by discipline
     * (RFC_NETWORK_REACHABILITY §1.2). That is why capturing density is a property of the *stream* the
     * monitor exposes rather than a second collector here — see [NetworkMonitor.observations]. The
     * [kotlinx.coroutines.flow.buffer] below is **not** a second collector: it is one operator in the
     * single chain, adding a queue between the monitor and this collector, and every emission still
     * reaches exactly one `collect` in one order.
     *
     * **Gaps are recorded, and they mean one thing.** This collector sits downstream of the relay's own
     * `DROP_OLDEST`/[64-slot][com.ditchoom.socket.ObservationRelay.OBSERVATION_BUFFER] buffer — the
     * buffer whose losses these gap lines exist to make visible. An unbounded downstream buffer keeps
     * the recorder from being a *second*, uninstrumented source of loss on top of it: a
     * [TraceEvent.NetGap] in a recorded trace then always means the platform burst outran the relay,
     * never that a sink write (disk, network) was momentarily slow. Gaps in a recorder's own trace
     * should be rare and real.
     *
     * The gap is computed from consecutive [NetworkObservation.Sequenced] emissions
     * ([ObservationSequence.droppedSince]); the opening emission establishes the baseline and can never
     * itself report one, since a subscriber arriving at sequence 37 lost nothing — it simply was not
     * there. [NetworkObservation.Unsequenced] emissions record their state and make **no** gap claim at
     * all, not even a confirmed zero, and do not move the baseline: a monitor with no sequence has
     * nothing to compute a gap from, and collapsing that into "dropped 0" would report a measurement
     * where there was none.
     *
     * **[observations][NetworkMonitor.observations], not [state][NetworkMonitor.state].** They differ
     * only in what they de-dupe, and that difference is the whole capture fidelity question. `state` is
     * a [kotlinx.coroutines.flow.StateFlow]: an observation folding to the value already published is
     * invisible to it, so a recorder driven by `state` writes nothing for a link flapping hard while
     * always resolving back to the same rung — the burst that most deserves recording. Collecting
     * `observations` preserves those repeats, [networkMonitorScriptFromTrace] turns each into its own
     * transition, and [com.ditchoom.socket.ScriptedNetworkMonitor] replays them as the platform chatter
     * they were, bumping [NetworkMonitor.observationCount] once each. A monitor that does not report
     * density defaults `observations` to `state`, so this is exactly the old behaviour there.
     */
    fun observe(
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            capability(monitor.capability)
            var opened = false
            var previous = ObservationSequence.None
            monitor.observations.buffer(Channel.UNLIMITED).collect { observation ->
                when (observation) {
                    is NetworkObservation.Sequenced -> {
                        val dropped = if (opened) observation.sequence.droppedSince(previous) else 0L
                        opened = true
                        previous = observation.sequence
                        emitObservation(observation.state, dropped)
                    }
                    is NetworkObservation.Unsequenced -> state(observation.state)
                }
            }
        }

    companion object {
        private fun monotonicClock(): () -> Duration {
            val mark = TimeSource.Monotonic.markNow()
            return { mark.elapsedNow() }
        }
    }
}

/**
 * Rebuilds the [NetworkMonitorScript] that a [NetworkMonitorRecorder] captured from a real monitor —
 * the inverse of recording, closing the record→fixture→replay loop for network observations.
 *
 * Only the network input events are consulted ([TraceEvent.Net], [TraceEvent.NetGap] and
 * [TraceEvent.NetCapability]); any other trace events are ignored, so a mixed QUIC/network trace can be
 * projected straight to a monitor script. The **first** recorded state is the script's initial state (a
 * monitor reports its current value the moment a recorder subscribes); every later one becomes a
 * transition at its recorded offset. With no recorded state at all the script sits at
 * [NetworkState.Unknown], matching a monitor that never reported.
 *
 * A [TraceEvent.NetGap] folds into the [TraceEvent.Net] that follows it, as that transition's
 * [NetworkMonitorScript.Transition.droppedBefore] — which is why the pairing is safe on a shared sink:
 * the fold walks only the network events, so any QUIC line written between the two by another tap is
 * skipped rather than breaking the association. Two shapes of gap have no transition to attach to and
 * are ignored, both deliberately rather than by accident:
 *  - a gap **before the first** [TraceEvent.Net]: that first state is the script's initial value, not a
 *    transition, and there is nothing before it for observations to have been lost from — a subscriber
 *    that opened at sequence 37 was simply not there earlier.
 *  - a **trailing** gap with no following [TraceEvent.Net]: the mark of a capture truncated mid-stream
 *    (the recorder's job cancelled, the sink cut off), where the observation the gap describes was never
 *    recorded. A truncated capture must still replay, so this degrades rather than throwing.
 *
 * A trace carrying no [TraceEvent.NetCapability] — a QUIC-only capture, or one taken before that line
 * existed — gets [weakestCapabilityFor], so replay still validates rather than silently trusting the
 * fixture author. A trace carrying no [TraceEvent.NetGap] at all — every trace recorded before gap lines
 * existed — parses exactly as it always did, every transition at `droppedBefore = 0`.
 */
fun networkMonitorScriptFromTrace(events: List<TraceEvent>): NetworkMonitorScript {
    val states = events.filterIsInstance<TraceEvent.Net>()
    val capability =
        events.filterIsInstance<TraceEvent.NetCapability>().firstOrNull()?.capability
            ?: weakestCapabilityFor(states.map { it.state })
    val transitions = mutableListOf<NetworkMonitorScript.Transition>()
    var opened = false
    var droppedBefore = 0L
    for (event in events) {
        when (event) {
            // Summed rather than overwritten: consecutive gap lines are not something the recorder
            // writes, but a concatenated or hand-edited trace can hold them, and their losses add up.
            is TraceEvent.NetGap -> droppedBefore += event.dropped
            is TraceEvent.Net -> {
                if (opened) transitions += NetworkMonitorScript.Transition(event.at, event.state, droppedBefore)
                opened = true
                droppedBefore = 0L
            }
            else -> Unit
        }
    }
    return NetworkMonitorScript(
        capability = capability,
        initialState = states.firstOrNull()?.state ?: NetworkState.Unknown,
        transitions = transitions,
    )
}

/**
 * The least capable [MonitorCapability] that could have produced every one of [states] — what a trace
 * with no recorded [TraceEvent.NetCapability] replays as.
 *
 * Deriving it beats defaulting to something permissive: the resolution is the weakest of
 * [ReachResolution.LinkOnly] / [RouteOnly][ReachResolution.RouteOnly] /
 * [RouteAndInternet][ReachResolution.RouteAndInternet] that [permits] the whole timeline, so the replay
 * still enforces the pairing rules on states it *can* judge. The mechanism is
 * [MonitorMechanism.Unknown], because a trace genuinely does not record whether the platform pushed or
 * the monitor polled. [ReachResolution.Asserted] is the last resort for a timeline no single real
 * resolution explains (a hand-edited fixture mixing observed and unobserved reachability).
 */
fun weakestCapabilityFor(states: List<NetworkState>): MonitorCapability {
    val resolution =
        listOf(
            ReachResolution.LinkOnly,
            ReachResolution.RouteOnly,
            ReachResolution.RouteAndInternet,
        ).firstOrNull { candidate -> states.all { candidate.permits(it) } }
            ?: ReachResolution.Asserted
    return MonitorCapability(MonitorMechanism.Unknown, resolution)
}
