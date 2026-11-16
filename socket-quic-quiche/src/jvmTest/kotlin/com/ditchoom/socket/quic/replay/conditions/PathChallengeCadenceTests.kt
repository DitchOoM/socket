package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client whose socket moved validates the new path over an 80 ms one-way link that loses the first answers the server
 * sends there, on virtual time. Quiche resends a PATH_CHALLENGE only when the packet carrying it is declared lost and
 * gives up after three such losses; the connection asks for a fresh challenge every 999 ms (the PTO of a path with no
 * RTT sample, RFC 9000 §8.2.1) until the path validates or the validation is abandoned (RFC 9000 §8.2.4), so each lost
 * answer costs about one interval.
 */
class PathChallengeCadenceTests {
    @Test
    fun eachLostPathResponseCostsAboutOneChallengeInterval() {
        for (lost in LOST_ANSWERS) {
            val replayed = ConditionReplay(move(flowingAfterMove()), 1L, lost).run()
            val took = replayed.moveToMigrated() ?: throw AssertionError("$lost answers lost: never migrated, ${replayed.pathMarks}")
            val bound = INTERVAL * (lost + 1) + ROUND_TRIP
            assertTrue(took <= bound, "$lost answers lost: migrated $took after the move, over $bound: ${replayed.pathMarks}")
            val challenged = replayed.pathMarks.filter { it.phase == "Challenged" }.map { it.at }
            if (lost < QUICHE_RESENDS) continue
            assertTrue(challenged.isNotEmpty(), "$lost answers lost: no fresh challenge was asked: ${replayed.pathMarks}")
            for ((a, b) in challenged.zipWithNext()) {
                if (b - a > INTERVAL * 2) continue // a new validation started between the two
                assertTrue(b - a in INTERVAL - SLACK..INTERVAL + SLACK, "fresh challenges $a and $b are not one interval apart")
            }
        }
    }

    /**
     * A new path that carries nothing is challenged at a bounded rate: the first validation is abandoned within three
     * of the larger of the two paths' PTOs, later ones are spaced out, and the idle timeout ends the connection.
     */
    @Test
    fun aNewPathThatNeverAnswersIsChallengedAtABoundedRateUntilTheIdleTimeout() {
        val dead =
            listOf(
                LinkWindow.Flowing(Duration.ZERO, MOVE_AT - FADE, ONE_WAY, NONE, NONE),
                LinkWindow.Outage(MOVE_AT - FADE, RUN_FOR),
            )
        val replayed = ConditionReplay(move(dead), 1L).run()
        val ended = replayed.outcome as? Outcome.Ended ?: throw AssertionError("did not end: ${replayed.describe()}")
        assertTrue(ended.end == ConnEnd.NoUsablePath::class, replayed.describe())
        assertTrue(ended.at <= MOVE_AT + IDLE + 1.seconds, replayed.describe())
        val moved = replayed.pathMarks.first { it.phase == "Moved" }.at
        val abandoned = replayed.pathMarks.first { it.phase == "ProbeFailed" }.at - moved
        assertTrue(abandoned <= FIRST_ABANDON, "the first validation was abandoned $abandoned after the move: ${replayed.pathMarks}")
        val probes = replayed.clientSends.indices.count { replayed.clientSends[it] >= MOVE_AT && replayed.clientSizes[it] == PATH_PROBE }
        assertTrue(probes <= MOST_PROBES, "$probes path probes over the $IDLE the dead path was tried: ${replayed.pathMarks}")
    }

    private fun flowingAfterMove(): List<LinkWindow> =
        listOf(
            LinkWindow.Flowing(Duration.ZERO, MOVE_AT - FADE, ONE_WAY, NONE, NONE),
            LinkWindow.Outage(MOVE_AT - FADE, MOVE_AT),
            LinkWindow.Flowing(MOVE_AT, RUN_FOR, ONE_WAY, NONE, NONE),
        )

    private fun move(link: List<LinkWindow>): Conditions =
        Conditions(
            name = "Wi-Fi to cellular from another address, 80 ms each way",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = IDLE,
            link = link,
            os =
                listOf(
                    OsEvent.Up(MOVE_AT, NetUp(NetHandle(NetKind.Cellular, 1), Validation.Validated)),
                    OsEvent.Route(MOVE_AT, RouteFrom.Other("198.51.100.1")),
                ),
            runFor = RUN_FOR,
            echoBytes = 2_000,
        )

    private companion object {
        val MOVE_AT = 5.seconds
        val FADE = 300.milliseconds
        val IDLE = 30.seconds
        val RUN_FOR = MOVE_AT + IDLE + 5.seconds
        val ONE_WAY = DelayRange(80.milliseconds, 80.milliseconds)
        val ROUND_TRIP = 160.milliseconds
        val NONE = PerMille(0)

        /** Quiche's own three resends answer up to the third lost answer; past it only fresh challenges do. */
        val LOST_ANSWERS = listOf(1, 3, 4, 5, 6, 8)
        const val QUICHE_RESENDS = 3

        /** RFC 9002's PTO of a path with no RTT sample in the Initial space: 333 ms + 4 × 166.5 ms. */
        val INTERVAL = 999.milliseconds
        val SLACK = 5.milliseconds

        /** Three of the new path's PTO, 1,024 ms, and a pass. */
        val FIRST_ABANDON = 3_100.milliseconds
        const val PATH_PROBE = 1200

        /** About one probe per two seconds over the idle timeout: the cadence of a validation, then backed-off retries. */
        const val MOST_PROBES = 20
    }
}
