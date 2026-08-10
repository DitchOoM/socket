@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract [ObservationRelay] exists to make structural: [NetworkMonitor.observationCount] and
 * [NetworkMonitor.observations] advance together, once per platform observation; the stream does **not**
 * conflate; and every emission carries the sequence that makes a lossy timeline self-reporting.
 */
class ObservationRelayTests {
    private val wifi: NetworkState = NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, handle = 1), InternetAccess.Unobserved)
    private val cellular: NetworkState =
        NetworkState.Routable(NetworkId.Link(NetworkKind.Cellular, handle = 2), InternetAccess.Unobserved)

    /**
     * The property a [kotlinx.coroutines.flow.StateFlow] cannot have, and the reason this type exists:
     * three observations that all fold to the value already published are three emissions, not zero —
     * and their sequences say so even though their states are identical.
     */
    @Test
    fun repeatedStatesAreNotConflated() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)

            val seen = mutableListOf<NetworkObservation>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            repeat(3) { relay.record(wifi) }
            runCurrent()

            assertEquals(
                listOf(0L, 1L, 2L, 3L),
                seen.map { (it as NetworkObservation.Sequenced).sequence.value },
                "the subscription value plus one per record, each with its own sequence",
            )
            assertEquals(List(4) { wifi }, seen.map { it.state }, "every one folded to the same state")
        }

    /** A subscriber opens with the live current state, exactly as collecting the StateFlow would. */
    @Test
    fun aSubscriberOpensWithTheCurrentState() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)
            relay.publish(cellular) // moved before anyone subscribed, and before any observation

            val seen = mutableListOf<NetworkObservation>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            assertEquals(
                listOf<NetworkObservation>(NetworkObservation.Sequenced(cellular, ObservationSequence.None)),
                seen,
                "the live state, not a replayed buffer that would still be empty here",
            )
        }

    /**
     * [ObservationRelay.record] publishes and stamps as one step, so a subscriber can never observe a
     * state paired with the wrong sequence — the reason [ObservationRelay] owns the state flow.
     */
    @Test
    fun recordPublishesTheStateItStamps() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)

            val seen = mutableListOf<NetworkObservation>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            relay.record(cellular)
            runCurrent()

            assertEquals(cellular, state.value, "record publishes")
            assertEquals(
                listOf<NetworkObservation>(
                    NetworkObservation.Sequenced(wifi, ObservationSequence.None),
                    NetworkObservation.Sequenced(cellular, ObservationSequence(1)),
                ),
                seen,
            )
        }

    /**
     * A seed is not the network talking: it moves [NetworkMonitor.state] and leaves the count alone, so a
     * monitor that resolves synchronously before wiring its callbacks still reports zero observations.
     */
    @Test
    fun publishMovesTheStateWithoutCountingAnObservation() =
        runTest {
            val state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
            val relay = ObservationRelay(state)

            relay.publish(wifi)

            assertEquals(wifi, state.value)
            assertEquals(0L, relay.count.value, "a seed is not an observation")
        }

    /** An observation that concluded nothing changed still counts — the case [state] cannot show. */
    @Test
    fun recordUnchangedCountsWithoutChangingTheState() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)

            val seen = mutableListOf<NetworkObservation>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            relay.recordUnchanged()
            runCurrent()

            assertEquals(wifi, state.value)
            assertEquals(1L, relay.count.value)
            assertEquals(listOf(wifi, wifi), seen.map { it.state })
        }

    /** The count is the half that must never drop, so it is asserted independently of the stream. */
    @Test
    fun countAdvancesOncePerRecordAndStartsAtZero() =
        runTest {
            val relay = ObservationRelay(MutableStateFlow(wifi))
            assertEquals(0L, relay.count.value)
            repeat(5) { relay.record(wifi) }
            assertEquals(5L, relay.count.value)
        }

    /**
     * The count is honest even with **no subscriber at all** — density is reported to a consumer
     * sampling the rate whether or not anything is recording the timeline.
     */
    @Test
    fun countAdvancesWithNoCollector() =
        runTest {
            val relay = ObservationRelay(MutableStateFlow(wifi))
            repeat(100) { relay.record(wifi) }
            assertEquals(100L, relay.count.value, "a dropped stream entry must never cost a count")
        }

    /**
     * A late subscriber opens at the sequence reached so far and continues from it without repeating it.
     *
     * This is the observable half of the subscription-gap contract: the opening emission and the stream's
     * own emissions are stamped from one monotonic sequence, which is what lets a subscriber arriving
     * *during* an observation suppress the second copy rather than report two where the count reports one.
     */
    @Test
    fun aLateSubscriberOpensAtTheSequenceReachedAndDoesNotRepeatIt() =
        runTest {
            val relay = ObservationRelay(MutableStateFlow<NetworkState>(wifi))
            repeat(3) { relay.record(wifi) }

            val seen = mutableListOf<NetworkObservation>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            relay.record(cellular)
            runCurrent()

            assertEquals(
                listOf(3L, 4L),
                seen.map { (it as NetworkObservation.Sequenced).sequence.value },
                "opens at 3 (not 0, and not a replay of 1..3), then continues",
            )
        }
}

/**
 * The subscription-gap filter, tested directly because the interleaving it exists for cannot be produced
 * from a test: it needs a platform callback landing between a subscription being registered and the
 * relay's snapshot being read.
 */
class StrictlyAfterTheOpeningTests {
    private val wifi: NetworkState = NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, handle = 1), InternetAccess.Unobserved)

    private fun at(sequence: Long) = NetworkObservation.Sequenced(wifi, ObservationSequence(sequence))

    /**
     * The race in miniature: the opening emission carries observation 5, and 5 is *also* already buffered
     * because it landed during the subscription gap. One observation must reach the collector once —
     * otherwise the stream reports two where [NetworkMonitor.observationCount] reports one.
     */
    @Test
    fun theObservationDuplicatedByTheSubscriptionGapIsDeliveredOnce() =
        runTest {
            val seen = flowOf(at(5), at(5), at(6)).strictlyAfterTheOpening().toList()
            assertEquals(listOf(5L, 6L), seen.map { (it as NetworkObservation.Sequenced).sequence.value })
        }

    /** Anything at or below the opening is a replay of what the opening already carried. */
    @Test
    fun emissionsBelowTheOpeningAreDropped() =
        runTest {
            val seen = flowOf(at(9), at(3), at(9), at(10)).strictlyAfterTheOpening().toList()
            assertEquals(listOf(9L, 10L), seen.map { (it as NetworkObservation.Sequenced).sequence.value })
        }

    /** The common case — no gap, nothing suppressed, repeats of the same *state* still all pass. */
    @Test
    fun anIntactStreamPassesThroughUntouched() =
        runTest {
            val seen = flowOf(at(0), at(1), at(2), at(3)).strictlyAfterTheOpening().toList()
            assertEquals(listOf(0L, 1L, 2L, 3L), seen.map { (it as NetworkObservation.Sequenced).sequence.value })
        }
}

/** Pure arithmetic, asserted away from any flow — the off-by-one a recorder would otherwise carry. */
class ObservationSequenceTests {
    @Test
    fun anIntactStreamReportsNoDrops() {
        assertEquals(0L, ObservationSequence(1).droppedSince(ObservationSequence.None))
        assertEquals(0L, ObservationSequence(9).droppedSince(ObservationSequence(8)))
    }

    @Test
    fun aGapReportsExactlyTheObservationsThatWentMissing() {
        assertEquals(4L, ObservationSequence(5).droppedSince(ObservationSequence.None))
        assertEquals(36L, ObservationSequence(100).droppedSince(ObservationSequence(63)))
    }

    @Test
    fun nextIsTheStampTheFollowingObservationCarries() {
        assertEquals(ObservationSequence(1), ObservationSequence.None.next())
        assertEquals(0L, ObservationSequence.None.value, "no observation yet is a real count, not a sentinel")
    }
}
