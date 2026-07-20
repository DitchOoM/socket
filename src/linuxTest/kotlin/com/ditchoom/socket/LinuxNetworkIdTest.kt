@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNetworkIdTest {
    // --- Synthetic netlink RTM_GETROUTE dump builders (host little-endian x86_64/arm64). ---
    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun le32(v: Int) =
        byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte(),
        )

    /** One rtattr: u16 len(=8) + u16 type + u32 value. */
    private fun rtattr(
        type: Int,
        value: Int,
    ) = le16(8) + le16(type) + le32(value)

    /** One RTM_NEWROUTE message: nlmsghdr(16) + rtmsg(12) + RTA_OIF(8) + RTA_PRIORITY(8) = 44 bytes. */
    private fun routeMsg(
        dstLen: Int,
        oif: Int,
        metric: Int,
        nlmsgType: Int = 24, // RTM_NEWROUTE
        rtnType: Int = 1, // RTN_UNICAST
    ): ByteArray {
        val rtmsg = byteArrayOf(2, dstLen.toByte(), 0, 0, 254.toByte(), 0, 0, rtnType.toByte()) + le32(0)
        val payload = rtmsg + rtattr(4, oif) + rtattr(6, metric) // RTA_OIF=4, RTA_PRIORITY=6
        val len = 16 + payload.size
        val hdr = le32(len) + le16(nlmsgType) + le16(0) + le32(0) + le32(0)
        return hdr + payload
    }

    private fun doneMsg() = le32(16) + le16(3) + le16(0) + le32(0) + le32(0) // NLMSG_DONE

    private fun scan(bytes: ByteArray): LinuxNetworkMonitor.ChunkScan =
        bytes.usePinned { pinned ->
            LinuxNetworkMonitor.scanDefaultRoutes(pinned.addressOf(0), bytes.size)
        }

    @Test
    fun scanDefaultRoutesPicksLowestMetricDefaultRouteOif() {
        // Two default routes (dst_len 0): oif 5 @ metric 25 beats oif 3 @ metric 100. A non-default
        // route (dst_len 24) is ignored, and NLMSG_DONE terminates the dump (ChunkScan.End).
        val bytes =
            routeMsg(dstLen = 0, oif = 3, metric = 100) +
                routeMsg(dstLen = 0, oif = 5, metric = 25) +
                routeMsg(dstLen = 24, oif = 9, metric = 0) +
                doneMsg()
        val result = scan(bytes)
        assertTrue(result is LinuxNetworkMonitor.ChunkScan.End, "NLMSG_DONE must terminate the scan")
        assertEquals(
            LinuxNetworkMonitor.DefaultRoute.Via(InterfaceIndex(5), 25),
            result.route,
            "lowest-metric default route wins",
        )
    }

    @Test
    fun scanDefaultRoutesReportsNoneWhenNoDefaultRoutePresent() {
        // A chunk with only a non-default route and no terminator: DefaultRoute.None, ChunkScan.More.
        val result = scan(routeMsg(dstLen = 24, oif = 9, metric = 0))
        assertTrue(result is LinuxNetworkMonitor.ChunkScan.More, "no NLMSG_DONE ⇒ not terminated")
        assertEquals(LinuxNetworkMonitor.DefaultRoute.None, result.route, "no default route ⇒ None")
    }

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

    // ── IPv6 /proc/net/ipv6_route fallback (parseDefaultRouteInterfaceV6) ──
    // Format is headerless, all-hex, iface LAST:
    // destNet(32) destPrefix(2) srcNet(32) srcPrefix(2) nextHop(32) metric(8) refcnt(8) use(8) flags(8) iface

    @Test
    fun parsesV6DefaultRouteInterfaceChoosingLowestMetric() {
        // Two ::/0 defaults (prefix 00, flags RTF_UP|RTF_GATEWAY = 00000003): eth0 metric 0x100 wins over
        // wlan0 metric 0x400. A non-default route (fe80::/64) and a local ::1 route must be ignored.
        val table =
            listOf(
                "00000000000000000000000000000000 00 00000000000000000000000000000000 00 fe800000000000000000000000000001 00000400 00000001 00000000 00000003 wlan0",
                "00000000000000000000000000000000 00 00000000000000000000000000000000 00 fe800000000000000000000000000002 00000100 00000001 00000000 00000003 eth0",
                "fe800000000000000000000000000000 40 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000000 00000000 00000001 eth0",
                "00000000000000000000000000000001 80 00000000000000000000000000000000 00 00000000000000000000000000000000 00000000 00000001 00000000 80200001 lo",
            ).joinToString("\n")
        assertEquals("eth0", LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(table))
    }

    @Test
    fun ignoresV6UnreachableDefaultRejectRouteToLo() {
        // systemd installs `unreachable default dev lo`: destination ::/0 (prefix 00) but flags carry
        // RTF_REJECT (0x0200). Without the reject filter this would be picked as the primary link (lo).
        val table =
            "00000000000000000000000000000000 00 00000000000000000000000000000000 00 00000000000000000000000000000000 ffffffff 00000001 00000000 00200203 lo"
        assertNull(LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(table))
    }

    @Test
    fun ignoresV6DownDefaultAndMalformedOrEmptyV6Table() {
        // A ::/0 row without RTF_UP (flags 00000000) is not usable.
        assertNull(
            LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(
                "00000000000000000000000000000000 00 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000000 eth0",
            ),
            "a ::/0 route without RTF_UP is not a usable default",
        )
        assertNull(LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(""), "empty table has no default route")
        // Truncated rows (fewer than 10 columns) are skipped, not crashed on.
        assertNull(
            LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(
                "00000000000000000000000000000000 00 00 eth0",
            ),
            "malformed short rows are skipped",
        )
    }

    @Test
    fun ignoresV6NonDefaultPrefixEvenWhenDestinationIsAllZero() {
        // A prefix length other than 00 is not a default route even with an all-zero destination.
        val table =
            "00000000000000000000000000000000 08 00000000000000000000000000000000 00 00000000000000000000000000000000 00000100 00000001 00000000 00000003 eth0"
        assertNull(LinuxNetworkMonitor.parseDefaultRouteInterfaceV6(table))
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
