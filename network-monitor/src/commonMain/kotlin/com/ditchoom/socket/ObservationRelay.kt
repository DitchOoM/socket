package com.ditchoom.socket

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription

/**
 * The shared implementation of the two density surfaces a platform-signalled [NetworkMonitor] reports:
 * [NetworkMonitor.observationCount] and [NetworkMonitor.observations].
 *
 * It exists so the pair cannot drift. Both answer the same question — *how often is the platform
 * talking* — and a monitor that advanced one without the other would report a count no recorded trace
 * could reproduce, which is precisely the failure that left a recorded ride unable to carry density in
 * the first place. One [record] call advances both, so "exactly once per platform observation" is
 * structural rather than a discipline each monitor has to keep independently.
 *
 * [record] also *publishes* the state, rather than trusting each monitor to publish first and record
 * second. That ordering is not a style preference: the state and its sequence have to become visible as
 * one indivisible pair, or a subscriber arriving between the two writes opens with a state stamped by
 * the wrong sequence — see [observations].
 *
 * Public because implementing the pair correctly is not obvious, and because a monitor living outside
 * this module needs it: the JVM multi-release 21-tier is its own compilation unit, so an `internal`
 * helper could not reach the FFM monitor. A third-party [NetworkMonitor] gets the same guarantees by
 * holding one of these and routing every publication through it.
 *
 * ```
 * private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
 * override val state: StateFlow<NetworkState> = _state.asStateFlow()
 *
 * private val relay = ObservationRelay(_state)
 * override val observationCount: StateFlow<Long> = relay.count
 * override val observations: Flow<NetworkObservation> = relay.observations
 *
 * init { relay.publish(readSynchronously()) }          // a seed is not an observation
 * private fun onPlatformCallback() = relay.record(fold(...))
 * ```
 *
 * Every mutating entry point is called from one platform callback thread per monitor — an Android
 * `NetworkCallback` on its handler, an `NWPathMonitor` handler on its queue, a netlink or routing-socket
 * read loop, the JS event loop — so sequence assignment needs no lock. The only cross-thread read is a
 * subscriber taking [latest], which is a single reference read of an immutable pair.
 */
class ObservationRelay(
    /** The monitor's state, published *through* this relay so state and sequence advance together. */
    private val state: MutableStateFlow<NetworkState>,
) {
    /**
     * The last published state and the count as of that publication, as one immutable pair.
     *
     * This is what makes a subscriber's opening emission race-free. Reading the state and the count
     * separately cannot be made safe in either order: read the count first and an observation landing in
     * between yields the new state stamped with the old sequence (which then fails to suppress that
     * observation's own buffered emission, and the subscriber sees it twice); read the state first and
     * the same interleaving yields a *stale* state stamped with the new sequence, which suppresses the
     * real one. One reference read of a pair written once has neither failure.
     */
    private val latest = MutableStateFlow(NetworkObservation.Sequenced(state.value, ObservationSequence.None))

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
     * callback that has no coroutine context and must never block. A collector slower than a burst
     * therefore loses the oldest entries — but every survivor carries its [ObservationSequence], so the
     * loss is *detectable* ([ObservationSequence.droppedSince]) rather than silent, and [count] never
     * drops at all.
     */
    private val relayed =
        MutableSharedFlow<NetworkObservation.Sequenced>(
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
     * state synchronously. `onSubscription` reads [latest] after the subscription is registered but
     * before any emission can be missed, so a subscriber always opens with a real current value and
     * loses nothing in the gap.
     *
     * The sequence filter is what makes that gap exactly-once rather than at-least-once. An observation
     * landing between the subscription being registered and [latest] being read is *both* the value the
     * opening emission carries and a value already buffered for delivery; dropping anything at or below
     * the opening sequence suppresses the second copy. Without it the stream would report two
     * observations where [count] reports one — the very drift this type exists to prevent.
     */
    val observations: Flow<NetworkObservation> =
        relayed
            .onSubscription { emit(latest.value) }
            .strictlyAfterTheOpening()

    /**
     * Publish [newState] **without** counting it — the synchronous seed a monitor resolves before its
     * platform callbacks are wired, which is not the network talking and must not advance [count].
     *
     * Kept on the relay rather than left as a direct write to the state flow so that [latest] stays
     * consistent with what [NetworkMonitor.state] reports: a subscriber arriving before the first
     * observation must open with the seeded state, not with whatever the flow held when this relay was
     * constructed.
     */
    fun publish(newState: NetworkState) {
        state.value = newState
        latest.value = NetworkObservation.Sequenced(newState, latest.value.sequence)
    }

    /**
     * One platform observation: publishes [newState] and advances [count], emitting to [observations]
     * whether or not [newState] differs from what was published before.
     */
    fun record(newState: NetworkState) {
        val observation = NetworkObservation.Sequenced(newState, latest.value.sequence.next())
        state.value = newState
        latest.value = observation
        _count.value = observation.sequence.value
        relayed.tryEmit(observation)
    }

    /**
     * One platform observation that concluded nothing changed — the common case, and the one [state]
     * cannot show. Equivalent to re-recording the state already published.
     */
    fun recordUnchanged() {
        record(latest.value.state)
    }

    companion object {
        /**
         * Room for a flap burst between collector resumptions. Sized for the worst real capture seen
         * rather than a round number: an elevator departure delivers a few dozen callbacks in a second
         * or two, and a recorder writing to a sink resumes far faster than that. A burst that outruns it
         * still leaves a detectable gap in the sequence rather than a silent one.
         */
        const val OBSERVATION_BUFFER = 64
    }
}

/**
 * Takes the first emission as the opening one and passes only what strictly follows it — the filter that
 * makes a subscriber's opening gap exactly-once.
 *
 * Extracted from [ObservationRelay.observations] because the interleaving it defends against is not
 * reachable from a test: it needs a platform callback to land between a subscription being registered and
 * the relay's [ObservationRelay] snapshot being read, which no single-threaded scheduler can produce. As
 * an operator over an arbitrary upstream, the rule it enforces is directly assertable.
 */
internal fun Flow<NetworkObservation.Sequenced>.strictlyAfterTheOpening(): Flow<NetworkObservation> =
    flow {
        var opened = false
        var last = ObservationSequence.None
        collect { observation ->
            if (!opened || observation.sequence > last) {
                opened = true
                last = observation.sequence
                emit(observation)
            }
        }
    }
