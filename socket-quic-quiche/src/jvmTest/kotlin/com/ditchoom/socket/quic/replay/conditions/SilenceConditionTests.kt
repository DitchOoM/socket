package com.ditchoom.socket.quic.replay.conditions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The client's silence watch against a real core pair on virtual time, over links that go dark, blip and lose: a path
 * that stops answering a writing application is declared silent within the patience; a blip the length of the
 * recorded 1.02 s excursion, ordinary loss, and separate blips with an exchange between them are not silence. The monitor never moves, so the data
 * plane is the only witness.
 */
class SilenceConditionTests {
    /**
     * Downlink dark under an application that keeps writing, at the walk probe's 250 ms and under the path's PTO:
     * each write re-arms quiche's PTO (RFC 9002 §6.2.1), so the expiry count stalls and the owed answer's patience
     * decides, within one write interval of it.
     */
    @Test
    fun aPathThatGoesDarkUnderAWritingApplicationIsDeclaredSilentWithinThePatience() {
        for (every in listOf(WALK_ECHO_INTERVAL, FAST_WRITE_INTERVAL)) {
            val replayed = ConditionReplay(downlinkDarkAt(DARK_AT), workload = Workload.Writes(every)).run()
            val silent = replayed.silentAt().firstOrNull() ?: throw AssertionError("writing every $every, never declared: ${replayed.news}")
            val bound = DARK_AT + every + PATIENCE + SLACK
            println("[silence] writing every $every: declared ${silent - DARK_AT} after the downlink went dark")
            assertTrue(silent > DARK_AT, "writing every $every, declared at $silent, before the dark at $DARK_AT")
            assertTrue(silent <= bound, "writing every $every, declared at $silent, past $bound: ${replayed.news}")
        }
    }

    /** A 1.02 s blip, at the one-way latencies of a phone near its edge, is never silence: one Wi-Fi excursion. */
    @Test
    fun aBlipTheLengthOfThe385ExcursionIsNotSilence() {
        for (oneWay in BLIP_LATENCIES) {
            val replayed = ConditionReplay(blip(oneWay, listOf(BLIP_AT))).run()
            assertEquals(emptyList(), replayed.silentAt(), "at $oneWay one way a 1.02 s blip was silence: ${replayed.news}")
            assertStalledAndHealed(replayed, oneWay)
        }
    }

    /**
     * A path that loses 10, 20 or 30 % of what crosses it, and carries the rest: quiche counts its time-threshold loss
     * timers as expiries too, so the count runs ahead, and the floor holds it. A declaration is allowed only after the
     * client heard nothing for at least the floor: random loss does silence a path outright now and then.
     */
    @Test
    fun aLossyPathIsNotASilentOne() {
        for (loss in LOSSY_RATES) {
            val replayed = ConditionReplay(lossy(PerMille(loss)), seed = 574_104L).run()
            assertTrue(replayed.outcome is Outcome.Survived, "at $loss‰: ${replayed.describe()}")
            for (silent in replayed.silentAt()) {
                val heardLast = replayed.clientArrivals.filter { it < silent }.maxOrNull() ?: Duration.ZERO
                assertTrue(
                    silent - heardLast >= FLOOR,
                    "at $loss‰ declared at $silent, ${silent - heardLast} after the last arrival: the count ran ahead of the floor",
                )
            }
        }
    }

    /**
     * Separate blips do not add up: an echo answered between two of them ends the run, so a long life of blips is not
     * silence. Each blip alone is past the count on a fast path; together they are far past it.
     */
    @Test
    fun separateBlipsDoNotAccumulateIntoSilence() {
        for (oneWay in listOf(10.milliseconds, 40.milliseconds)) {
            val replayed = ConditionReplay(blip(oneWay, List(BLIP_ROUNDS) { BLIP_AT + BLIP_SPACING * it })).run()
            assertEquals(emptyList(), replayed.silentAt(), "at $oneWay, $BLIP_ROUNDS separate blips added up to silence: ${replayed.news}")
            assertStalledAndHealed(replayed, oneWay)
        }
    }

    /** The blip did stall the echo, and the connection came back: otherwise the assertion above is decorative. */
    private fun assertStalledAndHealed(
        replayed: Replayed,
        oneWay: Duration,
    ) {
        val survived = replayed.outcome as? Outcome.Survived ?: throw AssertionError("at $oneWay: ${replayed.describe()}")
        assertTrue(survived.longestGap >= BLIP - 200.milliseconds, "at $oneWay the blip never stalled an echo: ${replayed.describe()}")
        assertTrue(
            replayed.echoes.last().answeredAt > replayed.conditions.runFor - 2.seconds,
            "at $oneWay echoes stopped: ${replayed.describe()}",
        )
    }

    private fun base(
        name: String,
        link: List<LinkWindow>,
    ) = Conditions(
        name = name,
        source = "synthetic",
        family = Family.V4,
        evidence = Evidence.BothEnds,
        start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
        echoEvery = 200.milliseconds,
        idleTimeout = IDLE,
        link = link,
        os = emptyList(),
        runFor = RUN_FOR,
    )

    private fun downlinkDarkAt(at: Duration): Conditions {
        val delay = DelayRange(20.milliseconds, 20.milliseconds)
        return base(
            "Downlink dark under a writer",
            listOf(
                LinkWindow.Flowing(Duration.ZERO, at, delay, PerMille(0), PerMille(0)),
                LinkWindow.Flowing(at, RUN_FOR, delay, PerMille(0), PerMille(ALL)),
            ),
        )
    }

    private fun blip(
        oneWay: Duration,
        at: List<Duration>,
        length: Duration = BLIP,
    ): Conditions {
        val delay = DelayRange(oneWay, oneWay)
        val windows = ArrayList<LinkWindow>()
        var from = Duration.ZERO
        for (start in at) {
            windows += LinkWindow.Flowing(from, start, delay, PerMille(0), PerMille(0))
            windows += LinkWindow.Outage(start, start + length)
            from = start + length
        }
        windows += LinkWindow.Flowing(from, RUN_FOR, delay, PerMille(0), PerMille(0))
        return base("${at.size} blip(s) of $length at $oneWay one way", windows)
    }

    private fun lossy(loss: PerMille): Conditions {
        val delay = DelayRange(40.milliseconds, 40.milliseconds)
        return base("${loss.value}‰ loss each way", listOf(LinkWindow.Flowing(Duration.ZERO, RUN_FOR, delay, loss, loss)))
    }

    private companion object {
        val DARK_AT = 3.seconds
        val WALK_ECHO_INTERVAL = 250.milliseconds
        val FAST_WRITE_INTERVAL = 30.milliseconds
        val PATIENCE = 4.seconds
        val FLOOR = 2.seconds

        /** A write the dark path will not answer leaving, and the wake that declares landing on the loop. */
        val SLACK = 100.milliseconds
        val BLIP = 1_020.milliseconds
        val BLIP_AT = 3.seconds
        val BLIP_LATENCIES = listOf(2.milliseconds, 10.milliseconds, 20.milliseconds, 40.milliseconds)
        val BLIP_SPACING = 3.seconds
        const val BLIP_ROUNDS = 3
        val LOSSY_RATES = listOf(100, 200, 300)
        const val ALL = 1000
        val IDLE = 30.seconds
        val RUN_FOR = 14.seconds
    }
}
