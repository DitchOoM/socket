package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind

/**
 * States a device really was in, as [OsNetworkFacts], so an analysis of them needs no device.
 *
 * Several of these render as the *same* ladder value — [NetworkState.Offline], or a plain
 * `Routable` — which is exactly why they are here: they are indistinguishable to everything a walk
 * recorded before this record existed, and they call for opposite conclusions. [registeredNoBearer]
 * is a phone with a working radio and no data at all; [airplaneMode] is a phone with the radio off;
 * [wifiScanning] is a phone between access points.
 *
 * The handles are the platforms' own opaque identities and are only ever compared.
 */
public object OsNetworkFixtures {
    /**
     * A phone with no usable network at all while its radio is registered: SIM loaded, LTE,
     * **in service**, **domestic roaming**, and no data bearer — because data roaming is off. Wi-Fi
     * is enabled but only scanning, so nothing has an address but a link-local one.
     *
     * To the ladder alone this is one `Offline`, identical to [airplaneMode] and to a basement.
     */
    public val registeredNoBearer: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Offline,
            cellular =
                CellularStatus.Reported(
                    sim = SimState.Ready,
                    registration = CellularRegistration.InService,
                    data = CellularData.Disconnected,
                    roaming = CellularRoaming.Roaming,
                    bearer = CellularBearer.Lte,
                ),
            links = listOf(OsLink("wlan0", listOf("fe80::4c2a:8bff:fe31:9f10"))),
        )

    /** The same phone once it joins a known Wi-Fi: validated IPv4, and still no IPv6 beyond link-local. */
    public val wifiNoGlobalV6: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, 441492361229L), InternetAccess.Observed.Confirmed),
            cellular = registeredNoBearer.cellular,
            links = listOf(OsLink("wlan0", listOf("192.168.1.6", "fe80::4c2a:8bff:fe31:9f10"))),
        )

    /**
     * Wi-Fi associating: a link, no address, and the cellular side unchanged. The rung is
     * [NetworkState.Offline] on a phone whose radio is perfectly healthy.
     */
    public val wifiScanning: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Offline,
            cellular = registeredNoBearer.cellular,
            links = listOf(OsLink("wlan0", emptyList())),
        )

    /** Airplane mode: the radio is off, and nothing is up. */
    public val airplaneMode: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Offline,
            cellular =
                CellularStatus.Reported(
                    sim = SimState.Ready,
                    registration = CellularRegistration.PowerOff,
                    data = CellularData.Disconnected,
                    roaming = CellularRoaming.NotReported,
                    bearer = CellularBearer.None,
                ),
            links = emptyList(),
        )

    /**
     * A carrier data bearer: 5G, home network, a CGNAT IPv4 and a **global** IPv6 — the configuration
     * a v6 lane needs and that the Wi-Fi in [wifiNoGlobalV6] cannot supply.
     */
    public val cellularWithGlobalV6: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Routable(NetworkId.Link(NetworkKind.Cellular, 559036687885L), InternetAccess.Observed.Confirmed),
            cellular =
                CellularStatus.Reported(
                    sim = SimState.Ready,
                    registration = CellularRegistration.InService,
                    data = CellularData.Connected,
                    roaming = CellularRoaming.Home,
                    bearer = CellularBearer.Nr,
                ),
            links =
                listOf(
                    OsLink("rmnet_data0", listOf("100.79.14.2", "2600:387:f:6e13::41", "fe80::9c1e:22ff:fe08:114c")),
                ),
        )

    /**
     * An iPhone on a home Wi-Fi. Apple reports no reachability verdict at all
     * ([InternetAccess.Unobserved]), and iOS exposes no registration, roaming or data state — so the
     * only cellular fact on the platform is the radio technology, and every other field says so.
     */
    public val iosWifiWithCellularIdle: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Routable(NetworkId.Link(NetworkKind.Wifi, 14L), InternetAccess.Unobserved),
            cellular =
                CellularStatus.Reported(
                    sim = SimState.NotReported,
                    registration = CellularRegistration.NotReported,
                    data = CellularData.NotReported,
                    roaming = CellularRoaming.NotReported,
                    bearer = CellularBearer.Lte,
                ),
            links =
                listOf(
                    OsLink("en0", listOf("192.168.1.42", "fe80::10ab:5eff:fe22:d301")),
                    OsLink("pdp_ip0", listOf("100.108.7.19")),
                ),
        )

    /** A host with no cellular radio to describe — the honest reading on a desktop or a Wi-Fi-only tablet. */
    public val noCellularRadio: OsNetworkFacts =
        OsNetworkFacts(
            state = NetworkState.Routable(NetworkId.Link(NetworkKind.Ethernet, 2L), InternetAccess.Unobserved),
            cellular = CellularStatus.NotReported,
            links = listOf(OsLink("en0", listOf("10.0.1.8", "2001:db8::1"))),
        )

    /** Every fixture, in the order they are declared — what a round-trip or rendering test walks. */
    public val all: List<OsNetworkFacts> =
        listOf(
            registeredNoBearer,
            wifiNoGlobalV6,
            wifiScanning,
            airplaneMode,
            cellularWithGlobalV6,
            iosWifiWithCellularIdle,
            noCellularRadio,
        )
}
