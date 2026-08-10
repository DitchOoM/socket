package com.ditchoom.socket

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update

/**
 * The shared implementation of the two density surfaces a platform-signalled [NetworkMonitor] reports:
 * [NetworkMonitor.observationCount] and [NetworkMonitor.observations].
 *
 * It exists so the pair cannot drift. Both answer the same question — *how often is the platform
 * talking* — and a monitor that bumped one without the other would report a count no recorded trace
 * could reproduce, which is precisely the failure that made a recorded ride unable to carry density in
 * the first place. One [record] call advances both, so "exactly once per platform observation" is
 * structural rather than a discipline each monitor has to keep independently.
 *
 * Public because implementing the pair correctly is not obvious — the stream must not conflate, must
 * not block the platform callback, and must open with the current state — and because a monitor living
 * outside this module needs it: the JVM multi-release 21-tier is its own compilation unit, so an
 * `internal` helper could not reach the FFM monitor that most needs it. A third-party [NetworkMonitor]
 * gets the same guarantee by holding one of these and calling [record] once per platform observation.
 *
 * **Call [record] after publishing the folded state.** It relays `state.value`, so it must run once the
 * monitor has already applied whatever that observation concluded — including the common case where it
 * concluded nothing changed, which is the whole point of the stream. Recording before the publish would
 * relay the *previous* state and shift the timeline by one observation.
 *
 * ```
 * private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
 * override val state: StateFlow<NetworkState> = _state.asStateFlow()
 *
 * private val relay = ObservationRelay(_state)
 * override val observationCount: StateFlow<Long> = relay.count
 * override val observations: Flow<NetworkState> = relay.observations
 *
 * private fun onPlatformCallback() {
 *     _state.value = fold(...)   // publish first
 *     relay.record()             // then record
 * }
 * ```
 */
class ObservationRelay(
    /** The monitor's published state — read at [record] time, and replayed to each new subscriber. */
    private val state: StateFlow<NetworkState>,
) {
    private val _count = MutableStateFlow(0L)

    /** Backs [NetworkMonitor.observationCount] — monotonic, never reset. */
    val count: StateFlow<Long> = _count.asStateFlow()

    /**
     * Deliberately **not** a [StateFlow]: conflation is exactly what this stream exists to avoid. A
     * `StateFlow` drops an emission whose value equals the current one, which erases every observation
     * that folded to no visible change — the burst of platform chatter a weak radio produces before any
     * rung actually moves, and the only reason to expose observations as a stream at all.
     *
     * [BufferOverflow.DROP_OLDEST] keeps [record] non-suspending so it is safe to call from a platform
     * callback (an Android `NetworkCallback`, an `NWPathMonitor` handler, a netlink read loop) that has
     * no coroutine context and must never block. A collector slower than a burst therefore loses the
     * oldest *stream* entries — but [count] is a separate counter that never drops, so density is still
     * reported faithfully even when the timeline is lossy.
     */
    private val relayed =
        MutableSharedFlow<NetworkState>(
            extraBufferCapacity = OBSERVATION_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Backs [NetworkMonitor.observations]: the current state on subscription, then one emission per
     * platform observation.
     *
     * [onSubscription] rather than a `replay = 1` buffer, because the two differ exactly where it
     * matters. `replay = 1` would hand a late subscriber whatever was last *recorded* — nothing at all
     * before the first platform callback, which is the normal case for a monitor that just seeded its
     * state synchronously. `onSubscription` reads the live [state] after the subscription is registered
     * but before any emission can be missed, so a subscriber always opens with a real current value and
     * loses nothing in the gap. That is the [StateFlow]-shaped contract a recorder relies on to give a
     * trace its initial state.
     */
    val observations: Flow<NetworkState> = relayed.onSubscription { emit(state.value) }

    /**
     * One platform observation, folded and published. Advances [count] and relays the published state to
     * [observations] — whether or not that state differs from the previous one.
     */
    fun record() {
        _count.update { it + 1 }
        relayed.tryEmit(state.value)
    }

    companion object {
        /**
         * Room for a flap burst between collector resumptions. Sized for the worst real capture seen
         * rather than a round number: an elevator departure delivers a few dozen callbacks in a second
         * or two, and a recorder writing to a sink resumes far faster than that.
         */
        const val OBSERVATION_BUFFER = 64
    }
}
