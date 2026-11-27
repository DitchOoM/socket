package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A reply read by a client whose link loses datagrams, on virtual time against a real core pair: the client's
 * acknowledgements are lost with the rest, and the reply still arrives whole within a bound that holds for the seeds
 * that drew the worst runs, instead of waiting out the server's backed-off retransmission timers. Each seed fixes the
 * link's loss draws.
 */
class ReplyOverLossTests {
    /** A reply of several congestion windows over a 400 ms one-way path that loses 30 % of its datagrams each way from the end of the handshake. */
    @Test
    fun aReplyOverAVeryLossyPathIsDeliveredWholeWithinTheBound() {
        for (seed in LOSSY_SEEDS) {
            val replayed = ConditionReplay(path(HANDSHAKE, PerMille(LOSSY)), seed = seed, workload = Workload.Download(REPLY)).run()
            val done =
                replayed.downloaded.firstOrNull()
                    ?: throw AssertionError("seed $seed: the reply never arrived whole: ${replayed.describe()}")
            assertTrue(done <= LOSSY_BOUND, "seed $seed: the reply arrived at $done, past $LOSSY_BOUND: ${replayed.describe()}")
            assertEquals(1, replayed.downloaded.size, "seed $seed: ${replayed.describe()}")
        }
    }

    /** A link clean through the handshake and the request that loses half its datagrams from then on: the client sees the loss only as gaps in what it receives. */
    @Test
    fun aReplyWhoseLinkTurnsLossyAfterTheRequestIsDeliveredWholeWithinTheBound() {
        for (seed in TURNING_SEEDS) {
            val replayed = ConditionReplay(path(REQUEST_READ, PerMille(TURNING)), seed = seed, workload = Workload.Download(REPLY)).run()
            val done =
                replayed.downloaded.firstOrNull()
                    ?: throw AssertionError("seed $seed: the reply never arrived whole: ${replayed.describe()}")
            assertTrue(done <= TURNING_BOUND, "seed $seed: the reply arrived at $done, past $TURNING_BOUND: ${replayed.describe()}")
        }
    }

    /** The path: clean until [cleanUntil], then losing [loss] each way. */
    private fun path(
        cleanUntil: Duration,
        loss: PerMille,
    ): Conditions =
        Conditions(
            name = "A 400 ms path losing ${loss.value} per mille after $cleanUntil",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = RUN_FOR * 2,
            idleTimeout = IDLE,
            link =
                if (cleanUntil == Duration.ZERO) {
                    listOf(LinkWindow.Flowing(Duration.ZERO, RUN_FOR, ONE_WAY, loss, loss))
                } else {
                    listOf(
                        LinkWindow.Flowing(Duration.ZERO, cleanUntil, ONE_WAY, PerMille(0), PerMille(0)),
                        LinkWindow.Flowing(cleanUntil, RUN_FOR, ONE_WAY, loss, loss),
                    )
                },
            os = emptyList(),
            runFor = RUN_FOR,
        )

    private companion object {
        val ONE_WAY = DelayRange(400.milliseconds, 400.milliseconds)
        const val REPLY = 34 * 1024
        const val LOSSY = 300
        const val TURNING = 500

        /** The seeds that drew the slowest runs among the first 120 that deliver the reply: it arrived at 15 to 25 s. */
        val LOSSY_SEEDS = listOf(14L, 22L, 63L, 70L, 110L)

        /** The same for the turning link: 41 to 52 s. */
        val TURNING_SEEDS = listOf(28L, 38L, 41L, 72L, 95L)

        /** The handshake takes a round trip and a half each way: clean through it, so both ends are established. */
        val HANDSHAKE = 1_500.milliseconds

        /** The client's request leaves a second after its handshake and the server reads it 0.4 s later. */
        val REQUEST_READ = 2_500.milliseconds
        val IDLE = 30.seconds
        val RUN_FOR = 120.seconds

        /** A reply that arrives within the idle timeout was never lost to a connection that fell silent. */
        val LOSSY_BOUND = IDLE
        val TURNING_BOUND = 60.seconds
    }
}
