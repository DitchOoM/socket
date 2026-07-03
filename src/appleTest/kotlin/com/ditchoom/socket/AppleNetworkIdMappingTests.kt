package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-mapper tests for [appleNetworkId] — the `nw_path` callback fields → typed [NetworkId]. */
class AppleNetworkIdMappingTests {
    @Test
    fun satisfiedWifiPathIsAWifiLink() {
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, 5),
            appleNetworkId(status = 1, interfaceType = 1, interfaceIndex = 5u, interfaceName = "en0", usesTypes = 1),
        )
    }

    @Test
    fun cellularAndWiredMapToTheirKinds() {
        assertEquals(
            NetworkId.Link(NetworkKind.Cellular, 3),
            appleNetworkId(1, 2, 3u, "pdp_ip0", 2),
        )
        assertEquals(
            NetworkId.Link(NetworkKind.Ethernet, 4),
            appleNetworkId(1, 3, 4u, "en5", 4),
        )
    }

    @Test
    fun utunOtherInterfaceIsAVpnCarryingItsUnderlyingLinks() {
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Wifi)), 12),
            appleNetworkId(status = 1, interfaceType = 0, interfaceIndex = 12u, interfaceName = "utun3", usesTypes = 1),
        )
        // Vpn(over cellular) is a *different* network than Vpn(over wifi) — same index, different kind.
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Cellular)), 12),
            appleNetworkId(1, 0, 12u, "utun3", 2),
        )
    }

    @Test
    fun unknownOtherInterfaceKeepsItsRawNameAsDiagnostic() {
        assertEquals(
            NetworkId.Link(NetworkKind.Other("awdl0"), 9),
            appleNetworkId(1, 0, 9u, "awdl0", 0),
        )
    }

    @Test
    fun unsatisfiedOrInterfacelessPathIsUnidentified() {
        assertEquals(NetworkId.Unidentified, appleNetworkId(status = 2, 1, 5u, "en0", 1))
        assertEquals(NetworkId.Unidentified, appleNetworkId(status = 1, -1, 0u, null, 0))
    }
}
