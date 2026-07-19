package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNetworkIdTest {
    @Test
    fun parsesDefaultRouteInterfaceChoosingLowestMetric() {
        // Two default routes (Destination 00000000, RTF_UP=0x0003) — eth2 metric 25 wins over wlan0
        // metric 600 — plus a non-default route that must be ignored. Whitespace-agnostic (tabs/spaces).
        val routeTable =
            listOf(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT",
                "eth2 00000000 01106E64 0003 0 0 25 00000000 0 0 0",
                "wlan0 00000000 AABBCCDD 0003 0 0 600 00000000 0 0 0",
                "eth2 00106E64 00000000 0001 0 0 281 00FCFFFF 0 0 0",
            ).joinToString("\n")
        assertEquals("eth2", LinuxNetworkMonitor.parseDefaultRouteInterface(routeTable))
    }

    @Test
    fun ignoresDownDefaultRouteAndReportsNoneWhenNoUpDefault() {
        // A default-destination row without RTF_UP (flags 0000) is not a usable default route.
        val routeTable =
            listOf(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT",
                "eth0 00000000 01020304 0000 0 0 100 00000000 0 0 0",
                "eth0 00106E64 00000000 0001 0 0 0 00FCFFFF 0 0 0",
            ).joinToString("\n")
        assertNull(LinuxNetworkMonitor.parseDefaultRouteInterface(routeTable))
    }

    @Test
    fun emptyOrHeaderOnlyOrMalformedRouteTableYieldsNoDefault() {
        assertNull(LinuxNetworkMonitor.parseDefaultRouteInterface(""), "empty table has no default route")
        assertNull(
            LinuxNetworkMonitor.parseDefaultRouteInterface(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT",
            ),
            "a header-only table has no default route",
        )
        // Truncated rows (fewer than 8 columns) must be skipped, not crash.
        assertNull(
            LinuxNetworkMonitor.parseDefaultRouteInterface(
                "Iface Destination Gateway\neth0 00000000 01020304",
            ),
            "malformed short rows are skipped",
        )
    }

    @Test
    fun tieOnMetricIsResolvedDeterministicallyByFirstLowest() {
        // Two up default routes at the same (lowest) metric — minByOrNull keeps the first, eth0.
        val routeTable =
            listOf(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT",
                "eth0 00000000 01020304 0003 0 0 100 00000000 0 0 0",
                "eth1 00000000 05060708 0003 0 0 100 00000000 0 0 0",
            ).joinToString("\n")
        assertEquals("eth0", LinuxNetworkMonitor.parseDefaultRouteInterface(routeTable))
    }

    @Test
    fun classifyLinkKindMapsEachKernelFactToItsKind() {
        // Wi-Fi wins even though a Wi-Fi NIC also reports the Ethernet ARPHRD type.
        assertEquals(
            NetworkKind.Wifi,
            LinuxNetworkMonitor.classifyLinkKind("wlan0", hasWireless = true, hasTunFlags = false, arphrdType = 1),
        )
        // A tun device: tun_flags present ⇒ VPN (before the name-based checks).
        assertEquals(
            NetworkKind.Vpn(),
            LinuxNetworkMonitor.classifyLinkKind("tun0", hasWireless = false, hasTunFlags = true, arphrdType = null),
        )
        // Cellular is name-based (wwan/rmnet/ppp) when there is no wireless/tun fact.
        assertEquals(
            NetworkKind.Cellular,
            LinuxNetworkMonitor.classifyLinkKind("rmnet0", hasWireless = false, hasTunFlags = false, arphrdType = 1),
        )
        // Plain wired NIC: ARPHRD_ETHER (1) with no other fact ⇒ Ethernet.
        assertEquals(
            NetworkKind.Ethernet,
            LinuxNetworkMonitor.classifyLinkKind("eth0", hasWireless = false, hasTunFlags = false, arphrdType = 1),
        )
        // Unknown ARPHRD / unreadable type ⇒ diagnostic Other keyed on the interface name.
        assertEquals(
            NetworkKind.Other("weird0"),
            LinuxNetworkMonitor.classifyLinkKind("weird0", hasWireless = false, hasTunFlags = false, arphrdType = null),
        )
    }

    @Test
    fun primaryNetworkIdOnThisHostIsARouteAwareLinkOrUnidentified() {
        // Live: reads /proc/net/route + /sys on the test host. Route-aware, so it must NOT be a bridge/
        // container guess when a real default route exists. Host-independent invariants only.
        when (val id = LinuxNetworkMonitor.primaryNetworkId()) {
            is NetworkId.Link -> assertTrue(id.handle > 0L, "a real link carries a positive interface index")
            NetworkId.Unidentified -> Unit // valid on a host with no default route / no up interface
            is NetworkId.KindOnly -> throw AssertionError("Linux derivation never produces KindOnly")
        }
    }
}
