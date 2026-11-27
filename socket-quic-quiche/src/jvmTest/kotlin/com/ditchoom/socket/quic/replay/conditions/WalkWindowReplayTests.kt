package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seven windows of recorded phone walks ([WalkWindows]) replayed against the core client and server on virtual time.
 * Five are failures: the old socket's sends fail or nothing comes back, and the connection ends on its idle deadline,
 * typed, neither sooner (a dead socket's refused sends or an OS report alone never end it) nor later. Two are the
 * positive controls: a handoff that carried the connection to cellular, which survives it.
 */
class WalkWindowReplayTests {
    /** Cellular reported 3.6 s in while every send on the old Wi-Fi socket fails: the connection moves, nothing answers, it ends at the deadline. */
    @Test
    fun aDeadLinkHandoffReportedLateEndsOnTheIdleDeadline() {
        endsOnTheIdleDeadline(WalkWindows.deadLinkHandoffCellularAt3600ms, ConnEnd.NoUsablePath::class, moves = 1, rebinds = 1)
    }

    /** The same, cellular reported 0.8 s in. */
    @Test
    fun aDeadLinkHandoffReportedEarlyEndsOnTheIdleDeadline() {
        endsOnTheIdleDeadline(WalkWindows.deadLinkHandoffCellularAt800ms, ConnEnd.NoUsablePath::class, moves = 1, rebinds = 0)
    }

    /** The downlink dark while the uplink flows, cellular reported a second in: every answer is lost, and the connection ends at the deadline. */
    @Test
    fun aDownlinkBlackoutEndsOnTheIdleDeadline() {
        endsOnTheIdleDeadline(WalkWindows.downlinkBlackout, ConnEnd.NoUsablePath::class, moves = 1, rebinds = 0)
    }

    /** Offline at 15.1 s and a cellular link not yet passing traffic at 15.4 s end it at the deadline, neither report sooner. */
    @Test
    fun anOfflineReportAndACellularLinkNotYetPassingEndOnTheIdleDeadline() {
        endsOnTheIdleDeadline(WalkWindows.cellularNotYetPassing, ConnEnd.NoUsablePath::class, moves = 1, rebinds = 1)
    }

    /** The OS reports the only network gone at 23.8 s: the end is the network's loss, at the deadline, not at the report. */
    @Test
    fun aSuspendedDataReportEndsOnTheIdleDeadlineAsTheNetworkLost() {
        endsOnTheIdleDeadline(WalkWindows.dataSuspended, ConnEnd.NetworkLost::class, moves = 0, rebinds = 1)
    }

    /** Wi-Fi silent 11.6 s, Offline in the middle, then cellular carries: the connection survives and echoes again. */
    @Test
    fun anAbruptHandoffThatCarriedTheConnectionKeepsItUp() {
        survivesTheHandoff(WalkWindows.abruptHandoff, silent = ABRUPT_SILENCE, rebinds = 1)
    }

    /** Landing: Wi-Fi gone, cellular reported 4.5 s in and carrying within 0.1 s: the connection moves and echoes again. */
    @Test
    fun theLandingHandoffFromWifiToCellularKeepsTheConnectionUp() {
        survivesTheHandoff(WalkWindows.landingHandoff, silent = LANDING_SILENCE, rebinds = 1)
    }

    private fun endsOnTheIdleDeadline(
        conditions: Conditions,
        end: KClass<out ConnEnd>,
        moves: Int,
        rebinds: Int,
    ) {
        val deadline = WalkWindows.LEAD_IN + WalkWindows.IDLE
        val replayed = replayTwice(conditions, Expect.Ends(end, notAfter = deadline + DEADLINE_SLACK))
        val ended = replayed.outcome as Outcome.Ended
        assertTrue(
            ended.at >= deadline - DEADLINE_SLACK,
            "ended at ${ended.at}, before the idle deadline $deadline: ${replayed.describe()}",
        )
        // The silence before the OS spoke asked for a fresh port this many times, each one socket.
        assertEquals(rebinds, replayed.rebinds(), "the silence's rebinds: ${replayed.describe()}")
        assertEquals(moves, replayed.routeMoves(), "the socket moved to each new address once: ${replayed.describe()}")
    }

    /** The connection never ends, moves once, and its first echo after the link carries again comes within [FIRST_ECHO]. */
    private fun survivesTheHandoff(
        conditions: Conditions,
        silent: Duration,
        rebinds: Int,
    ) {
        val replayed =
            replayTwice(conditions, Expect.Survives(firstEchoWithin = FIRST_ECHO, longestGapAtMost = silent + FIRST_ECHO))
        // The silence before the OS spoke asked for a fresh port this many times, each one socket.
        assertEquals(rebinds, replayed.rebinds(), "the silence's rebinds: ${replayed.describe()}")
        assertEquals(1, replayed.routeMoves(), "the socket moved to cellular once: ${replayed.describe()}")
        assertTrue(
            replayed.echoes.last().answeredAt > conditions.runFor - ECHOING_AGAIN,
            "echoes stopped before the window's end: ${replayed.describe()}",
        )
    }

    private companion object {
        /** How far from the idle deadline the end may fall: the deadline runs from a datagram sent after the window opened. */
        val DEADLINE_SLACK = 1.seconds

        /** The echo after a handoff: a validation round trip, an echo's, and the retransmission timer a lost probe waits on. */
        val FIRST_ECHO = 2.seconds

        /** The link carries nothing for this long in the abrupt handoff, and in the landing's. */
        val ABRUPT_SILENCE = 11_558L.milliseconds
        val LANDING_SILENCE = 4_635L.milliseconds
        val ECHOING_AGAIN = 3.seconds
    }
}
