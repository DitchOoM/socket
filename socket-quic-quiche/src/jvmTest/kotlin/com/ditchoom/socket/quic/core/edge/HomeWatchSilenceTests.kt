package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.LinkAddresses
import com.ditchoom.socket.NumericAddress
import com.ditchoom.socket.ParsedAddress
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the network feed judges a path that went silent beside what the monitor says: a silence asks for a fresh port on
 * the route's address, wherever the monitor stands. Where the socket is changes only when the connection says a move
 * landed ([HomeWatch.landed]): an ask the connection did not act on leaves the watch where the socket is. Addresses are
 * documentation ranges.
 */
class HomeWatchSilenceTests {
    private val wifi = NetworkId.Link(NetworkKind.Wifi, 1L)
    private val cellular = NetworkId.Link(NetworkKind.Cellular, 2L)
    private val ethernet = NetworkId.Link(NetworkKind.Ethernet, 3L)
    private val wifiAddress = "192.0.2.10"
    private val cellularAddress = "198.51.100.20"
    private val ethernetAddress = "2001:db8::3"
    private val fromWifi = RouteSource.Resolved(wifiAddress)
    private val fromCellular = RouteSource.Resolved(cellularAddress)

    /** The monitor never moves; the path's silence alone asks for a fresh port on the address it is on. */
    @Test
    fun aSilentPathRebindsWithNoNetworkEventAtAll() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        assertEquals(wifiAddress, watch.target(), "the fresh port is on the address the route leaves from")
    }

    /**
     * A rebind on the same address leaves the network the socket is on where it was, so a genuine handoff after it is
     * still a migration, and the path going silent again after that is still a rebind.
     */
    @Test
    fun aRebindLeavesTheHomeNetworkWhereItWas() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        watch.landed(wifiAddress, wifi, LinkAddresses.NotReported)
        assertEquals(HomeStep.Stay, watch.up(wifi, fromWifi), "the monitor naming Wi-Fi again is no handoff")
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular), "a handoff after a rebind")
        watch.landed(cellularAddress, cellular, LinkAddresses.NotReported)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular), "the trigger is still live")
    }

    /** Each silence is one rebind, an answering path none; the feed's own test holds it to one per declaration. */
    @Test
    fun eachSilenceIsOneRebindAndOnlyOne() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        repeat(5) { assertEquals(HomeStep.Rebind, watch.silent(fromWifi), "silence $it") }
        assertEquals(wifiAddress, watch.host())
    }

    /**
     * A path that dies before the monitor named any network still rebinds, and the first network the monitor names is
     * home, not a handoff: the fresh port opened on the default route, which is that network. A handoff after it moves.
     */
    @Test
    fun aRebindBeforeTheMonitorNamesANetworkLeavesHomeToTheFirstItNames() {
        val watch = HomeWatch(wifiAddress, NetworkId.Unidentified, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        watch.landed(wifiAddress, NetworkId.Unidentified, LinkAddresses.NotReported)
        assertEquals(HomeStep.Stay, watch.up(wifi, fromWifi), "the first network named was read as a handoff")
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular))
    }

    /**
     * The rebind's fresh port landed on ethernet's address while the monitor already named ethernet: the reading of
     * ethernet queued behind it finds the socket already there.
     */
    @Test
    fun aRebindOntoTheNamedNetworkIsNotRepeatedByTheReadingQueuedBehindIt() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular))
        assertEquals(HomeStep.Rebind, watch.silent(RouteSource.Resolved(ethernetAddress)))
        watch.landed(ethernetAddress, ethernet, LinkAddresses.NotReported)
        assertEquals(HomeStep.Stay, watch.up(ethernet, RouteSource.Resolved(ethernetAddress)), "a second move onto ethernet")
    }

    /** A network named after a rebind, whose route leaves from another address, is a handoff: the rebind opened on Wi-Fi. */
    @Test
    fun aNetworkNamedAfterARebindFromAnotherAddressIsStillAMigration() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        assertEquals(HomeStep.Migrate, watch.up(ethernet, RouteSource.Resolved(ethernetAddress)))
    }

    /**
     * The 2026-09-26 iPhone walk: cellular up beside Wi-Fi, the monitor naming Wi-Fi and reporting both links' addresses,
     * the default route already on cellular. The rebind binds cellular's address and lands there, so it is on cellular,
     * and the monitor naming cellular afterwards is no handoff and no reopen.
     */
    @Test
    fun aRebindOntoAnAddressAnotherLinkCarriesIsOnThatLink() {
        val links = linksOf(wifi to wifiAddress, cellular to cellularAddress)
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular))
        assertEquals(cellularAddress, watch.target())
        watch.landed(cellularAddress, wifi, links)
        assertEquals(HomeStep.Stay, watch.up(cellular, fromCellular), "a second move onto cellular")
    }

    /** The same for a migration the route asks for: the socket is on the link that carries its new address. */
    @Test
    fun aMigrationOntoAnAddressAnotherLinkCarriesIsOnThatLink() {
        val links = linksOf(wifi to wifiAddress, cellular to cellularAddress)
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(wifi, fromCellular))
        watch.landed(cellularAddress, wifi, links)
        assertEquals(HomeStep.Stay, watch.up(cellular, fromCellular), "a reopen onto cellular")
    }

    /**
     * A rebind toward cellular's address that the connection did not take (the path answered first, the move was
     * refused, given up or lost) leaves the socket on Wi-Fi, as the connection says: the monitor naming cellular is the
     * handoff it is.
     */
    @Test
    fun aRebindTheConnectionDidNotTakeLeavesTheHandoffAfterItAHandoff() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular))
        assertEquals(wifiAddress, watch.host(), "the ask moved the watch's idea of where the socket is")
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular), "the handoff onto cellular was swallowed")
    }

    /** While a move toward an address is asked and not yet ended, a reading toward the same address does not ask again. */
    @Test
    fun aReadingTowardTheAddressAMoveIsUnderWayToAsksNothingMore() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular))
        assertEquals(HomeStep.Stay, watch.up(cellular, fromCellular), "a second ask would throw away the move under way")
        watch.landed(wifiAddress, wifi, LinkAddresses.NotReported)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular), "the move ended where it was, and is not asked again")
    }

    /**
     * The same where the monitor reports no link addresses and names a new network: the socket is still on Wi-Fi's
     * address, so the route from cellular's is a migration, never a reopen of the Wi-Fi socket on cellular, which the
     * platform would refuse and which would end the connection.
     */
    @Test
    fun aRebindTheConnectionDidNotTakeIsNeverAReopenOnTheNextNetwork() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular))
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular), "the Wi-Fi socket would be reopened on cellular")
    }

    /**
     * A move under way toward an address, whose network the monitor then names anew with the route still from that
     * address: the move is asked again on that network, never a reopen, which with no second socket left would land on
     * the carrying one.
     */
    @Test
    fun aNewNetworkUnderAMoveUnderWayAsksTheMoveAgainThere() {
        val watch = HomeWatch(wifiAddress, wifi, OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular))
        val again = NetworkId.Link(NetworkKind.Cellular, 9L)
        assertEquals(HomeStep.Migrate, watch.up(again, fromCellular), "a reopen while a move is asked")
        assertEquals(HomeStep.Stay, watch.up(again, fromCellular))
    }

    /** One address spelled two ways is one address: the connection's own spelling of where it landed is home. */
    @Test
    fun anAddressSpelledAnotherWayIsTheSameAddress() {
        val watch = HomeWatch("2001:db8:0:0:0:0:0:3", wifi, OnMove.Migrate)
        assertEquals(HomeStep.Stay, watch.up(wifi, RouteSource.Resolved(ethernetAddress)), "the same address read as a move")
    }

    /** No route, no network, or a connection that does not move on its own: a silence asks for nothing. */
    @Test
    fun aSilenceWithNoRouteOrNoNetworkOrNoMovesAsksForNothing() {
        val noRoute = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)
        assertEquals(HomeStep.Stay, HomeWatch(wifiAddress, wifi, OnMove.Migrate).silent(noRoute))
        val offline = HomeWatch(wifiAddress, wifi, OnMove.Migrate).apply { offline() }
        assertEquals(HomeStep.Stay, offline.silent(fromWifi))
        assertEquals(HomeStep.Stay, HomeWatch(wifiAddress, wifi, OnMove.End).silent(fromWifi))
    }

    private fun linksOf(vararg links: Pair<NetworkId, String>): LinkAddresses =
        LinkAddresses.Reported(links.associate { (id, address) -> id to setOf(numeric(address)) })

    private fun numeric(address: String): NumericAddress =
        when (val parsed = NumericAddress.parse(address)) {
            is ParsedAddress.Address -> parsed.address
            is ParsedAddress.NotNumeric -> throw AssertionError("not numeric: $address")
        }
}
