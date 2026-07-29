package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [androidNetworkId], the pure `NetworkCapabilities` transport bits + `networkHandle` →
 * [NetworkId] mapper. Its KDoc has claimed "(unit-tested without a device)" since it was written; this
 * is that test — the sibling mappers (`JvmNetworkIdTest`, `BrowserNetworkIdMappingTests`) had one and
 * Android did not.
 *
 * Host JVM, no Robolectric: the function takes booleans and a nullable Long precisely so the branchy
 * part is testable off-device. The emulator lane exercises exactly one point in this space (whatever
 * link the API 29 AVD happens to have), so everything below — VPN transport composition, the
 * precedence order, and the API < 23 degradation — is only covered here.
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
    fun noRecognizedTransportIsUnidentifiedEvenWithoutAHandle() {
        assertEquals(
            NetworkId.Unidentified,
            androidNetworkId(hasWifi = false, hasCellular = false, hasEthernet = false, hasVpn = false, handle = null),
        )
    }

    @Test
    fun missingHandleDegradesToKindOnlyNotUnidentified() {
        // `network.networkHandle` needs API 23; below that AndroidNetworkMonitor passes null. The kind
        // is still known, so the identity degrades one step — losing per-link identity but keeping the
        // Wi-Fi↔cellular transition, which is the decisive one. The emulator lane runs API 29 only, so
        // this branch is exercised nowhere else in the repo.
        assertEquals(
            NetworkId.KindOnly(NetworkKind.Wifi),
            androidNetworkId(hasWifi = true, hasCellular = false, hasEthernet = false, hasVpn = false, handle = null),
        )
        assertEquals(
            NetworkId.KindOnly(NetworkKind.Vpn(setOf(NetworkKind.Cellular))),
            androidNetworkId(hasWifi = false, hasCellular = true, hasEthernet = false, hasVpn = true, handle = null),
        )
    }

    @Test
    fun handleIsCarriedThroughVerbatimIncludingZero() {
        // Zero is a legal networkHandle and must not be confused with "absent" — the nullable Long is
        // the only absence signal.
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
