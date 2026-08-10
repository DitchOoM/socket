package com.ditchoom.socket

import kotlin.jvm.JvmInline

/**
 * An observation's position in the monotonic count [NetworkMonitor.observationCount] reports — its
 * [value] is that same number, as of the moment the observation was published.
 *
 * A [value class][JvmInline] so it cannot be silently swapped with an unrelated [Long] at a call site,
 * exactly as [InterfaceIndex] guards an interface index: the [NetworkState] an observation carries can
 * itself hold a [NetworkId.Link.handle][com.ditchoom.socket.transport.NetworkId.Link.handle], which is
 * also a `Long`, and the two sit side by side in every [NetworkObservation.Sequenced].
 *
 * Never give a parameter of this type a default value — a defaulted value-class argument rides the
 * synthetic default-args bridge and boxes.
 */
@JvmInline
value class ObservationSequence(
    val value: Long,
) : Comparable<ObservationSequence> {
    override fun compareTo(other: ObservationSequence): Int = value.compareTo(other.value)

    /** The sequence the next observation is stamped with. */
    fun next(): ObservationSequence = ObservationSequence(value + 1)

    /**
     * How many observations were lost between [previous] and this one — `0` when the stream was intact.
     *
     * The gap is what makes a lossy capture *honest* rather than merely lossy: a subscriber slower than
     * a burst drops the oldest entries, and comparing sequences is the only way to know that happened at
     * all. Sequences are strictly increasing, so this is never negative for two observations taken from
     * the same stream in order.
     */
    fun droppedSince(previous: ObservationSequence): Long = value - previous.value - 1

    companion object {
        /**
         * No observation yet — what a monitor carries between construction and its first platform
         * callback.
         *
         * Not a sentinel: [NetworkMonitor.observationCount] genuinely starts at zero, so this is the
         * honest count at that moment, and [droppedSince] needs no special case for the first
         * observation (`ObservationSequence(1).droppedSince(None) == 0`).
         */
        val None = ObservationSequence(0)
    }
}

/**
 * One emission of [NetworkMonitor.observations]: a [NetworkState], and whether the monitor that
 * produced it is reporting observation density.
 *
 * Sealed rather than a nullable sequence, so "this monitor does not report density" and "this
 * observation's position is unknown" cannot collapse into the same value.
 */
sealed interface NetworkObservation {
    /** The state this observation folded to — for [Sequenced], including a fold that changed nothing. */
    val state: NetworkState

    /**
     * From a monitor that reports density: one per platform observation, carrying its [sequence].
     *
     * Consecutive emissions may hold an equal [state] — that is the whole point. A link flapping hard
     * while every evaluation folds back to the same rung is chatter that [NetworkMonitor.state] de-dupes
     * away, and it is the signal density exists to expose.
     */
    data class Sequenced(
        override val state: NetworkState,
        val sequence: ObservationSequence,
    ) : NetworkObservation

    /**
     * From a monitor that does not report density — every *visible* state change, and no claim about
     * how often the platform spoke.
     *
     * The honest degradation, and what [NetworkMonitor.observations] defaults to: a consumer still sees
     * every change [NetworkMonitor.state] shows, and simply cannot see the invisible ones. Monitors
     * declaring [MonitorMechanism.Polled] report this deliberately, for the same reason they leave
     * [NetworkMonitor.observationCount] at zero — a poll's cadence is configuration, not the network
     * talking.
     */
    data class Unsequenced(
        override val state: NetworkState,
    ) : NetworkObservation
}
