package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkMonitorDefaultsTest {
    @Test
    fun alwaysAvailableAssertsRoutabilityAndDeclaresThatItNeverLooked() {
        // It reports the optimistic rung so a consumer that just wants to opt out of monitoring proceeds…
        val state = NetworkMonitor.AlwaysAvailable.state.value
        assertEquals(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved), state)
        assertTrue(state.canRouteOffLink, "opting out of monitoring must not block connections")
        // …identity is the explicit "no cheap network identity" state, never null (RFC_TRANSPORT_FALLBACK §12)…
        assertEquals(NetworkId.Unidentified, state.networkId)
        // …and `Asserted` is how a consumer gating on reachability detects that it never measured anything.
        assertEquals(
            MonitorCapability(MonitorMechanism.Static, ReachResolution.Asserted),
            NetworkMonitor.AlwaysAvailable.capability,
        )
    }

    @Test
    fun anUndeclaredMonitorIsNeverOverTrusted() {
        // A third-party monitor written before `capability` existed still compiles, and inherits the
        // least capable resolution rather than being taken at face value.
        val undeclared =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> = MutableStateFlow(NetworkState.Unknown)

                override fun close() {}
            }
        assertEquals(MonitorMechanism.Unknown, undeclared.capability.mechanism)
        assertEquals(ReachResolution.LinkOnly, undeclared.capability.resolution)
    }

    @Test
    fun anUndeclaredObservationCountNeverAdvancesItMeansNotReportedNotQuiet() {
        // A monitor that predates the counter (or opts out — every Polled monitor does) reports the
        // default, which sits at zero forever. Zero-deltas from such a monitor mean "density is not
        // reported here", never "the platform is quiet" — the same explicit-unknown stance as
        // UndeclaredCapability, and the reason a consumer gates interpretation on capability.mechanism.
        val undeclared =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> = MutableStateFlow(NetworkState.Unknown)

                override fun close() {}
            }
        assertEquals(0L, undeclared.observationCount.value)
        assertEquals(0L, NetworkMonitor.AlwaysAvailable.observationCount.value, "Static asserts, never observes")
    }

    @Test
    fun processDefaultReturnsTheInstalledOverride() {
        // The cross-platform injection seam: once a monitor is installed, processDefault() returns it
        // for every consumer instead of the platform default. Installs are process-global and one-way
        // by design (install once at startup), so this is the only test that touches the seam.
        // Android no longer *needs* it — see AndroidStartupNetworkMonitorInstrumentedTest, which proves
        // default() is already reactive there via androidx.startup.
        val marker =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> =
                    MutableStateFlow(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved))

                override fun close() {}
            }
        NetworkMonitor.installProcessDefault(marker)
        assertSame(marker, NetworkMonitor.processDefault(), "processDefault must return the installed override")
    }
}
