package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.InterfaceIndex
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkInterfaceInfo
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The `OS-NET` grammar, pinned: what each state a device was really in renders as, how an address
 * family's reach is decided, and that the trace line decodes back to the same facts.
 */
class OsNetworkFactsTests {
    @Test
    fun aRegisteredRadioWithNoBearerRendersEveryFactThatDistinguishesItFromAirplaneMode() {
        assertEquals(
            "state=Offline v4=Absent v6=LinkLocal " +
                "cell=Reported(sim=Ready,reg=InService,data=Disconnected,roaming=Roaming,bearer=Lte) " +
                "links=wlan0=fe80::4c2a:8bff:fe31:9f10",
            OsNetworkFixtures.registeredNoBearer.line,
        )
        assertEquals(
            "state=Offline v4=Absent v6=Absent " +
                "cell=Reported(sim=Ready,reg=PowerOff,data=Disconnected,roaming=NotReported,bearer=None) " +
                "links=-",
            OsNetworkFixtures.airplaneMode.line,
        )
    }

    @Test
    fun theTwoLinesTheAnalyzersFixturesReplayAreTheseOnes() {
        // Pinned verbatim: `device-probe/test_analyze.py` seeds a walk log with exactly these two
        // records, so the Python analyzer is tested against text the probe really writes.
        assertEquals(
            "state=Routable|Link:Wifi:441492361229|Confirmed v4=SiteLocal v6=LinkLocal " +
                "cell=Reported(sim=Ready,reg=InService,data=Disconnected,roaming=Roaming,bearer=Lte) " +
                "links=wlan0=192.168.1.6,fe80::4c2a:8bff:fe31:9f10",
            OsNetworkFixtures.wifiNoGlobalV6.line,
        )
        assertEquals(
            "state=Routable|Link:Cellular:559036687885|Confirmed v4=SiteLocal v6=Global " +
                "cell=Reported(sim=Ready,reg=InService,data=Connected,roaming=Home,bearer=Nr) " +
                "links=rmnet_data0=100.79.14.2,2600:387:f:6e13::41,fe80::9c1e:22ff:fe08:114c",
            OsNetworkFixtures.cellularWithGlobalV6.line,
        )
    }

    @Test
    fun aPlatformThatCannotReportAFieldRendersItsTypedAbsenceRatherThanABlank() {
        // iOS: CoreTelephony still names the radio technology, and exposes no registration, roaming,
        // data state or SIM state at all. Each of those says NotReported — never an empty value, and
        // never a plausible-looking default like Home or Disconnected.
        assertEquals(
            "state=Routable|Link:Wifi:14|Unobserved v4=SiteLocal v6=LinkLocal " +
                "cell=Reported(sim=NotReported,reg=NotReported,data=NotReported,roaming=NotReported,bearer=Lte) " +
                "links=en0=192.168.1.42,fe80::10ab:5eff:fe22:d301;pdp_ip0=100.108.7.19",
            OsNetworkFixtures.iosWifiWithCellularIdle.line,
        )
        // A host with no cellular radio says so once, rather than five times.
        assertTrue(OsNetworkFixtures.noCellularRadio.line.contains(" cell=NotReported "), OsNetworkFixtures.noCellularRadio.line)
    }

    @Test
    fun anAddressFamilyReachIsTheWidestScopeAnyUpLinkCarries() {
        assertEquals(AddressScope.Absent, OsNetworkFixtures.registeredNoBearer.v4)
        assertEquals(AddressScope.LinkLocal, OsNetworkFixtures.registeredNoBearer.v6)
        // The walk's whole IPv6 question: a phone on this Wi-Fi has IPv4 and NO routable IPv6.
        assertEquals(AddressScope.SiteLocal, OsNetworkFixtures.wifiNoGlobalV6.v4)
        assertEquals(AddressScope.LinkLocal, OsNetworkFixtures.wifiNoGlobalV6.v6)
        // ...and on the carrier bearer it has a global IPv6 behind a CGNAT IPv4.
        assertEquals(AddressScope.SiteLocal, OsNetworkFixtures.cellularWithGlobalV6.v4)
        assertEquals(AddressScope.Global, OsNetworkFixtures.cellularWithGlobalV6.v6)
        assertEquals(AddressScope.Global, OsNetworkFixtures.noCellularRadio.v6)
        // A link with no address at all is Absent on both, not a missing value.
        assertEquals(AddressScope.Absent, OsNetworkFixtures.wifiScanning.v4)
        assertEquals(AddressScope.Absent, OsNetworkFixtures.wifiScanning.v6)
    }

    @Test
    fun theTraceLineDecodesBackToTheSameFactsForEveryStateADeviceWasIn() {
        for (facts in OsNetworkFixtures.all) {
            val event = TraceEvent.OsNet(1234.milliseconds, facts)
            assertEquals(event, TraceEvent.parse(event.toString()), "round trip of $event")
        }
    }

    @Test
    fun aPlatformLabelCarryingTheGrammarsOwnDelimitersStillRoundTrips() {
        val hostile =
            OsNetworkFacts(
                state =
                    NetworkState.Routable(
                        NetworkId.Link(NetworkKind.Other("odd|kind;with=delims, and (parens)"), 7L),
                        InternetAccess.Unobserved,
                    ),
                cellular =
                    CellularStatus.Reported(
                        sim = SimState.Ready,
                        registration = CellularRegistration.InService,
                        data = CellularData.Connected,
                        roaming = CellularRoaming.Home,
                        bearer = CellularBearer.Other("TD-SCDMA|weird;name=x"),
                    ),
                links = listOf(OsLink("if;0=a|b", listOf("10.0.0.1"))),
            )
        val event = TraceEvent.OsNet(0.milliseconds, hostile)

        assertEquals(event, TraceEvent.parse(event.toString()))
        assertEquals(1, event.toString().count { it == '\n' } + 1, "an event is always one line")
    }

    @Test
    fun theOsFactsAreCarriedThroughAFixtureWindowRatherThanDroppedAsAnObservation() {
        // isInput is what decides whether a windowed fixture keeps the record of why the monitor said
        // what it said. An observation would be filtered out of the window and the explanation lost.
        assertTrue(TraceEvent.OsNet(0.milliseconds, OsNetworkFixtures.airplaneMode).isInput)
    }

    @Test
    fun onlyLinksThatAreUpAndNotLoopbackAreRecordedAndTheZoneSuffixIsDropped() {
        fun scanned(
            name: String,
            index: Long,
            addresses: List<String>,
            isUp: Boolean,
            isLoopback: Boolean,
        ) = NetworkInterfaceInfo(name, InterfaceIndex(index), NetworkKind.Other("scan"), addresses, isUp, isLoopback)

        val interfaces =
            listOf(
                scanned("lo", 1, listOf("127.0.0.1", "::1"), isUp = true, isLoopback = true),
                scanned("wlan0", 4, listOf("192.168.1.6", "fe80::1%wlan0"), isUp = true, isLoopback = false),
                scanned("rmnet1", 9, listOf("10.1.2.3"), isUp = false, isLoopback = false),
            )

        assertEquals(listOf(OsLink("wlan0", listOf("192.168.1.6", "fe80::1"))), osLinksFrom(interfaces))
    }
}
