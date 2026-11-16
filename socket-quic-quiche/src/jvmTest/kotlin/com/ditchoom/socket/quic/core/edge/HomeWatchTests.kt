package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 2026-11-16 Pixel walk, read reading by reading: Wi-Fi off for 10 s, back as a new Android network (handle
 * 518801772557 then 523096739853), first unvalidated with only the v4 address, then validated with a new v6 privacy
 * address. Addresses are documentation ranges.
 */
class HomeWatchTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val before = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
    private val after = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
    private val noRoute = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)

    @Test
    fun theSameV4AddressOnANewNetworkReopensTheSocketOnceAndValidationLaterChangesNothing() {
        val watch = HomeWatch("192.0.2.10", before, OnMove.End)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")), "unvalidated, address back")
        assertEquals(HomeStep.Stay, watch.up(after, RouteSource.Resolved("192.0.2.10")), "validated: already rehomed")
    }

    @Test
    fun aV6NetworkBackWithoutItsAddressWaitsAndThenWithANewPrivacyAddressEndsAsMoved() {
        val watch = HomeWatch("2001:db8::3eed", before, OnMove.End)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Stay, watch.up(after, noRoute), "unvalidated, only link-local: still no route")
        assertEquals(HomeStep.Moved, watch.up(after, RouteSource.Resolved("2001:db8::cc30")), "validated, new address")
        assertEquals(HomeStep.Stay, watch.offline(), "a moved connection is told nothing more")
    }

    @Test
    fun anAddressThatArrivesLateOnTheNewNetworkStillReopensTheSocket() {
        val watch = HomeWatch("192.0.2.10", before, OnMove.End)
        watch.offline()
        assertEquals(HomeStep.Stay, watch.up(after, noRoute))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun theSameNetworkBackWithTheSameAddressSendsAtOnceWithoutReopening() {
        val watch = HomeWatch("192.0.2.10", before, OnMove.End)
        watch.offline()
        assertEquals(HomeStep.Usable, watch.up(before, RouteSource.Resolved("192.0.2.10")))
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun aMonitorThatCannotNameNetworksTakesTheFirstOneItNamesAsHome() {
        val watch = HomeWatch("192.0.2.10", NetworkId.Unidentified, OnMove.End)
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10")))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun aV6NetworkBackWithANewPrivacyAddressMigratesThereAndThatAddressIsHomeFromThen() {
        val watch = HomeWatch("2001:db8::3eed", before, OnMove.Migrate)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Stay, watch.up(after, noRoute), "unvalidated, only link-local: still no route")
        assertEquals(HomeStep.Migrate, watch.up(after, RouteSource.Resolved("2001:db8::cc30")), "validated, new address")
        assertEquals("2001:db8::cc30", watch.host())
        assertEquals(HomeStep.Gone, watch.up(after, noRoute), "the route flickers away")
        assertEquals(HomeStep.Usable, watch.up(after, RouteSource.Resolved("2001:db8::cc30")), "and back: already there")
    }

    @Test
    fun wifiToCellularAndBackMigratesEachWay() {
        val cellular = NetworkId.Link(NetworkKind.Cellular, 1L)
        val watch = HomeWatch("192.0.2.10", before, OnMove.Migrate)
        assertEquals(HomeStep.Migrate, watch.up(cellular, RouteSource.Resolved("198.51.100.7")))
        assertEquals(HomeStep.Stay, watch.up(cellular, RouteSource.Resolved("198.51.100.7")))
        assertEquals(HomeStep.Migrate, watch.up(before, RouteSource.Resolved("192.0.2.10")), "Wi-Fi back: its address is new again")
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun onlyAnAutomaticPolicyMoves() {
        assertEquals(OnMove.Migrate, onMoveOf(MigrationPolicy.Automatic))
        assertEquals(OnMove.End, onMoveOf(MigrationPolicy.Manual))
        assertEquals(OnMove.End, onMoveOf(MigrationPolicy.Forbidden))
    }
}
