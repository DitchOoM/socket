@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract [ObservationRelay] exists to make structural: [NetworkMonitor.observationCount] and
 * [NetworkMonitor.observations] advance together, once per platform observation, and the stream does
 * **not** conflate.
 */
class ObservationRelayTests {
    private val wifi: NetworkState = NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, handle = 1), InternetAccess.Unobserved)
    private val cellular: NetworkState =
        NetworkState.Routable(NetworkId.Link(NetworkKind.Cellular, handle = 2), InternetAccess.Unobserved)

    /**
     * The property a [kotlinx.coroutines.flow.StateFlow] cannot have, and the reason this type exists:
     * three observations that all fold to the value already published are three emissions, not zero.
     */
    @Test
    fun repeatedStatesAreNotConflated() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)

            val seen = mutableListOf<NetworkState>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            repeat(3) { relay.record() }
            runCurrent()

            assertEquals(listOf(wifi, wifi, wifi, wifi), seen, "the subscription value plus one per record")
        }

    /** A subscriber opens with the live current state, exactly as collecting the StateFlow would. */
    @Test
    fun aSubscriberOpensWithTheCurrentState() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)
            state.value = cellular // moved before anyone subscribed, and before any observation

            val seen = mutableListOf<NetworkState>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            assertEquals(
                listOf(cellular),
                seen,
                "the live state, not a replayed buffer that would still be empty here",
            )
        }

    /** [ObservationRelay.record] relays what the monitor published, so publish-then-record is the order. */
    @Test
    fun recordRelaysTheStateAsPublishedAtThatMoment() =
        runTest {
            val state = MutableStateFlow<NetworkState>(wifi)
            val relay = ObservationRelay(state)

            val seen = mutableListOf<NetworkState>()
            backgroundScope.launch { relay.observations.collect { seen += it } }
            runCurrent()

            state.value = cellular
            relay.record()
            runCurrent()

            assertEquals(listOf(wifi, cellular), seen)
        }

    /** The count is the half that must never drop, so it is asserted independently of the stream. */
    @Test
    fun countAdvancesOncePerRecordAndStartsAtZero() =
        runTest {
            val relay = ObservationRelay(MutableStateFlow(wifi))
            assertEquals(0L, relay.count.value)
            repeat(5) { relay.record() }
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
            repeat(100) { relay.record() }
            assertEquals(100L, relay.count.value, "a dropped stream entry must never cost a count")
        }
}
