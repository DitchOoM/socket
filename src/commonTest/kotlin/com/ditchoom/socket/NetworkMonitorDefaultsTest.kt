package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkMonitorDefaultsTest {
    @Test
    fun networkIdDefaultsToUnidentifiedForMonitorsThatDoNotOverrideIt() {
        // AlwaysAvailable (and any third-party monitor predating the property) inherits the default:
        // the explicit "no cheap network identity" state, never null (RFC_TRANSPORT_FALLBACK §12).
        assertEquals(NetworkId.Unidentified, NetworkMonitor.AlwaysAvailable.networkId.value)
    }
}
