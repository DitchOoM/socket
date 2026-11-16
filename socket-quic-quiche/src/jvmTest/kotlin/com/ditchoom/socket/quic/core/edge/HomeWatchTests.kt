package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.RouteProbeFailure
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 2026-11-16 Pixel walk, read reading by reading: Wi-Fi off for 10 s, back as a new Android network (handle
 * 518801772557 then 523096739853), first unvalidated with only the v4 address, then validated with a new v6 privacy
 * address. Addresses are documentation ranges.
 */
class HomeWatchTests {
    private val before = NetworkId.Link(NetworkKind.Wifi, 518_801_772_557L)
    private val after = NetworkId.Link(NetworkKind.Wifi, 523_096_739_853L)
    private val noRoute = RouteSource.Unresolved(RouteProbeFailure.SourceAddressUnknown)

    @Test
    fun theSameV4AddressOnANewNetworkReopensTheSocketOnceAndValidationLaterChangesNothing() {
        val watch = HomeWatch("192.0.2.10", before)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")), "unvalidated, address back")
        assertEquals(HomeStep.Stay, watch.up(after, RouteSource.Resolved("192.0.2.10")), "validated: already rehomed")
    }

    @Test
    fun aV6NetworkBackWithoutItsAddressWaitsAndThenWithANewPrivacyAddressEndsAsMoved() {
        val watch = HomeWatch("2001:db8::3eed", before)
        assertEquals(HomeStep.Gone, watch.offline())
        assertEquals(HomeStep.Stay, watch.up(after, noRoute), "unvalidated, only link-local: still no route")
        assertEquals(HomeStep.Moved, watch.up(after, RouteSource.Resolved("2001:db8::cc30")), "validated, new address")
        assertEquals(HomeStep.Stay, watch.offline(), "a moved connection is told nothing more")
    }

    @Test
    fun anAddressThatArrivesLateOnTheNewNetworkStillReopensTheSocket() {
        val watch = HomeWatch("192.0.2.10", before)
        watch.offline()
        assertEquals(HomeStep.Stay, watch.up(after, noRoute))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun theSameNetworkBackWithTheSameAddressSendsAtOnceWithoutReopening() {
        val watch = HomeWatch("192.0.2.10", before)
        watch.offline()
        assertEquals(HomeStep.Usable, watch.up(before, RouteSource.Resolved("192.0.2.10")))
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10")))
    }

    @Test
    fun aMonitorThatCannotNameNetworksTakesTheFirstOneItNamesAsHome() {
        val watch = HomeWatch("192.0.2.10", NetworkId.Unidentified)
        assertEquals(HomeStep.Stay, watch.up(before, RouteSource.Resolved("192.0.2.10")))
        assertEquals(HomeStep.Rehome, watch.up(after, RouteSource.Resolved("192.0.2.10")))
    }
}
