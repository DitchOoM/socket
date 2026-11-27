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
 * the route's address, wherever the monitor stands. Where the socket is comes only from what the loop publishes
 * ([carried]); the move a rebind asks names the network that carries its address ([homeOf]), so the loop holds the
 * socket on the right link once it is promoted. Addresses are documentation ranges.
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
    private val onWifi = carried(wifiAddress, wifi)

    /** The monitor never moves; the path's silence alone asks for a fresh port on the address it is on. */
    @Test
    fun aSilentPathRebindsWithNoNetworkEventAtAll() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        assertEquals(wifiAddress, towardOf(fromWifi, onWifi), "the fresh port is on the address the route leaves from")
        assertEquals(wifiAddress, towardOf(RouteSource.PlatformAssigned, onWifi), "or on the carrying one's")
    }

    /**
     * A rebind on the same address leaves the socket on the network it was on, so a genuine handoff after it is still a
     * migration, and the path going silent again after that is still a rebind.
     */
    @Test
    fun aRebindLeavesTheHomeNetworkWhereItWas() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        assertEquals(wifi, homeOf(wifiAddress, LinkAddresses.NotReported, wifi), "the rebind's socket is asked on Wi-Fi")
        val rebound = carried(wifiAddress, wifi, ticket = 2)
        assertEquals(HomeStep.Stay, watch.up(wifi, fromWifi, rebound), "the monitor naming Wi-Fi again is no handoff")
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, rebound), "a handoff after a rebind")
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular), "the trigger is still live")
    }

    /** Each silence is one rebind, an answering path none; the feed's own test holds it to one per declaration. */
    @Test
    fun eachSilenceIsOneRebindAndOnlyOne() {
        val watch = HomeWatch(OnMove.Migrate)
        repeat(5) { assertEquals(HomeStep.Rebind, watch.silent(fromWifi), "silence $it") }
    }

    /**
     * A path that dies before the monitor named any network still rebinds, and the first network the monitor names is
     * taken by the loop, not a handoff: the fresh port opened on the default route, which is that network.
     */
    @Test
    fun aRebindBeforeTheMonitorNamesANetworkLeavesHomeToTheFirstItNames() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        val rebound = carried(wifiAddress, NetworkId.Unidentified, ticket = 2)
        assertEquals(HomeStep.Rehome, watch.up(wifi, fromWifi, rebound), "the loop takes the first network named")
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, carried(wifiAddress, wifi, ticket = 2)))
    }

    /**
     * The rebind's fresh port landed on ethernet's address while the monitor already named ethernet: the reading of
     * ethernet queued behind it finds the socket already there.
     */
    @Test
    fun aRebindOntoTheNamedNetworkIsNotRepeatedByTheReadingQueuedBehindIt() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, onWifi))
        assertEquals(HomeStep.Rebind, watch.silent(RouteSource.Resolved(ethernetAddress)))
        val landed = carried(ethernetAddress, homeOf(ethernetAddress, LinkAddresses.NotReported, ethernet), ticket = 2)
        assertEquals(HomeStep.Stay, watch.up(ethernet, RouteSource.Resolved(ethernetAddress), landed), "a second move onto ethernet")
    }

    /** A network named after a rebind, whose route leaves from another address, is a handoff: the rebind opened on Wi-Fi. */
    @Test
    fun aNetworkNamedAfterARebindFromAnotherAddressIsStillAMigration() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromWifi))
        assertEquals(HomeStep.Migrate, watch.up(ethernet, RouteSource.Resolved(ethernetAddress), carried(wifiAddress, wifi, ticket = 2)))
    }

    /**
     * The 2026-09-26 iPhone walk: cellular up beside Wi-Fi, the monitor naming Wi-Fi and reporting both links' addresses,
     * the default route already on cellular. The rebind binds cellular's address and is asked on cellular, so once it
     * landed the monitor naming cellular is no handoff and no reopen.
     */
    @Test
    fun aRebindOntoAnAddressAnotherLinkCarriesIsOnThatLink() {
        val links = linksOf(wifi to wifiAddress, cellular to cellularAddress)
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular))
        assertEquals(cellularAddress, towardOf(fromCellular, onWifi))
        assertEquals(cellular, homeOf(cellularAddress, links, wifi), "the ask names the link that carries the address")
        assertEquals(HomeStep.Stay, watch.up(cellular, fromCellular, carried(cellularAddress, cellular, ticket = 2)))
    }

    /** The same for a migration the route asks for: the socket is on the link that carries its new address. */
    @Test
    fun aMigrationOntoAnAddressAnotherLinkCarriesIsOnThatLink() {
        val links = linksOf(wifi to wifiAddress, cellular to cellularAddress)
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(wifi, fromCellular, onWifi))
        assertEquals(cellular, homeOf(cellularAddress, links, wifi))
        assertEquals(HomeStep.Stay, watch.up(cellular, fromCellular, carried(cellularAddress, cellular, ticket = 2)), "a reopen")
    }

    /**
     * A move toward an address the reported links do not hold yet is asked on no network: which link will carry it is
     * not known, so the loop takes the one the next rehome names rather than the one the monitor named then.
     */
    @Test
    fun aMoveTowardAnAddressNoReportedLinkHoldsNamesNoNetwork() {
        val links = linksOf(wifi to wifiAddress)
        assertEquals(NetworkId.Unidentified, homeOf(cellularAddress, links, wifi))
        assertEquals(wifi, homeOf(cellularAddress, LinkAddresses.NotReported, wifi), "with no links reported, the named one")
    }

    /**
     * A rebind toward cellular's address that never landed leaves the socket on Wi-Fi, as the loop says: the monitor
     * naming cellular is the handoff it is, never a reopen of the Wi-Fi socket. CoreCarriedTests holds the same through
     * the real loop.
     */
    @Test
    fun aRebindThatDidNotLandLeavesTheHandoffAfterItAHandoff() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Rebind, watch.silent(fromCellular))
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, onWifi), "the handoff onto cellular was swallowed")
    }

    /**
     * A reading toward the address a move is under way to is asked again: the connection lets the move under way serve
     * it (RebindLadderTests.aWantAnOpenMoveAlreadyServesAsksNothing), and one refused is asked anew.
     */
    @Test
    fun eachReadingTowardAnotherAddressIsAskedAndTheConnectionJudgesIt() {
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, onWifi))
        assertEquals(HomeStep.Migrate, watch.up(cellular, fromCellular, onWifi))
        assertEquals(HomeStep.Migrate, watch.up(NetworkId.Link(NetworkKind.Cellular, 9L), fromCellular, onWifi), "never a reopen")
    }

    /** One address spelled two ways is one address. */
    @Test
    fun anAddressSpelledAnotherWayIsTheSameAddress() {
        val watch = HomeWatch(OnMove.Migrate)
        val spelled = carried("2001:db8:0:0:0:0:0:3", wifi)
        assertEquals(HomeStep.Stay, watch.up(wifi, RouteSource.Resolved(ethernetAddress), spelled), "the same address read as a move")
    }

    /** No route, no network, or a connection that does not move on its own: a silence asks for nothing. */
    @Test
    fun aSilenceWithNoRouteOrNoNetworkOrNoMovesAsksForNothing() {
        val noRoute = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)
        assertEquals(HomeStep.Stay, HomeWatch(OnMove.Migrate).silent(noRoute))
        val offline = HomeWatch(OnMove.Migrate).apply { offline() }
        assertEquals(HomeStep.Stay, offline.silent(fromWifi))
        assertEquals(HomeStep.Stay, HomeWatch(OnMove.End).silent(fromWifi))
    }

    private fun linksOf(vararg links: Pair<NetworkId, String>): LinkAddresses =
        LinkAddresses.Reported(links.associate { (id, address) -> id to setOf(numeric(address)) })

    private fun numeric(address: String): NumericAddress =
        when (val parsed = NumericAddress.parse(address)) {
            is ParsedAddress.Address -> parsed.address
            is ParsedAddress.NotNumeric -> throw AssertionError("not numeric: $address")
        }
}
