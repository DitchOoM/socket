package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.replay.conditions.fixtures.iphoneWifiCellularFlap
import com.ditchoom.socket.quic.replay.conditions.fixtures.pixelWifiFlapCoreV4
import com.ditchoom.socket.quic.replay.conditions.fixtures.pixelWifiFlapCoreV6
import com.ditchoom.socket.quic.replay.conditions.fixtures.samsungCellularToWifi
import com.ditchoom.socket.quic.replay.conditions.fixtures.samsungWifiToCellular
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Walk failures as permanent tests: the network conditions and OS events of real recordings, replayed against the core
 * client and server on virtual time ([ConditionReplay]), each held to its outcome and replayed twice to the same one.
 */
class WalkConditionReplayTests {
    /**
     * Pixel, core-v4: Wi-Fi gone for 12 s and back as a new network with the same address. The pre-fix core (3322c1fa)
     * kept sending from the socket of the old network and ended at the idle timeout; this one reopens the socket on the
     * new network and echoes again. Its first echo takes two round trips: the nudge is a PING, and the echo lost in the
     * outage is resent once the PING's acknowledgement shows it lost.
     */
    @Test
    fun aWifiFlapBackOnANewNetworkWithTheSameAddressIsSurvived() {
        val conditions = pixelWifiFlapCoreV4
        val rtt = conditions.roundTrip()
        val outage = conditions.link.filterIsInstance<LinkWindow.Outage>().single()
        val usable =
            when (val again = conditions.usableAgain()) {
                is Usable.At -> again.at
                Usable.Never, Usable.Undisturbed -> throw AssertionError("the script comes back from its outage: $again")
            }
        val replayed =
            replayTwice(
                conditions,
                Expect.Survives(firstEchoWithin = rtt * 2, longestGapAtMost = usable - outage.from + conditions.echoEvery + rtt * 2),
            )
        assertEquals(1, replayed.reopens, "the socket was reopened on the new network once: ${replayed.describe()}")
    }

    /**
     * Pixel, core-v6, the same flap: the new network comes up without a global address, then validated with a new
     * privacy address. The connection ends as LocalAddressGone when the new address appears, not as NetworkLost at the
     * idle timeout as the pre-fix core did.
     */
    @Test
    fun aWifiFlapBackWithANewPrivacyAddressEndsPromptlyAsLocalAddressGone() {
        val conditions = pixelWifiFlapCoreV6
        replayTwice(conditions, Expect.Ends(ConnEnd.LocalAddressGone::class, notAfter = firstUpAfterAnOutage(conditions) + 1.seconds))
    }

    // The 4.x walk's handoffs: the core does not migrate yet, so each ends when the route leaves from another address.
    // Migration must turn these into Survived.

    @Test
    fun wifiFadingToCellularEndsAsLocalAddressGoneWhenCellularTakesTheRoute() {
        val conditions = samsungWifiToCellular
        replayTwice(conditions, Expect.Ends(ConnEnd.LocalAddressGone::class, notAfter = routeMoved(conditions) + PROMPT))
    }

    @Test
    fun cellularToWifiEndsAsLocalAddressGoneWhenWifiTakesTheRoute() {
        val conditions = samsungCellularToWifi
        replayTwice(conditions, Expect.Ends(ConnEnd.LocalAddressGone::class, notAfter = routeMoved(conditions) + PROMPT))
    }

    @Test
    fun aThreeSecondFlapToCellularAndBackEndsAsLocalAddressGoneAtTheFlap() {
        val conditions = iphoneWifiCellularFlap
        replayTwice(conditions, Expect.Ends(ConnEnd.LocalAddressGone::class, notAfter = routeMoved(conditions) + PROMPT))
    }

    private fun firstUpAfterAnOutage(conditions: Conditions): Duration {
        val outage = conditions.link.filterIsInstance<LinkWindow.Outage>().first()
        return conditions.os
            .filterIsInstance<OsEvent.Up>()
            .first { it.at >= outage.from }
            .at
    }

    private fun routeMoved(conditions: Conditions): Duration =
        conditions.os
            .filterIsInstance<OsEvent.Route>()
            .first { it.route is RouteFrom.Other }
            .at

    private companion object {
        val PROMPT = 500.milliseconds
    }
}
