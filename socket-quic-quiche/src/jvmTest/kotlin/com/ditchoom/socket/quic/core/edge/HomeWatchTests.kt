package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.conn.Carried
import com.ditchoom.socket.quic.core.udp.SocketTicket
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 2026-11-16 Pixel walk, read reading by reading: Wi-Fi off for 10 s, back as a new Android network (handle
 * 518801772557 then 523096739853), first unvalidated with only the v4 address, then validated with a new v6 privacy
 * address. Addresses are documentation ranges. Where the socket is, each reading is handed as the loop publishes it.
 */
class HomeWatchTests {
    private val before = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
    private val after = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
    private val noRoute = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)

    @Test
    fun theSameV4AddressOnANewNetworkIsARehomeUntilTheLoopSaysTheSocketIsThere() {
        val watch = HomeWatch(OnMove.End)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", before)), "unvalidated")
        assertEquals(HomeStep.Stay, watch.up(after, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", after)), "reopened there")
    }

    @Test
    fun aV6NetworkBackWithoutItsAddressWaitsAndThenWithANewPrivacyAddressEndsAsMoved() {
        val watch = HomeWatch(OnMove.End)
        val home = carried("2001:db8::3eed", before)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Stay, watch.up(after, noRoute, home), "unvalidated, only link-local: still no route")
        assertEquals(HomeStep.Moved, watch.up(after, RouteSource.Resolved("2001:db8::cc30"), home), "validated, new address")
        assertEquals(HomeStep.Stay, watch.offline(), "a moved connection is told nothing more")
    }

    @Test
    fun anAddressThatArrivesLateOnTheNewNetworkStillReopensTheSocket() {
        val watch = HomeWatch(OnMove.End)
        val home = carried("192.0.2.10", before)
        watch.offline()
        assertEquals(HomeStep.Stay, watch.up(after, noRoute, home))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10"), home))
    }

    @Test
    fun theSameNetworkBackWithTheSameAddressSendsAtOnceWithoutReopening() {
        val watch = HomeWatch(OnMove.End)
        val home = carried("192.0.2.10", before)
        watch.offline()
        assertEquals(HomeStep.Usable, watch.up(before, RouteSource.Resolved("192.0.2.10"), home))
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10"), home))
    }

    /** A socket on no named network is rehomed onto the first one named, which the loop takes without a reopen. */
    @Test
    fun aSocketOnNoNamedNetworkIsRehomedOntoTheFirstOneNamed() {
        val watch = HomeWatch(OnMove.End)
        assertEquals(HomeStep.Rehome, watch.up(before, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", NetworkId.Unidentified)))
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", before)))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", before)))
    }

    @Test
    fun aV6NetworkBackWithANewPrivacyAddressMigratesThereAndThatAddressIsHomeOnceTheLoopCarriesThere() {
        val watch = HomeWatch(OnMove.Migrate)
        val home = carried("2001:db8::3eed", before)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Stay, watch.up(after, noRoute, home), "unvalidated, only link-local: still no route")
        val fromNew = RouteSource.Resolved("2001:db8::cc30")
        assertEquals(HomeStep.Migrate, watch.up(after, fromNew, home), "validated, new address")
        assertEquals("2001:db8::cc30", towardOf(fromNew, home))
        val moved = carried("2001:db8::cc30", after, ticket = 2)
        assertEquals(HomeStep.Gone, watch.up(after, noRoute, moved), "the route flickers away")
        assertEquals(HomeStep.Usable, watch.up(after, fromNew, moved), "and back: already there")
    }

    @Test
    fun wifiToCellularAndBackMigratesEachWay() {
        val cellular = NetworkId.Link(NetworkKind.Cellular, 1L)
        val watch = HomeWatch(OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, RouteSource.Resolved("198.51.100.7"), carried("192.0.2.10", before)))
        val onCellular = carried("198.51.100.7", cellular, ticket = 2)
        assertEquals(HomeStep.Stay, watch.up(cellular, RouteSource.Resolved("198.51.100.7"), onCellular))
        assertEquals(
            HomeStep.Migrate,
            watch.up(before, RouteSource.Resolved("192.0.2.10"), onCellular),
            "Wi-Fi back: its address is new again",
        )
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10"), carried("192.0.2.10", before, ticket = 3)))
    }

    @Test
    fun onlyAnAutomaticPolicyMoves() {
        assertEquals(OnMove.Migrate, onMoveOf(MigrationPolicy.Automatic))
        assertEquals(OnMove.End, onMoveOf(MigrationPolicy.Manual))
        assertEquals(OnMove.End, onMoveOf(MigrationPolicy.Forbidden))
    }
}

/** The socket [ticket] carrying on [host], on [network], as the loop publishes it. */
internal fun carried(
    host: String,
    network: NetworkId,
    ticket: Int = 1,
): Carried = Carried(SocketTicket(ticket), InetSocketAddress(host, 0).toPathKey(), network)
