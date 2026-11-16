package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.HandshakePacing
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A real core client and server through a handshake on virtual time: its deadline, and its pace under loss. */
class HandshakeConditionTests {
    /** Nothing ever answers: the handshake ends at its 10 s bound, typed, and not at the 30 s idle timeout. */
    @Test
    fun aHandshakeNothingAnswersEndsAtItsBound() {
        replayTwice(
            handshake("Black hole", listOf(LinkWindow.Outage(Duration.ZERO, 40.seconds)), 40.seconds),
            Expect.Ends(ConnEnd.HandshakeTimedOut::class, notAfter = 10.seconds + 10.milliseconds),
        )
    }

    /** The interop runner's handshakeloss shape: 30 % loss each way on a 15 ms link. The handshake completes and echoes. */
    @Test
    fun aHandshakeThroughThirtyPercentLossCompletesAndEchoes() {
        val replayed = replayTwice(lossy(HandshakePacing.Adaptive()), Expect.Survives(firstEchoWithin = 10.seconds))
        assertTrue(replayed.echoes.size > 5, replayed.describe())
    }

    /**
     * Wi-Fi's prior is 80 ms, so the client's first PTO and first ceiling are 240 ms: through 30 % loss on a 30 ms
     * round trip, no seed leaves the client silent longer than that before its handshake completes.
     */
    @Test
    fun underThirtyPercentLossTheClientIsNeverSilentLongerThanTheFirstCeiling() {
        for (seed in SEEDS) {
            val replayed = ConditionReplay(lossy(HandshakePacing.Adaptive()), seed).run()
            assertEquals(1, replayed.established.size, "seed $seed: ${replayed.describe()}")
            assertTrue(replayed.longestHandshakeSilence() <= 240.milliseconds, "seed $seed: ${replayed.longestHandshakeSilence()}")
            assertTrue(replayed.established.single() <= 400.milliseconds, "seed $seed: ${replayed.established}")
        }
    }

    /** The same loss under RFC 9002's 333 ms prior: the first lost flight costs the client a full second of silence. */
    @Test
    fun rfc9002PacingWaitsASecondForTheSameLostFlight() {
        val replayed = ConditionReplay(lossy(HandshakePacing.Rfc9002), seed = 3L).run()
        assertEquals(999.milliseconds, replayed.longestHandshakeSilence())
    }

    private fun lossy(pacing: HandshakePacing): Conditions {
        val delay = DelayRange(15.milliseconds, 15.milliseconds)
        return handshake(
            "30 % loss",
            listOf(LinkWindow.Flowing(Duration.ZERO, 8.seconds, delay, PerMille(300), PerMille(300))),
            8.seconds,
            pacing,
        )
    }

    private fun handshake(
        name: String,
        link: List<LinkWindow>,
        runFor: Duration,
        pacing: HandshakePacing = HandshakePacing.Adaptive(),
    ) = Conditions(
        name = "$name: a handshake on Wi-Fi",
        source = "synthetic",
        family = Family.V4,
        evidence = Evidence.BothEnds,
        start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
        echoEvery = 500.milliseconds,
        idleTimeout = 30.seconds,
        link = link,
        os = emptyList(),
        runFor = runFor,
        handshakePacing = pacing,
    )

    private companion object {
        val SEEDS = 1L..15L
    }
}
