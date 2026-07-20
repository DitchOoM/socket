package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlin.time.Duration

/**
 * A deterministic timeline of network transitions a [ScriptedNetworkMonitor] plays out — the hermetic
 * substitute for waiting on a real platform monitor's callbacks. It is the input half of the
 * network-observation record/replay loop (RFC_UNIFIED_NETWORK_TEST_HARNESS §7): a
 * `com.ditchoom.socket.testkit.NetworkMonitorRecorder` captures a real monitor's emissions as
 * `TraceEvent.NetAvail`/`TraceEvent.Net`, and those replay back through a script built from the same
 * events (`NetworkMonitorScript.fromTrace`, in `:socket-testkit`).
 *
 * A script is a starting state ([initialAvailability] + [initialNetworkId], the values the monitor
 * reports before its first scheduled change) plus an ordered list of timed [Transition]s. Sealed and
 * value-typed with **no bare strings and no nulls** — every state is a distinct [NetworkAvailability]
 * or [NetworkId] the same way the platform monitors report them.
 *
 * Construction enforces the schedule invariants (no impossible states): [Transition.at] offsets are
 * non-negative and non-decreasing, so a built script always plays in the order it reads. Build one
 * with the [networkMonitorScript] DSL, which appends in call order and stamps each transition with
 * the running offset.
 */
class NetworkMonitorScript(
    /** The availability the monitor reports until the first [Transition.Availability] fires. */
    val initialAvailability: NetworkAvailability,
    /** The identity the monitor reports until the first [Transition.Network] fires. */
    val initialNetworkId: NetworkId,
    /** Timed transitions, non-decreasing in [Transition.at]; empty means "never changes". */
    val transitions: List<Transition>,
) {
    init {
        var previous = Duration.ZERO
        for ((index, transition) in transitions.withIndex()) {
            require(transition.at >= Duration.ZERO) {
                "transition[$index] at ${transition.at} is negative; offsets are measured from the start of playback"
            }
            require(transition.at >= previous) {
                "transition[$index] at ${transition.at} precedes transition[${index - 1}] at $previous; a script must be non-decreasing in time"
            }
            previous = transition.at
        }
    }

    /** The total virtual duration of the script — the offset of its last transition, or zero if empty. */
    val duration: Duration get() = transitions.lastOrNull()?.at ?: Duration.ZERO

    /**
     * One scheduled change, fired at offset [at] from the start of playback. Sealed so the two axes a
     * monitor exposes — [NetworkAvailability] and [NetworkId] — stay distinct types, never a nullable
     * pair.
     */
    sealed interface Transition {
        /** Offset from the start of playback (not a wall-clock instant). */
        val at: Duration

        /** At [at], the monitor's `availability` becomes [value]. */
        data class Availability(
            override val at: Duration,
            val value: NetworkAvailability,
        ) : Transition

        /** At [at], the monitor's `networkId` becomes [id]. */
        data class Network(
            override val at: Duration,
            val id: NetworkId,
        ) : Transition
    }

    companion object {
        /** An empty script that reports [availability]/[networkId] forever and never transitions. */
        fun steady(
            availability: NetworkAvailability = NetworkAvailability.AVAILABLE,
            networkId: NetworkId = NetworkId.Unidentified,
        ): NetworkMonitorScript = NetworkMonitorScript(availability, networkId, emptyList())
    }
}

/**
 * Builds a [NetworkMonitorScript] in call order. Each `after(delay) { … }` advances the running offset
 * and the enclosed change lands at the accumulated instant, so a script reads as the sequence of
 * events it plays. Absolute offsets are also available via [availabilityAt]/[networkIdAt].
 *
 * ```
 * val script = networkMonitorScript(initialNetworkId = wifi) {
 *     after(1.seconds) { networkId(cellular) }   // Wi-Fi → cellular flap at t=1s
 *     after(500.milliseconds) { availability(UNAVAILABLE) }   // then a drop at t=1.5s
 * }
 * ```
 */
fun networkMonitorScript(
    initialAvailability: NetworkAvailability = NetworkAvailability.AVAILABLE,
    initialNetworkId: NetworkId = NetworkId.Unidentified,
    block: NetworkMonitorScriptBuilder.() -> Unit,
): NetworkMonitorScript {
    val builder = NetworkMonitorScriptBuilder().apply(block)
    return NetworkMonitorScript(initialAvailability, initialNetworkId, builder.build())
}

/** DSL receiver for [networkMonitorScript]. Not thread-safe; build a script from a single coroutine. */
class NetworkMonitorScriptBuilder internal constructor() {
    private val transitions = mutableListOf<NetworkMonitorScript.Transition>()
    private var cursor: Duration = Duration.ZERO

    /**
     * Advances the running offset by [delay], then records the change(s) in [block] at the new instant.
     * The window receiver only exposes the two change verbs so a transition can never be scheduled at an
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

    /** Records an availability change at the absolute offset [at]. */
    fun availabilityAt(
        at: Duration,
        value: NetworkAvailability,
    ) {
        transitions += NetworkMonitorScript.Transition.Availability(at, value)
        cursor = maxOf(cursor, at)
    }

    /** Records a networkId change at the absolute offset [at]. */
    fun networkIdAt(
        at: Duration,
        id: NetworkId,
    ) {
        transitions += NetworkMonitorScript.Transition.Network(at, id)
        cursor = maxOf(cursor, at)
    }

    internal fun build(): List<NetworkMonitorScript.Transition> = transitions.sortedBy { it.at }

    /** The change verbs available inside an [after] window, all landing at the window's instant. */
    inner class TransitionWindow internal constructor(
        private val at: Duration,
    ) {
        /** At this window's instant, the monitor's `availability` becomes [value]. */
        fun availability(value: NetworkAvailability) {
            transitions += NetworkMonitorScript.Transition.Availability(at, value)
        }

        /** At this window's instant, the monitor's `networkId` becomes [id]. */
        fun networkId(id: NetworkId) {
            transitions += NetworkMonitorScript.Transition.Network(at, id)
        }
    }
}
