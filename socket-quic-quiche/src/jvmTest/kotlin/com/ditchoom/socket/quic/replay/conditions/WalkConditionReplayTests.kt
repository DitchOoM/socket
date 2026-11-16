package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.replay.conditions.fixtures.iphoneWifiCellularFlap
import com.ditchoom.socket.quic.replay.conditions.fixtures.pixelWifiFlapCoreV4
import com.ditchoom.socket.quic.replay.conditions.fixtures.pixelWifiFlapCoreV6
import com.ditchoom.socket.quic.replay.conditions.fixtures.samsungCellularToWifi
import com.ditchoom.socket.quic.replay.conditions.fixtures.samsungWifiToCellular
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Walk failures as permanent tests: the network conditions and OS events of real recordings, replayed against the core
 * client and server on virtual time ([ConditionReplay]), each held to its outcome and replayed twice to the same one.
 */
class WalkConditionReplayTests {
    /**
     * Pixel, core-v4: Wi-Fi gone for 12 s and back as a new network with the same address. The pre-fix core (3322c1fa)
     * kept sending from the socket of the old network and ended at the idle timeout; this one reopens the socket on the
     * new network and echoes again, within one round trip: the network's return sends the probes a PTO would at once,
     * carrying the echo lost in the outage (a PING alone, as before, resent it only once its acknowledgement showed it
     * lost: 151.6 ms against a 105.9 ms round trip).
     */
    @Test
    fun aWifiFlapBackOnANewNetworkWithTheSameAddressIsSurvived() {
        val conditions = pixelWifiFlapCoreV4
        val rtt = conditions.roundTrip()
        val outage = conditions.link.filterIsInstance<LinkWindow.Outage>().single()
        val usable = usableAt(conditions)
        val replayed =
            replayTwice(
                conditions,
                Expect.Survives(firstEchoWithin = rtt, longestGapAtMost = usable - outage.from + conditions.echoEvery + rtt),
            )
        assertEquals(1, replayed.reopens, "the socket was reopened on the new network once: ${replayed.describe()}")
    }

    /**
     * Pixel, core-v6, the same flap: the new network comes up without a global address, then validated with a new
     * privacy address. The socket moves to that address and the connection migrates: the first echo is answered within
     * two round trips of the address appearing, one to validate the new path and one for the echo.
     */
    @Test
    fun aWifiFlapBackWithANewPrivacyAddressMigratesToIt() {
        survivesTheMoves(pixelWifiFlapCoreV6, moves = 1)
    }

    /** Samsung, Wi-Fi fading for 12 s until cellular takes the route from another address: the connection moves there. */
    @Test
    fun wifiFadingToCellularMigratesWhenCellularTakesTheRoute() {
        survivesTheMoves(samsungWifiToCellular, moves = 1)
    }

    /** Samsung, cellular to Wi-Fi with no outage: the connection moves to Wi-Fi's address at once. */
    @Test
    fun cellularToWifiMigratesWhenWifiTakesTheRoute() {
        survivesTheMoves(samsungCellularToWifi, moves = 1)
    }

    /**
     * iPhone, Wi-Fi to cellular and back to the same Wi-Fi within 3 s: the connection migrates each way, and no echo
     * waits longer than one echo interval and the two round trips a migration costs.
     */
    @Test
    fun aThreeSecondFlapToCellularAndBackMigratesEachWay() {
        val conditions = iphoneWifiCellularFlap
        val replayed = survivesTheMoves(conditions, moves = 2)
        val gap = (replayed.outcome as Outcome.Survived).longestGap
        val bound = conditions.echoEvery + conditions.roundTrip() * 2
        assertTrue(gap <= bound, "an echo waited $gap, over $bound: ${replayed.describe()}")
    }

    /**
     * Replays [conditions] and holds it to surviving [moves] moves of its socket: the first echo after the last
     * disruption is answered within two round trips, and no echo waits longer than the disruption and that.
     */
    private fun survivesTheMoves(
        conditions: Conditions,
        moves: Int,
    ): Replayed {
        val twoRoundTrips = conditions.roundTrip() * 2
        val replayed =
            replayTwice(
                conditions,
                Expect.Survives(
                    firstEchoWithin = twoRoundTrips,
                    longestGapAtMost = usableAt(conditions) - disrupted(conditions) + conditions.echoEvery + twoRoundTrips,
                ),
            )
        assertEquals(moves, replayed.moves, "the socket moved to each new address once: ${replayed.describe()}")
        return replayed
    }

    private fun usableAt(conditions: Conditions): Duration =
        when (val again = conditions.usableAgain()) {
            is Usable.At -> again.at
            Usable.Never, Usable.Undisturbed -> throw AssertionError("the script comes back from its disruption: $again")
        }

    /** When the script first disrupts the connection: an outage, or the OS's first news. */
    private fun disrupted(conditions: Conditions): Duration =
        minOf(
            conditions.link.filterIsInstance<LinkWindow.Outage>().minOfOrNull { it.from } ?: conditions.runFor,
            conditions.os.minOfOrNull { it.at } ?: conditions.runFor,
        )
}
