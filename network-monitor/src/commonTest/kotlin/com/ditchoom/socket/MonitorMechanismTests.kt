package com.ditchoom.socket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The portable half of the [MonitorMechanism] contract. The platform monitors assert their own value
 * where they can actually be constructed (see `AndroidStartupNetworkMonitorInstrumentedTest`,
 * `JvmNetworkMonitorMechanismTest`, `AppleNetworkMonitorReactiveTest`); this pins the shape every
 * platform depends on.
 */
class MonitorMechanismTests {
    @Test
    fun alwaysAvailableIsStaticNotSignalled() {
        // The distinction that matters: a consumer gating a feature on "will I be told when the network
        // changes" must be able to tell AlwaysAvailable apart from a real monitor. Reading only the
        // reported state cannot — AlwaysAvailable reports a routable network, which looks exactly like a
        // healthy reactive monitor right up until the network drops and nothing ever fires.
        assertEquals(MonitorMechanism.Static, NetworkMonitor.AlwaysAvailable.capability.mechanism)
    }

    @Test
    fun aMonitorThatDoesNotDeclareIsUnknownNotStatic() {
        // Source compatibility: a third-party monitor written before `capability` existed still compiles,
        // and its default must be the explicit "cannot tell you" — never Static, which would assert the
        // false and load-bearing claim that it never changes.
        val predatingCapability =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> = MutableStateFlow(NetworkState.Unknown)

                override fun close() {}
            }
        assertEquals(MonitorMechanism.Unknown, predatingCapability.capability.mechanism)
    }

    @Test
    fun scriptedMonitorReportsSignalledSoReactivityGatedFeaturesCanBeTested() {
        // Non-vacuity guard for every downstream test that drives a flap through ScriptedNetworkMonitor:
        // if the fake reported Polled or Static, a consumer that disables itself on a non-reactive
        // monitor (webrtc's IceRestartPolicy.OnNetworkChange) would sit out the whole scripted timeline
        // and the test would pass while proving nothing.
        val script =
            networkMonitorScript {
                after(FIVE_SECONDS) { state(NetworkState.Offline) }
            }
        assertEquals(MonitorMechanism.PlatformSignalled, ScriptedNetworkMonitor(script).capability.mechanism)
    }

    @Test
    fun polledCarriesItsIntervalSoLatencyIsKnowable() {
        // Polled is a data class, not an object, precisely so the consumer can compare the detection
        // latency against its own deadline (ICE consent is 30s; a 5s poll fits, a 60s poll does not).
        val polled: MonitorMechanism = MonitorMechanism.Polled(FIVE_SECONDS)
        assertTrue(polled is MonitorMechanism.Polled)
        assertEquals(FIVE_SECONDS, polled.interval)
    }

    private companion object {
        val FIVE_SECONDS = 5.seconds
    }
}
