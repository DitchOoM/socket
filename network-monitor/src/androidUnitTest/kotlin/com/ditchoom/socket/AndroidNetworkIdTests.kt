package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Unit tests for [androidNetworkId], the pure `NetworkCapabilities` transport bits + `networkHandle` →
 * [NetworkId] mapper. Its KDoc has claimed "(unit-tested without a device)" since it was written; this
 * is that test — the sibling mappers (`JvmNetworkIdTest`, `BrowserNetworkIdMappingTests`) had one and
 * Android did not.
 *
 * Host JVM, no Robolectric: the function takes booleans and a Long precisely so the branchy part is
 * testable off-device. The emulator lane exercises exactly one point in this space (whatever link the
 * API 29 AVD happens to have), so everything below — VPN transport composition and the precedence
 * order — is only covered here.
 *
 * There is no longer an API < 23 degradation to cover: `minSdk` is 23 (RFC_NETWORK_REACHABILITY §8.1),
 * which is where `Network.getNetworkHandle()` exists, so [androidNetworkId] takes a non-null handle and
 * a [NetworkId.KindOnly] result is unreachable on this platform.
 */
class AndroidNetworkIdTests {
    @Test
    fun wifiWithHandleIsAFullLinkIdentity() {
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, HANDLE),
            androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = false, handle = HANDLE),
        )
    }

    @Test
    fun cellularAndEthernetEachMapToTheirOwnKind() {
        assertEquals(
            NetworkId.Link(NetworkKind.Cellular, HANDLE),
            androidNetworkId(hasWifi = false, hasCellular = true, hasEthernet = false, hasVpn = false, handle = HANDLE),
        )
        assertEquals(
            NetworkId.Link(NetworkKind.Ethernet, HANDLE),
            androidNetworkId(hasWifi = false, hasCellular = false, hasEthernet = true, hasVpn = false, handle = HANDLE),
        )
    }

    @Test
    fun vpnWinsAndKeepsTheTransportItTunnelsOver() {
        // The load-bearing case for the per-network capability-cache scope: a VPN's capabilities also
        // list the underlying transport, so "VPN over Wi-Fi" and "VPN over cellular" must be different
        // NetworkIds. Collapsing both to a bare Vpn would let a cache entry learned on Wi-Fi be reused
        // after the tunnel moved to cellular.
        val overWifi =
            androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = true, handle = HANDLE)
        val overCellular =
            androidNetworkId(hasWifi = false, hasCellular = true, hasEthernet = false, hasVpn = true, handle = HANDLE)

        assertEquals(NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Wifi)), HANDLE), overWifi)
        assertEquals(NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Cellular)), HANDLE), overCellular)
        assertNotEquals(overWifi, overCellular)
    }

    @Test
    fun vpnOverMultipleTransportsKeepsThemAll() {
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Wifi, NetworkKind.Ethernet)), HANDLE),
            androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = true, hasVpn = true, handle = HANDLE),
        )
    }

    @Test
    fun vpnWithNoUnderlyingTransportIsStillAVpn() {
        // Not an impossible state — a VPN whose underlying transports aren't reported yet. It must stay
        // a Vpn with an empty transport set, never fall through to Unidentified.
        assertEquals(
            NetworkId.Link(NetworkKind.Vpn(emptySet()), HANDLE),
            androidNetworkId(hasWifi = false, hasCellular = false, hasEthernet = false, hasVpn = true, handle = HANDLE),
        )
    }

    @Test
    fun wifiOutranksCellularWhenBothAreReported() {
        // A network can legitimately carry both bits. The `when` order decides, and that order is a
        // contract: the same physical link must map to the same NetworkId on every reading, or the
        // cache key flaps and every entry misses.
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, HANDLE),
            androidNetworkId(hasWifi = true, hasCellular = true, hasEthernet = false, hasVpn = false, handle = HANDLE),
        )
    }

    @Test
    fun noRecognizedTransportIsUnidentifiedNotKindOnly() {
        // Explicit "no cheap network identity" (RFC_TRANSPORT_FALLBACK §12) — never a null, and never a
        // KindOnly wrapping some invented kind.
        assertEquals(
            NetworkId.Unidentified,
            androidNetworkId(hasWifi = false, hasCellular = false, hasEthernet = false, hasVpn = false, handle = HANDLE),
        )
    }

    @Test
    fun everyRecognizedTransportKeepsPerLinkIdentity() {
        // With minSdk 23 the handle always exists, so a recognized transport is always a full
        // NetworkId.Link — the KindOnly degradation path is gone, not merely unused.
        val ids =
            listOf(
                androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = false, handle = HANDLE),
                androidNetworkId(hasWifi = false, hasCellular = true, hasEthernet = false, hasVpn = false, handle = HANDLE),
                androidNetworkId(hasWifi = false, hasCellular = false, hasEthernet = true, hasVpn = false, handle = HANDLE),
                androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = true, handle = HANDLE),
            )
        ids.forEach { assertIs<NetworkId.Link>(it) }
    }

    @Test
    fun handleIsCarriedThroughVerbatimIncludingZero() {
        // Zero is a legal networkHandle and must survive the round-trip like any other value.
        assertEquals(
            NetworkId.Link(NetworkKind.Wifi, 0L),
            androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = false, handle = 0L),
        )
    }

    private companion object {
        /** An arbitrary `Network.getNetworkHandle()` value; only its round-trip matters. */
        const val HANDLE = 0x1234_5678_9ABCL
    }
}
