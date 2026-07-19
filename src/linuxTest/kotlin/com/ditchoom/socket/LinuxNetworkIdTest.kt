package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
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
