package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-mapper tests for [browserConnectionTypeToNetworkId] — `navigator.connection.type` → typed [NetworkId]. */
class BrowserNetworkIdMappingTests {
    @Test
    fun knownKindsMapToKindOnly() {
        assertEquals(NetworkId.KindOnly(NetworkKind.Wifi), browserConnectionTypeToNetworkId("wifi"))
        assertEquals(NetworkId.KindOnly(NetworkKind.Cellular), browserConnectionTypeToNetworkId("cellular"))
        assertEquals(NetworkId.KindOnly(NetworkKind.Ethernet), browserConnectionTypeToNetworkId("ethernet"))
    }

    @Test
    fun absentNoneAndUnknownAreUnidentified() {
        assertEquals(NetworkId.Unidentified, browserConnectionTypeToNetworkId(null))
        assertEquals(NetworkId.Unidentified, browserConnectionTypeToNetworkId("none"))
        assertEquals(NetworkId.Unidentified, browserConnectionTypeToNetworkId("unknown"))
    }

    @Test
    fun unmappedTypesKeepTheRawLabelAsDiagnostic() {
        assertEquals(NetworkId.KindOnly(NetworkKind.Other("bluetooth")), browserConnectionTypeToNetworkId("bluetooth"))
    }
}
