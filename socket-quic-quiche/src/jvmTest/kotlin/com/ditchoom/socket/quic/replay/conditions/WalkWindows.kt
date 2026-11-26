package com.ditchoom.socket.quic.replay.conditions

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seven windows of recorded phone walks, each as the conditions a connection met from the instant of the last datagram
 * it received: what the link did, what the OS said and when. Each opens with [LEAD_IN] of a healthy link, in which the
 * replayed handshake completes and the echo runs, so the window's own time zero is [LEAD_IN] on the script's clock.
 * Sends that fail on the dead Wi-Fi socket are not scripted: a dead link drops what they would have sent.
 */
internal object WalkWindows {
    /** A healthy link before the window opens, long enough for the handshake and a few echoes. */
    val LEAD_IN = 2.seconds

    /** The idle timeout the walks ran with. */
    val IDLE = 30.seconds

    /** How long the abrupt handoff is run: the window's 13.4 s and a few seconds more, to see the echoes resume. */
    private val ABRUPT_RUN = 18.seconds

    /** How long the landing handoff is run: the window's 18.7 s of traffic. */
    private val LANDING_RUN = 19.seconds

    private val lead = DelayRange(20.milliseconds, 40.milliseconds)
    private val none = PerMille(0)
    private val all = PerMille(1000)
    private val wifi = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated)

    /** The documentation address a route from the cellular network leaves from. */
    private const val CELLULAR_ADDRESS = "198.51.100.1"

    /** A window [at] seconds in, on the script's clock. */
    private fun inWindow(at: Duration): Duration = LEAD_IN + at

    private fun cellular(validation: Validation) = NetUp(NetHandle(NetKind.Cellular, 1), validation)

    private fun walk(
        name: String,
        source: String,
        start: NetUp,
        link: List<LinkWindow>,
        os: List<OsEvent>,
        runFor: Duration,
    ): Conditions =
        Conditions(
            name = name,
            source = source,
            family = Family.V4,
            evidence = Evidence.ClientOnly,
            start = start,
            echoEvery = 250.milliseconds,
            idleTimeout = IDLE,
            link = link,
            os = os,
            runFor = runFor,
        )

    /** Wi-Fi dead after the last datagram in; the OS reports cellular [reportedAt] into the window; nothing ever arrives. */
    private fun deadLinkHandoff(
        name: String,
        source: String,
        reportedAt: Duration,
        windowLength: Duration,
    ): Conditions =
        walk(
            name,
            source,
            wifi,
            link = listOf(flowing(Duration.ZERO, LEAD_IN), LinkWindow.Outage(LEAD_IN, inWindow(windowLength) + IDLE)),
            os =
                listOf(
                    OsEvent.Up(inWindow(reportedAt), cellular(Validation.Unobserved)),
                    OsEvent.Route(inWindow(reportedAt), RouteFrom.Other(CELLULAR_ADDRESS)),
                ),
            runFor = inWindow(windowLength) + IDLE,
        )

    private fun flowing(
        from: Duration,
        until: Duration,
    ) = LinkWindow.Flowing(from, until, lead, none, none)

    /** cellular reported 3.6 s after the last datagram; 45 sends failed on the old socket. */
    val deadLinkHandoffCellularAt3600ms: Conditions =
        deadLinkHandoff("Dead-link handoff", "recorded phone walk window", 3_564.milliseconds, 30_239.milliseconds)

    /** cellular reported 0.8 s after the last datagram; 94 sends failed on the old socket. */
    val deadLinkHandoffCellularAt800ms: Conditions =
        deadLinkHandoff("Dead-link handoff", "recorded phone walk window", 768.milliseconds, 30_283.milliseconds)

    /**
     * the downlink went dark after one datagram, the uplink kept flowing, and
     * cellular was reported 1.1 s in.
     */
    val downlinkBlackout: Conditions =
        walk(
            "Downlink blackout",
            "recorded phone walk window",
            wifi,
            link =
                listOf(
                    flowing(Duration.ZERO, LEAD_IN),
                    LinkWindow.Flowing(LEAD_IN, inWindow(30_270.milliseconds) + IDLE, lead, none, all),
                ),
            os =
                listOf(
                    OsEvent.Up(inWindow(1_083.milliseconds), cellular(Validation.Unobserved)),
                    OsEvent.Route(inWindow(1_083.milliseconds), RouteFrom.Other(CELLULAR_ADDRESS)),
                ),
            runFor = inWindow(30_270.milliseconds) + IDLE,
        )

    /**
     * A recorded walk: Wi-Fi dark from the last datagram, Offline at 15.1 s, then a cellular
     * link that is up but has not yet passed traffic at 15.4 s, which never does.
     */
    val cellularNotYetPassing: Conditions =
        walk(
            "Cellular not yet passing",
            "recorded phone walk window",
            wifi,
            link = listOf(flowing(Duration.ZERO, LEAD_IN), LinkWindow.Outage(LEAD_IN, inWindow(30_220.milliseconds) + IDLE)),
            os =
                listOf(
                    OsEvent.Offline(inWindow(15_148.milliseconds)),
                    OsEvent.Route(inWindow(15_148.milliseconds), RouteFrom.NoRoute),
                    OsEvent.Up(inWindow(15_421.milliseconds), cellular(Validation.Pending)),
                    OsEvent.Route(inWindow(15_421.milliseconds), RouteFrom.Other(CELLULAR_ADDRESS)),
                ),
            runFor = inWindow(30_220.milliseconds) + IDLE,
        )

    /**
     * A recorded walk, the positive control: Wi-Fi silent from the last datagram, Offline at
     * 10.9 s, cellular up but pending at 11.1 s and carrying again from 11.5 s, validated at 12.2 s.
     */
    val abruptHandoff: Conditions =
        walk(
            "Abrupt handoff",
            "recorded phone walk window",
            wifi,
            link =
                listOf(
                    flowing(Duration.ZERO, LEAD_IN),
                    LinkWindow.Outage(LEAD_IN, inWindow(11_558.milliseconds)),
                    LinkWindow.Flowing(
                        inWindow(11_558.milliseconds),
                        inWindow(ABRUPT_RUN),
                        DelayRange(80.milliseconds, 260.milliseconds),
                        none,
                        none,
                    ),
                ),
            os =
                listOf(
                    OsEvent.Offline(inWindow(10_872.milliseconds)),
                    OsEvent.Route(inWindow(10_872.milliseconds), RouteFrom.NoRoute),
                    OsEvent.Up(inWindow(11_064.milliseconds), cellular(Validation.Pending)),
                    OsEvent.Route(inWindow(11_064.milliseconds), RouteFrom.Other(CELLULAR_ADDRESS)),
                    OsEvent.Up(inWindow(12_180.milliseconds), cellular(Validation.Validated)),
                ),
            runFor = inWindow(ABRUPT_RUN),
        )

    /**
     * A recorded walk: the modem dropped out of service and the OS reported the
     * cellular network blocked, its data suspended, 23.8 s after the last datagram; nothing more arrived.
     */
    val dataSuspended: Conditions =
        walk(
            "Data suspended",
            "recorded phone walk window",
            cellular(Validation.Validated),
            link = listOf(flowing(Duration.ZERO, LEAD_IN), LinkWindow.Outage(LEAD_IN, inWindow(30_141.milliseconds) + IDLE)),
            os = listOf(OsEvent.Offline(inWindow(23_820.milliseconds))),
            runFor = inWindow(30_141.milliseconds) + IDLE,
        )

    /**
     * A recorded walk, the landing: Wi-Fi gone, cellular reported 4.5 s after the last
     * datagram over Wi-Fi and carrying within 0.1 s, a round trip of about 250 ms.
     */
    val landingHandoff: Conditions =
        walk(
            "Landing handoff",
            "recorded phone walk window",
            wifi,
            link =
                listOf(
                    flowing(Duration.ZERO, LEAD_IN),
                    LinkWindow.Outage(LEAD_IN, inWindow(4_635.milliseconds)),
                    LinkWindow.Flowing(
                        inWindow(4_635.milliseconds),
                        inWindow(LANDING_RUN),
                        DelayRange(100.milliseconds, 140.milliseconds),
                        none,
                        none,
                    ),
                ),
            os =
                listOf(
                    OsEvent.Up(inWindow(4_535.milliseconds), cellular(Validation.Unobserved)),
                    OsEvent.Route(inWindow(4_535.milliseconds), RouteFrom.Other(CELLULAR_ADDRESS)),
                ),
            runFor = inWindow(LANDING_RUN),
        )
}
