package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client whose local address changes while a 2,000-byte echo is in flight, on virtual time over a real round trip:
 * its socket moves to the new address, the path from there is probed with datagrams of at most 1200 bytes (RFC 9000
 * §8.2.1), and the connection migrates once it is validated. The old path fades out shortly before the route moves,
 * so an echo is lost on it, and the old socket is gone the moment the route moves, as Android ties a socket to its
 * network.
 */
class MigrationConditionTests {
    /** 40–60 ms each way and 3 % loss each way, five seeds: every run migrates and keeps echoing. */
    @Test
    fun aMoveOverALossyPathWithRealDelayIsSurvived() {
        val conditions = move(DelayRange(40.milliseconds, 60.milliseconds), PerMille(LOSSY))
        for (seed in SEEDS) {
            val replayed = ConditionReplay(conditions, seed).run()
            val survived = replayed.outcome as? Outcome.Survived ?: throw AssertionError("seed $seed: ${replayed.describe()}")
            assertEquals(1, replayed.moves, "seed $seed: ${replayed.describe()}")
            assertTrue(survived.echoes > MOVE_AT / conditions.echoEvery, "seed $seed echoed on after the move: ${replayed.describe()}")
            when (val first = survived.firstEchoAfterUsable) {
                is AfterUsable.Answered ->
                    assertTrue(first.after < 2.seconds, "seed $seed: first echo ${first.after} after the move: ${replayed.describe()}")
                AfterUsable.Undisturbed, AfterUsable.NeverUsable, AfterUsable.Unanswered ->
                    throw AssertionError("seed $seed: no echo after the move: ${replayed.describe()}")
            }
        }
    }

    /** With no loss the echo in flight is answered within two round trips of the move: validation, then the echo. */
    @Test
    fun theEchoInFlightIsAnsweredTwoRoundTripsAfterTheMove() {
        val delay = DelayRange(30.milliseconds, 30.milliseconds)
        val replayed = replayTwice(move(delay, PerMille(0)), Expect.Survives(firstEchoWithin = delay.max * 4))
        assertEquals(1, replayed.moves, replayed.describe())
    }

    /**
     * Every datagram of the move is at most 1200 bytes, and the padded ones (the client's PATH_CHALLENGE, the server's
     * PATH_RESPONSE and its own challenge) are exactly 1200: a link that carries no more still validates the path.
     */
    @Test
    fun theProbesOfAMoveAreTwelveHundredBytes() {
        val replayed = replayTwice(move(DelayRange(30.milliseconds, 30.milliseconds), PerMille(0)), Expect.Survives(1.seconds))
        val clientSizes = during(replayed.clientSends, replayed.clientSizes)
        val serverSizes = during(replayed.serverSends, replayed.serverSizes)
        assertEquals(PATH_PROBE, clientSizes.max(), "the client's datagrams while moving: $clientSizes")
        assertEquals(PATH_PROBE, serverSizes.max(), "the server's datagrams while moving: $serverSizes")
    }

    /** A move to an address from which nothing gets through ends the connection at its idle timeout as NoUsablePath. */
    @Test
    fun aMoveToAnAddressThatNeverWorksEndsAsNoUsablePath() {
        val flowing = DelayRange(30.milliseconds, 30.milliseconds)
        val conditions =
            move(flowing, PerMille(0)).copy(
                name = "A move onto a link that carries nothing",
                link =
                    listOf(
                        LinkWindow.Flowing(Duration.ZERO, MOVE_AT, flowing, PerMille(0), PerMille(0)),
                        LinkWindow.Outage(MOVE_AT, RUN_FOR),
                    ),
            )
        replayTwice(conditions, Expect.Ends(ConnEnd.NoUsablePath::class, notAfter = MOVE_AT + IDLE + 1.seconds))
    }

    /** The sizes of what was sent (at [sends]) while the new path was being validated: the move's first round trip. */
    private fun during(
        sends: List<Duration>,
        sizes: List<Int>,
    ): List<Int> = sizes.filterIndexed { i, _ -> sends[i] >= MOVE_AT && sends[i] < MOVE_AT + PROBE_ROUND_TRIP }

    private fun move(
        delay: DelayRange,
        loss: PerMille,
    ): Conditions =
        Conditions(
            name = "Wi-Fi to cellular from another address, ${delay.min}–${delay.max} each way, ${loss.value}‰ loss",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = IDLE,
            link =
                listOf(
                    LinkWindow.Flowing(Duration.ZERO, MOVE_AT - FADE, delay, loss, loss),
                    LinkWindow.Outage(MOVE_AT - FADE, MOVE_AT),
                    LinkWindow.Flowing(MOVE_AT, RUN_FOR, delay, loss, loss),
                ),
            os =
                listOf(
                    OsEvent.Up(MOVE_AT, NetUp(NetHandle(NetKind.Cellular, 1), Validation.Validated)),
                    OsEvent.Route(MOVE_AT, RouteFrom.Other("198.51.100.1")),
                ),
            runFor = RUN_FOR,
            echoBytes = ECHO_BYTES,
        )

    private companion object {
        val MOVE_AT = 5.seconds

        /** How long the old path is dead before the route moves: longer than an echo interval, so an echo is lost in it. */
        val FADE = 300.milliseconds
        val RUN_FOR = 12.seconds
        val IDLE = 5.seconds
        val SEEDS = 1L..5L
        const val LOSSY = 30
        const val ECHO_BYTES = 2_000

        /** The round trip of the probe-size test's 30 ms each way. */
        val PROBE_ROUND_TRIP = 60.milliseconds
        const val PATH_PROBE = 1200
    }
}
