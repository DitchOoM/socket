package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A real core client and server on a link that reorders and duplicates: each datagram's delay is drawn from 2 to 40 ms,
 * so a later one overtakes an earlier, and a quarter of them arrive twice. The connection survives and every echo comes
 * back, the same ones at the same instants on the same script.
 */
class ReorderDuplicateConditionTests {
    @Test
    fun anEchoSurvivesAReorderingAndDuplicatingLink() {
        val replayed = replayTwice(reordering(), Expect.Survives(firstEchoWithin = 5.seconds))
        assertTrue(replayed.duplicated > 0, "the link never duplicated a datagram: ${replayed.describe()}")
        assertTrue(replayed.echoes.size > 10, replayed.describe())
    }

    private fun reordering() =
        Conditions(
            name = "reorder and duplicate: Wi-Fi",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 200.milliseconds,
            idleTimeout = 30.seconds,
            link =
                listOf(
                    LinkWindow.Flowing(
                        Duration.ZERO,
                        6.seconds,
                        DelayRange(2.milliseconds, 40.milliseconds),
                        PerMille(0),
                        PerMille(0),
                        duplicate = PerMille(250),
                    ),
                ),
            os = emptyList(),
            runFor = 6.seconds,
        )
}
