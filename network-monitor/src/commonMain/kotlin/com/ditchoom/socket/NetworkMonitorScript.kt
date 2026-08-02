package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlin.time.Duration

/**
 * A deterministic timeline of [NetworkState] transitions a [ScriptedNetworkMonitor] plays out — the
 * hermetic substitute for waiting on a real platform monitor's callbacks. It is the input half of the
 * network-observation record/replay loop (RFC_UNIFIED_NETWORK_TEST_HARNESS §7): a
 * `com.ditchoom.socket.testkit.NetworkMonitorRecorder` captures a real monitor's emissions as
 * `TraceEvent.Net` + one `TraceEvent.NetCapability`, and those replay back through a script built from
 * the same events (`networkMonitorScriptFromTrace`, in `:socket-testkit`).
 *
 * A script is a declared [capability], a starting [initialState], and an ordered list of timed
 * [Transition]s. Because a state and its identity are now **one value**, a transition is one concept —
 * there is no `Availability`/`Network` pair that could disagree, and therefore no way to script the
 * torn read that motivated RFC_NETWORK_REACHABILITY §1.2.
 *
 * Construction enforces every invariant, so an impossible timeline fails at the point it is written
 * rather than misleading a test that passes:
 *  - [Transition.at] offsets are non-negative and non-decreasing, so a built script plays in the order
 *    it reads.
 *  - Every state — [initialState] included — must be one the declared [capability] could actually have
 *    produced ([ReachResolution.permits]). Scripting `Routable(_, Confirmed)` against
 *    [ReachResolution.RouteOnly] is a bug in the fixture, and this is where it surfaces: in
 *    `commonTest`, under virtual time, on every platform, with no device.
 *  - A [MonitorMechanism.Static] monitor may not have transitions at all — that is what "static" means.
 *
 * Build one with the [networkMonitorScript] DSL, which appends in call order and stamps each transition
 * with the running offset.
 */
class NetworkMonitorScript(
    /**
     * What the scripted monitor declares it can observe. Every state in this script is checked against
     * its [MonitorCapability.resolution], so a consumer test is always written against a timeline some
     * real monitor could have produced.
     */
    val capability: MonitorCapability,
    /** The state the monitor reports until the first [Transition] fires. */
    val initialState: NetworkState,
    /** Timed transitions, non-decreasing in [Transition.at]; empty means "never changes". */
    val transitions: List<Transition>,
) {
    init {
        require(capability.resolution.permits(initialState)) {
            "initial state $initialState is not producible by a monitor declaring ${capability.resolution}"
        }
        if (capability.mechanism == MonitorMechanism.Static) {
            require(transitions.isEmpty()) {
                "a ${MonitorMechanism.Static} monitor cannot transition, but the script has ${transitions.size}"
            }
        }
        var previous = Duration.ZERO
        for ((index, transition) in transitions.withIndex()) {
            require(transition.at >= Duration.ZERO) {
                "transition[$index] at ${transition.at} is negative; offsets are measured from the start of playback"
            }
            require(transition.at >= previous) {
                "transition[$index] at ${transition.at} precedes transition[${index - 1}] at $previous; a script must be non-decreasing in time"
            }
            require(capability.resolution.permits(transition.state)) {
                "transition[$index] state ${transition.state} is not producible by a monitor declaring ${capability.resolution}"
            }
            previous = transition.at
        }
    }

    /** The total virtual duration of the script — the offset of its last transition, or zero if empty. */
    val duration: Duration get() = transitions.lastOrNull()?.at ?: Duration.ZERO

    /**
     * One scheduled change: at offset [at] from the start of playback, the monitor's
     * [state][NetworkMonitor.state] becomes [state].
     *
     * A flat data class rather than a sealed hierarchy, because collapsing availability and identity into
     * one [NetworkState] left exactly one kind of transition — a single-case sealed interface would be
     * ceremony with nothing to discriminate.
     */
    data class Transition(
        /** Offset from the start of playback (not a wall-clock instant). */
        val at: Duration,
        /** The state the monitor reports from [at] onwards. */
        val state: NetworkState,
    )

    companion object {
        /**
         * A script that reports [state] forever and never transitions. Defaults to the shape
         * [NetworkMonitor.AlwaysAvailable] reports, capability included, so the common "network is fine,
         * stop asking" fake is one call.
         */
        fun steady(
            state: NetworkState = NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved),
            capability: MonitorCapability = MonitorCapability(MonitorMechanism.Static, ReachResolution.Asserted),
        ): NetworkMonitorScript = NetworkMonitorScript(capability, state, emptyList())
    }
}

/**
 * Builds a [NetworkMonitorScript] in call order. Each `after(delay) { … }` advances the running offset
 * and the enclosed change lands at the accumulated instant, so a script reads as the sequence of events
 * it plays. Absolute offsets are also available via [NetworkMonitorScriptBuilder.stateAt], which may
 * not regress behind the running offset — the builder appends strictly in call order, so the script it
 * hands the [NetworkMonitorScript] constructor is exactly the timeline as written, never a re-sort of it.
 *
 * The captive-portal timeline from RFC_NETWORK_REACHABILITY §7.1 — a state no device in this repo's
 * test fleet can reproduce, and a deterministic `commonTest` here:
 * ```
 * val portalThenLogin = networkMonitorScript(
 *     capability = MonitorCapability(PlatformSignalled, RouteAndInternet),
 *     initialState = NetworkState.Unknown,
 * ) {
 *     after(0.seconds)        { state(Routable(wifi, Pending)) }
 *     after(800.milliseconds) { state(Routable(wifi, Blocked(CaptivePortal))) }
 *     after(30.seconds)       { state(Routable(wifi, Confirmed)) }   // user logs in
 * }
 * ```
 */
fun networkMonitorScript(
    capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet),
    initialState: NetworkState = NetworkState.Unknown,
    block: NetworkMonitorScriptBuilder.() -> Unit,
): NetworkMonitorScript {
    val builder = NetworkMonitorScriptBuilder().apply(block)
    return NetworkMonitorScript(capability, initialState, builder.build())
}

/**
 * DSL receiver for [networkMonitorScript]. Append order is guaranteed non-decreasing in time — [after]
 * only ever advances the running offset, and [stateAt] rejects an offset behind it — so [build] hands
 * the transitions to [NetworkMonitorScript] as written; the constructor's ordering `require` cannot
 * fire on this path and remains the guarantee for hand-built lists. Not thread-safe; build a script
 * from a single coroutine.
 */
class NetworkMonitorScriptBuilder internal constructor() {
    private val transitions = mutableListOf<NetworkMonitorScript.Transition>()
    private var cursor: Duration = Duration.ZERO

    /**
     * Advances the running offset by [delay], then records the change in [block] at the new instant. The
     * window receiver exposes only the one change verb, so a transition can never be scheduled at an
     * ambiguous time.
     */
    fun after(
        delay: Duration,
        block: TransitionWindow.() -> Unit,
    ) {
        require(delay >= Duration.ZERO) { "after() delay $delay is negative" }
        cursor += delay
        TransitionWindow(cursor).apply(block)
    }

    /**
     * Records a state change at the absolute offset [at], which must not precede the running offset —
     * scheduling backwards is rejected here, not silently re-sorted into place. Rejecting in the builder
     * gives a precise call-site error; the [NetworkMonitorScript] constructor's non-decreasing `require`
     * never fires on the DSL path and remains the guarantee for hand-built lists. Either way a script
     * always plays exactly as it reads. Advances the running offset to [at], so a subsequent [after] is
     * relative to this instant.
     */
    fun stateAt(
        at: Duration,
        state: NetworkState,
    ) {
        require(at >= cursor) {
            "stateAt($at) precedes the running offset $cursor; offsets must be scheduled in non-decreasing order"
        }
        transitions += NetworkMonitorScript.Transition(at, state)
        cursor = at
    }

    internal fun build(): List<NetworkMonitorScript.Transition> = transitions.toList()

    /** The change verb available inside an [after] window, landing at the window's instant. */
    inner class TransitionWindow internal constructor(
        private val at: Duration,
    ) {
        /** At this window's instant, the monitor's [state][NetworkMonitor.state] becomes [state]. */
        fun state(state: NetworkState) {
            transitions += NetworkMonitorScript.Transition(at, state)
        }
    }
}
