package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

/**
 * Pure-mapper tests for [jsNetworkState] — the JS row of RFC_NETWORK_REACHABILITY §4, shared by the Node
 * and browser paths of [JsNetworkMonitor].
 */
class JsNetworkStateMappingTests {
    private val wifi = NetworkId.KindOnly(NetworkKind.Wifi)

    @Test
    fun anUpLinkIsRoutableWithUnobservedInternet() {
        assertEquals(
            NetworkState.Routable(wifi, InternetAccess.Unobserved),
            jsNetworkState(hasLink = true, id = wifi),
        )
        // Node resolves no identity at all — still Routable, just Unidentified.
        assertEquals(
            NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved),
            jsNetworkState(hasLink = true, id = NetworkId.Unidentified),
        )
    }

    @Test
    fun noLinkIsOffline() {
        assertEquals(NetworkState.Offline, jsNetworkState(hasLink = false, id = NetworkId.Unidentified))
        // Even if a stale connection.type is still readable, no link means Offline — which carries no id.
        assertEquals(NetworkState.Offline, jsNetworkState(hasLink = false, id = wifi))
    }

    /**
     * RFC §9.2: a `LinkOnly` monitor must **never** report [NetworkState.LinkLocal]. Under the draft an
     * online browser would have reported `canRouteOffLink == false` and refused to connect.
     */
    @Test
    fun jsNetworkStateNeverReportsLinkLocal() {
        for (hasLink in listOf(true, false)) {
            for (id in listOf(NetworkId.Unidentified, wifi)) {
                val state = jsNetworkState(hasLink, id)
                assertTrue(state !is NetworkState.LinkLocal, "LinkOnly must never report LinkLocal, was $state")
                assertTrue(
                    ReachResolution.LinkOnly.permits(state),
                    "JsNetworkMonitor declares LinkOnly but mapped ($hasLink, $id) to $state",
                )
            }
        }
    }

    @Test
    fun anOnlineBrowserIsWorthConnectingOn() {
        val online = jsNetworkState(hasLink = true, id = wifi)
        assertTrue(online.canRouteOffLink, "an online browser must be worth attempting a connection on")
        assertTrue(!online.isTransient, "Unobserved is terminal — nothing further will resolve")
        assertTrue(!online.needsUserAction)
    }
}
