package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NetworkMonitorDefaultsTest {
    @Test
    fun networkIdDefaultsToUnidentifiedForMonitorsThatDoNotOverrideIt() {
        // AlwaysAvailable (and any third-party monitor predating the property) inherits the default:
        // the explicit "no cheap network identity" state, never null (RFC_TRANSPORT_FALLBACK §12).
        assertEquals(NetworkId.Unidentified, NetworkMonitor.AlwaysAvailable.networkId.value)
    }

    @Test
    fun processDefaultReturnsTheInstalledOverride() {
        // The cross-platform injection seam: once a monitor is installed (on Android this is how the
        // Context-backed monitor is supplied via installAndroidContext), processDefault() returns it
        // for every consumer instead of the platform default. Installs are process-global and one-way
        // by design (install once at startup), so this is the only test that touches the seam.
        val marker =
            object : NetworkMonitor {
                override val availability: StateFlow<NetworkAvailability> =
                    MutableStateFlow(NetworkAvailability.AVAILABLE)

                override fun close() {}
            }
        NetworkMonitor.installProcessDefault(marker)
        assertSame(marker, NetworkMonitor.processDefault(), "processDefault must return the installed override")
    }
}
