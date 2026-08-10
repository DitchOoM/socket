package com.ditchoom.socket

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [JsNetworkMonitor] is the one monitor whose [MonitorMechanism] is decided at runtime, which makes it
 * the one that can get [NetworkMonitor.observations] wrong in a way no other backend can.
 *
 * Under Node it is [MonitorMechanism.Polled], so nothing ever calls `ObservationRelay.record` — a poll is
 * not an observation. Exposing the relay's stream here would therefore open with the current state and
 * then go silent for the monitor's whole lifetime, while [NetworkMonitor.state] kept reporting every
 * polled change. A recorder collecting `observations` would write one line and call the network quiet.
 *
 * Asserting the *variant* is what pins that down: [NetworkObservation.Unsequenced] can only come from the
 * `state`-derived default, and [NetworkObservation.Sequenced] only from the relay.
 */
class JsNetworkMonitorObservationsTests {
    @Test
    fun nodeReportsUnsequencedObservationsOverState() =
        runTest {
            if (!isNodeJsRuntime) return@runTest
            val monitor = JsNetworkMonitor()
            try {
                val first = monitor.observations.first()
                assertIs<NetworkObservation.Unsequenced>(
                    first,
                    "a Polled monitor must leave the density default, not expose a relay nothing records into",
                )
                assertEquals(monitor.state.value, first.state, "and it opens with the current state")
            } finally {
                monitor.close()
            }
        }

    /** The other half of the same contract, unchanged by the fix: a poll's cadence is not density. */
    @Test
    fun nodeNeverAdvancesTheObservationCount() =
        runTest {
            if (!isNodeJsRuntime) return@runTest
            val monitor = JsNetworkMonitor()
            try {
                assertEquals(0L, monitor.observationCount.value)
                assertTrue(monitor.capability.mechanism is MonitorMechanism.Polled)
            } finally {
                monitor.close()
            }
        }
}
