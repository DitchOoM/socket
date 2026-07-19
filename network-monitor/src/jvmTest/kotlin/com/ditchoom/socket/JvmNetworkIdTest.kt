package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmNetworkIdTest {
    @Test
    fun currentPrimaryNetworkIdIsAStableLinkOrUnidentified() {
        // Host-independent invariants: the derivation reuses the availability scan to keep the primary
        // interface's OS index as a NetworkId.Link handle (the discriminator QUIC auto-migration reacts
        // to), or Unidentified when nothing qualifies. Never throws, never a bare string/null.
        when (val id = currentPrimaryNetworkId()) {
            is NetworkId.Link -> {
                assertTrue(id.handle != 0L, "a real link must carry a non-zero interface handle")
                val kind = id.kind
                assertTrue(kind is NetworkKind.Other && kind.raw.isNotEmpty(), "raw-scan kind is a named Other")
            }
            NetworkId.Unidentified -> Unit // valid: no qualifying interface on this host
            is NetworkId.KindOnly -> throw AssertionError("desktop-JVM derivation never produces KindOnly")
        }
    }

    @Test
    fun parseDefaultRouteInterfaceMatchesTheNativeParser() {
        // Route-aware selection (item c): on Linux the JVM prefers the /proc/net/route default-route
        // interface, exactly like the native LinuxNetworkMonitor. Lowest metric wins; non-default and
        // down routes are ignored; short/empty tables yield null.
        val routeTable =
            listOf(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT",
                "eth2 00000000 01106E64 0003 0 0 25 00000000 0 0 0",
                "wlan0 00000000 AABBCCDD 0003 0 0 600 00000000 0 0 0",
                "eth2 00106E64 00000000 0001 0 0 281 00FCFFFF 0 0 0",
            ).joinToString("\n")
        assertEquals("eth2", parseDefaultRouteInterface(routeTable), "lowest-metric up default route wins")

        assertNull(parseDefaultRouteInterface(""), "empty table has no default route")
        assertNull(
            parseDefaultRouteInterface("Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT"),
            "header-only table has no default route",
        )
        assertNull(
            parseDefaultRouteInterface("Iface Destination Gateway\neth0 00000000 01020304"),
            "malformed short rows are skipped",
        )
        assertNull(
            parseDefaultRouteInterface(
                "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT\n" +
                    "eth0 00000000 01020304 0000 0 0 100 00000000 0 0 0",
            ),
            "a default-destination row without RTF_UP is not a usable default route",
        )
    }

    @Test
    fun currentPrimaryNetworkIdIsStableAcrossCalls() {
        // The same host state must map to the same identity, so a StateFlow dedupes it and auto-migration
        // only fires on a genuine change — not on every monitor tick.
        assertTrue(currentPrimaryNetworkId() == currentPrimaryNetworkId(), "identity must be stable across calls")
    }
}
