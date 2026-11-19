package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long a moved client waits on a path being validated, on virtual time: a validation is abandoned at three of the
 * larger base PTO of the two paths, never of a PTO an outage backed off; a slow new path still migrates; and another
 * network under the socket ends the back-off of the probes that failed on the one before.
 */
class PathValidationPolicyTests {
    /** After the old path was dark for ten seconds, a validation of a dark new path is abandoned near 3 × 1,024 ms. */
    @Test
    fun aLongOutageBeforeTheMoveDoesNotLengthenTheAbandonment() {
        val healed = MOVE_AT + 5.seconds
        val link =
            listOf(
                LinkWindow.Flowing(Duration.ZERO, MOVE_AT - LONG_OUTAGE, FAST, NONE, NONE),
                LinkWindow.Outage(MOVE_AT - LONG_OUTAGE, healed),
                LinkWindow.Flowing(healed, RUN_FOR, FAST, NONE, NONE),
            )
        val replayed = ConditionReplay(conditions(link, moveTo()), 1L).run()
        val moved = replayed.pathMarks.first { it.phase == "Moved" }.at
        val abandoned = replayed.pathMarks.first { it.phase == "ProbeFailed" }.at - moved
        assertTrue(abandoned <= FIRST_ABANDON, "abandoned $abandoned after the move: ${replayed.pathMarks}")
        assertTrue(replayed.moveToMigrated() != null, "never migrated once the new path healed: ${replayed.pathMarks}")
    }

    /** A new path with a 1.5–2 s round trip, past every PTO a path with no sample has, still migrates. */
    @Test
    fun aSlowNewPathStillMigrates() {
        val slow = DelayRange(750.milliseconds, 1_000.milliseconds)
        val link =
            listOf(
                LinkWindow.Flowing(Duration.ZERO, MOVE_AT - FADE, FAST, NONE, NONE),
                LinkWindow.Outage(MOVE_AT - FADE, MOVE_AT),
                LinkWindow.Flowing(MOVE_AT, RUN_FOR, slow, NONE, NONE),
            )
        for (seed in 1L..3L) {
            val replayed = ConditionReplay(conditions(link, moveTo()), seed).run()
            val took = replayed.moveToMigrated() ?: throw AssertionError("seed $seed: never migrated: ${replayed.pathMarks}")
            assertTrue(took <= SLOW_MIGRATION, "seed $seed: migrated $took after the move: ${replayed.pathMarks}")
        }
    }

    /**
     * The new path stays dark long enough for the re-probes to back off to eight intervals; another network then comes
     * up under the socket's address, and the path works there: it is probed at once, not when the back-off ends, and
     * the probe's challenge leaves although the unanswered ones before it fill the path's congestion window, so the
     * connection migrates one round trip after the rehome.
     */
    @Test
    fun anotherNetworkEndsTheBackOffOfTheProbesThatFailedBefore() {
        val rehomeAt = MOVE_AT + REHOME_AFTER
        val link =
            listOf(
                LinkWindow.Flowing(Duration.ZERO, MOVE_AT - FADE, FAST, NONE, NONE),
                LinkWindow.Outage(MOVE_AT - FADE, rehomeAt),
                LinkWindow.Flowing(rehomeAt, RUN_FOR, FAST, NONE, NONE),
            )
        val os =
            moveTo() +
                listOf(
                    OsEvent.Up(rehomeAt, NetUp(NetHandle(NetKind.Cellular, 2), Validation.Validated)),
                    OsEvent.Route(rehomeAt, RouteFrom.Other(MOVED_FROM)),
                )
        val replayed = ConditionReplay(conditions(link, os), 1L).run()
        val moved = replayed.pathMarks.first { it.phase == "Moved" }.at
        val rehomed = replayed.pathMarks.first { it.phase == "Rehomed" }.at
        val reprobed = replayed.pathMarks.firstOrNull { it.phase == "Reprobed" && it.at >= rehomed }?.at
        assertTrue(
            reprobed != null && reprobed - rehomed <= PASS,
            "rehomed ${rehomed - moved} after the move, re-probed at ${reprobed?.minus(moved)}: ${replayed.pathMarks}",
        )
        val took = replayed.moveToMigrated() ?: throw AssertionError("never migrated: ${replayed.pathMarks}")
        assertTrue(
            took - (rehomed - moved) <= ROUND_TRIP + PASS,
            "migrated ${took - (rehomed - moved)} after the rehome: ${replayed.pathMarks}",
        )
    }

    private fun moveTo(): List<OsEvent> =
        listOf(
            OsEvent.Up(MOVE_AT, NetUp(NetHandle(NetKind.Cellular, 1), Validation.Validated)),
            OsEvent.Route(MOVE_AT, RouteFrom.Other(MOVED_FROM)),
        )

    private fun conditions(
        link: List<LinkWindow>,
        os: List<OsEvent>,
    ): Conditions =
        Conditions(
            name = "Wi-Fi to cellular from another address",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = IDLE,
            link = link,
            os = os,
            runFor = RUN_FOR,
            echoBytes = 2_000,
        )

    private companion object {
        val MOVE_AT = 15.seconds
        val FADE = 300.milliseconds
        val LONG_OUTAGE = 10.seconds
        val IDLE = 60.seconds
        val RUN_FOR = MOVE_AT + 40.seconds
        val FAST = DelayRange(80.milliseconds, 80.milliseconds)
        val NONE = PerMille(0)
        const val MOVED_FROM = "198.51.100.1"

        /** Three of a new path's PTO with no RTT sample, 1,024 ms, and a pass. */
        val FIRST_ABANDON = 3_100.milliseconds

        /** Past the third abandonment, when the next re-probe is eight intervals away. */
        val REHOME_AFTER = 19_500.milliseconds

        val SLOW_MIGRATION = 12.seconds
        val PASS = 10.milliseconds
        val ROUND_TRIP = 160.milliseconds
    }
}
