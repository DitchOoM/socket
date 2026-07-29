package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-mapper tests for [appleNetworkId] — the `nw_path` callback fields → typed [NetworkId]. */
class AppleNetworkIdMappingTests {
    @Test
    fun satisfiedWifiPathIsAWifiLink() {
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, 5),
            appleNetworkId(interfaceType = 1, interfaceIndex = 5u, interfaceName = "en0", usesTypes = 1),
        )
    }

    @Test
    fun cellularAndWiredMapToTheirKinds() {
        assertEquals(
            NetworkId.Link(NetworkKind.Cellular, 3),
            appleNetworkId(2, 3u, "pdp_ip0", 2),
        )
        assertEquals(
            NetworkId.Link(NetworkKind.Ethernet, 4),
            appleNetworkId(3, 4u, "en5", 4),
        )
    }

    @Test
    fun utunOtherInterfaceIsAVpnCarryingItsUnderlyingLinks() {
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Wifi)), 12),
            appleNetworkId(interfaceType = 0, interfaceIndex = 12u, interfaceName = "utun3", usesTypes = 1),
        )
        // Vpn(over cellular) is a *different* network than Vpn(over wifi) — same index, different kind.
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Cellular)), 12),
            appleNetworkId(0, 12u, "utun3", 2),
        )
    }

    @Test
    fun unknownOtherInterfaceKeepsItsRawNameAsDiagnostic() {
        assertEquals(
            NetworkId.Link(NetworkKind.Other("awdl0"), 9),
            appleNetworkId(0, 9u, "awdl0", 0),
        )
    }

    @Test
    fun interfacelessPathIsUnidentified() {
        assertEquals(NetworkId.Unidentified, appleNetworkId(-1, 0u, null, 0))
    }

    /**
     * Identity no longer depends on `nw_path_status` — an unsatisfied path that still names an interface
     * has a perfectly good identity, and [NetworkState.LinkLocal] needs one. The *rung* is
     * [appleNetworkState]'s job.
     */
    @Test
    fun identityIsIndependentOfPathStatus() {
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, 5),
            appleNetworkId(1, 5u, "en0", 1),
        )
        assertEquals(
            NetworkState.LinkLocal(NetworkId.Link(NetworkKind.Wifi, 5)),
            appleNetworkState(
                status = NwPathStatus.Unsatisfied,
                interfaceType = 1,
                interfaceIndex = 5u,
                interfaceName = "en0",
                usesTypes = 1,
            ),
        )
    }
}

/**
 * Pure-mapper tests for [appleNetworkState] — the Apple rung table of RFC_NETWORK_REACHABILITY §4.
 *
 * Every case is also checked against [ReachResolution.RouteOnly], the resolution [AppleNetworkMonitor]
 * declares, so the monitor cannot drift into emitting a state its own capability forbids.
 */
class AppleNetworkStateMappingTests {
    private fun state(
        status: NwPathStatus,
        interfaceType: Int = 1,
        interfaceIndex: UInt = 5u,
        interfaceName: String? = "en0",
        usesTypes: Int = 1,
    ) = appleNetworkState(status, interfaceType, interfaceIndex, interfaceName, usesTypes)

    @Test
    fun satisfiedPathIsRoutableWithUnobservedInternet() {
        assertEquals(
            NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, 5), InternetAccess.Unobserved),
            state(status = NwPathStatus.Satisfied),
        )
    }

    /** `NWPath` has no validation concept, so `Confirmed`/`Pending`/`Blocked` are unreachable here. */
    @Test
    fun satisfiedPathNeverClaimsObservedInternet() {
        val routable = state(status = NwPathStatus.Satisfied) as NetworkState.Routable
        assertTrue(routable.internet is InternetAccess.Unobserved, "was ${routable.internet}")
        assertTrue(routable.canRouteOffLink)
        assertTrue(!routable.isTransient)
    }

    @Test
    fun unsatisfiedPathWithAnInterfaceIsLinkLocal() {
        assertEquals(
            NetworkState.LinkLocal(NetworkId.Link(NetworkKind.Ethernet, 4)),
            state(status = NwPathStatus.Unsatisfied, interfaceType = 3, interfaceIndex = 4u, interfaceName = "en5", usesTypes = 4),
        )
    }

    @Test
    fun unsatisfiedPathWithNoInterfaceIsOffline() {
        assertEquals(
            NetworkState.Offline,
            state(status = NwPathStatus.Unsatisfied, interfaceType = -1, interfaceIndex = 0u, interfaceName = null, usesTypes = 0),
        )
    }

    /** RFC §8.3: `requiresConnection` stays folded in with `unsatisfied` until a device check says otherwise. */
    @Test
    fun requiresConnectionMatchesUnsatisfied() {
        assertEquals(state(status = NwPathStatus.Unsatisfied), state(status = NwPathStatus.RequiresConnection))
        assertEquals(
            state(status = NwPathStatus.Unsatisfied, interfaceType = -1, interfaceIndex = 0u, interfaceName = null, usesTypes = 0),
            state(status = NwPathStatus.RequiresConnection, interfaceType = -1, interfaceIndex = 0u, interfaceName = null, usesTypes = 0),
        )
    }

    @Test
    fun anUnrecognizedStatusIsUnknownNotAGuessedRung() {
        // The enum is Apple's to extend. A future case must not land on whatever arm happens to be last;
        // Unknown is "do not know yet", and it is transient, so a consumer waits instead of tearing down.
        assertEquals(NetworkState.Unknown, state(status = NwPathStatus.Unrecognized(42)))
        assertEquals(NwPathStatus.Unrecognized(42), nwPathStatus(42))
    }

    @Test
    fun theRawStatusEnumDecodesAtTheBoundaryOnly() {
        assertEquals(NwPathStatus.Invalid, nwPathStatus(0))
        assertEquals(NwPathStatus.Satisfied, nwPathStatus(1))
        assertEquals(NwPathStatus.Unsatisfied, nwPathStatus(2))
        assertEquals(NwPathStatus.RequiresConnection, nwPathStatus(3))
    }

    @Test
    fun invalidPathIsUnknownNotOffline() {
        assertEquals(NetworkState.Unknown, state(status = NwPathStatus.Invalid))
        assertTrue(NetworkState.Unknown.isTransient, "Unknown means 'wait', not 'no network, act now'")
    }

    @Test
    fun everyMappedStateIsPermittedByTheDeclaredResolution() {
        val states =
            listOf(
                state(status = NwPathStatus.Invalid),
                state(status = NwPathStatus.Unrecognized(9)),
                state(status = NwPathStatus.Satisfied),
                state(status = NwPathStatus.Unsatisfied),
                state(status = NwPathStatus.RequiresConnection),
                state(status = NwPathStatus.Unsatisfied, interfaceType = -1, interfaceIndex = 0u, interfaceName = null, usesTypes = 0),
            )
        for (s in states) {
            assertTrue(
                ReachResolution.RouteOnly.permits(s),
                "AppleNetworkMonitor declares RouteOnly but mapped $s",
            )
        }
    }
}
