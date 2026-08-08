package com.ditchoom.socket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract-level guarantees of the link-quality surface: absence is *declared* and *honest*. The
 * per-platform samplers are proven in their own suites (Android unit tests for the
 * `NetworkCapabilities.signalStrength` mapper, the Apple live test for the per-family split); what
 * belongs here is the shape every monitor shares — including monitors that never heard of the axis.
 */
class LinkQualityTests {
    @Test
    fun aMonitorPredatingTheAxisDeclaresNoneAndReportsUnavailable() {
        // Same stance as UndeclaredCapability and the observationCount default: an implementation
        // written before this property existed still compiles, and what it reports is the explicit
        // "never measures" declaration — a consumer can trust the gate without knowing the vintage.
        val undeclared =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> = MutableStateFlow(NetworkState.Unknown)

                override fun close() {}
            }
        assertEquals(LinkQualityResolution.None, undeclared.capability.linkQuality)
        assertEquals(LinkQuality.Unavailable, undeclared.linkQuality.value)
    }

    @Test
    fun alwaysAvailableNeverMeasuresQualityEither() {
        assertEquals(LinkQualityResolution.None, NetworkMonitor.AlwaysAvailable.capability.linkQuality)
        assertEquals(LinkQuality.Unavailable, NetworkMonitor.AlwaysAvailable.linkQuality.value)
    }

    @Test
    fun theTwoAxisCapabilityConstructorStillMeansNone() {
        // Every MonitorCapability written before the third axis (including recorded traces, which
        // decode with the two-argument constructor) must keep meaning what it meant: no quality
        // reporting. A default of Rssi would silently promise measurements no old monitor makes.
        assertEquals(
            LinkQualityResolution.None,
            MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteAndInternet).linkQuality,
        )
    }
}
