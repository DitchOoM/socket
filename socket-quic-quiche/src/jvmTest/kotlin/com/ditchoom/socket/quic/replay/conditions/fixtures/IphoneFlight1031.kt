@file:Suppress("ktlint")

package com.ditchoom.socket.quic.replay.conditions.fixtures

import com.ditchoom.socket.quic.replay.conditions.Conditions
import com.ditchoom.socket.quic.replay.conditions.DelayRange
import com.ditchoom.socket.quic.replay.conditions.Evidence
import com.ditchoom.socket.quic.replay.conditions.Family
import com.ditchoom.socket.quic.replay.conditions.LinkWindow
import com.ditchoom.socket.quic.replay.conditions.NetHandle
import com.ditchoom.socket.quic.replay.conditions.NetKind
import com.ditchoom.socket.quic.replay.conditions.NetUp
import com.ditchoom.socket.quic.replay.conditions.OsEvent
import com.ditchoom.socket.quic.replay.conditions.PerMille
import com.ditchoom.socket.quic.replay.conditions.RouteFrom
import com.ditchoom.socket.quic.replay.conditions.Validation
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One connection of the 2026-10-31 flight, as its hops were logged (iPhone lane v4): opened on the plane's Wi-Fi, which
 * twice went silent with no word from the OS and was left for a fresh port on the same link; after landing, cellular,
 * the airport's Wi-Fi, cellular again and home Wi-Fi. iOS names a Wi-Fi by its interface, so each Wi-Fi is the same
 * network to the OS, reached from a new address. Written by hand from the hop log, its recording no longer kept: the
 * hops' order and kind are the field's; the spacing and the 35 ms one way are the 4.x scenario's.
 */
internal val iphoneFlight1031: Conditions =
    Conditions(
        name = "IphoneFlight1031",
        source = "iPhone flight walk 2026-10-31 (4.x lane v4), one connection's hops; hand-written",
        family = Family.V4,
        evidence = Evidence.ClientOnly,
        start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
        echoEvery = 250.milliseconds,
        idleTimeout = 30.seconds,
        link = listOf(
            LinkWindow.Flowing(0.seconds, 60.seconds, DelayRange(35.milliseconds, 35.milliseconds), PerMille(0), PerMille(0)),
        ),
        os = listOf(
            OsEvent.Up(26.seconds, NetUp(NetHandle(NetKind.Cellular, 1), Validation.Validated)),
            OsEvent.Route(26.seconds, RouteFrom.Other("198.51.100.1")),
            OsEvent.Up(33.seconds, NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated)),
            OsEvent.Route(33.seconds, RouteFrom.Other("192.0.2.20")),
            OsEvent.Up(40.seconds, NetUp(NetHandle(NetKind.Cellular, 1), Validation.Validated)),
            OsEvent.Route(40.seconds, RouteFrom.Other("198.51.100.1")),
            OsEvent.Up(47.seconds, NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated)),
            OsEvent.Route(47.seconds, RouteFrom.Other("192.0.2.30")),
        ),
        runFor = 55.seconds,
        // The plane's Wi-Fi stalls, twice: every socket the client holds goes dark, the OS still calling the link up.
        strands = listOf(5.seconds, 15.seconds),
    )
