package com.ditchoom.socket.testkit.walk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The lane watchdog judged phase by phase, on virtual time, with the probes' own numbers: a heartbeat
 * every 60 s, 120 s of echo silence, a 30 s idle timeout plus a 10 s margin to connect, and a
 * reconnect backoff that doubles from 3 s to 60 s while attempts die young.
 */
class LaneWatchTests {
    private val lane = WalkTargets(WalkTarget("192.0.2.1", 4433), listOf(WalkTarget("2001:db8::1", 4433))).lanes[1]
    private val bounds = StallBounds.forConnect(echoSilence = 120.seconds, handshakeBound = 30.seconds, margin = 10.seconds)
    private val heartbeat = 60.seconds

    private fun TestScope.watch(startsAfter: Duration = Duration.ZERO) =
        LaneWatch(lane, bounds, clock = { testScheduler.currentTime.milliseconds }, startsAfter = startsAfter)

    /** The probes' heartbeat: [beats] beats, one every [heartbeat], each judged. */
    private suspend fun LaneWatch.beats(beats: Int): List<LaneWatch.Beat> =
        List(beats) {
            delay(heartbeat)
            beat()
        }

    /**
     * 2026-09-24: cellular carried no IPv6, so every v6 connect failed fast with a typed
     * `UnresolvedRouteSourceException` and the lane sat in its 60 s backoff for the whole walk. No
     * connection, so no echo loop, and loopTicks never moved — which the old check called a stall.
     */
    @Test
    fun aLaneWithNoRouteWaitingOutItsBackoffIsNotAStall() =
        runTest {
            val watch = watch()
            val walk = 2.hours
            backgroundScope.launch {
                var retry = 3.seconds
                while (testScheduler.currentTime.milliseconds < walk) {
                    watch.nextAttempt()
                    delay(4.milliseconds)
                    retry = minOf(retry * 2, 60.seconds)
                    watch.backingOff(retry)
                    delay(retry)
                }
                watch.walkOver()
            }

            val beats = watch.beats((walk / heartbeat).toInt())

            assertEquals(0, watch.loopTicks, "the lane never connected")
            assertEquals(
                emptyList(),
                beats.filterNot { it == LaneWatch.Beat.OnTime },
                "a lane waiting out a backoff it will leave on time owes nothing yet; attempts=${watch.attempt}",
            )
        }

    @Test
    fun aConnectThatNeitherConnectsNorFailsIsAStall() =
        runTest {
            val watch = watch()
            backgroundScope.launch {
                watch.nextAttempt()
                awaitCancellation()
            }

            val beats = watch.beats(3)

            assertIs<LaneWatch.Beat.Overdue>(beats[0], "60 s against a 40 s bound, seen once: $beats")
            val stalled = assertIs<LaneWatch.Beat.Stalled>(beats[1], "the same connect overdue on two beats: $beats")
            assertEquals(
                "STALL-SUSPECTED phase=Connecting attempt=1 waited=120s bound=40s — expected the connect to succeed or fail; " +
                    "it has done neither. Dumping the last 256 trace events.",
                stalled.line(ringSize = 256),
            )
            assertEquals(LaneWatch.Beat.StillStalled, beats[2], "a stall is dumped once, not every minute")
        }

    @Test
    fun aBackoffThatOverrunsItsDeadlineIsAStall() =
        runTest {
            val watch = watch()
            backgroundScope.launch {
                watch.nextAttempt()
                watch.backingOff(60.seconds)
                awaitCancellation()
            }

            val beats = watch.beats(3)

            assertEquals(LaneWatch.Beat.OnTime, beats[0], "the backoff has only just ended")
            assertIs<LaneWatch.Beat.Overdue>(beats[1])
            val stalled = assertIs<LaneWatch.Beat.Stalled>(beats[2], "$beats")
            assertEquals(
                "STALL-SUSPECTED phase=Backoff attempt=1 waited=180s bound=70s — expected attempt 2 when the 60s backoff ended; " +
                    "none started. Dumping the last 256 trace events.",
                stalled.line(ringSize = 256),
            )
        }

    /** The shape of the 2026-09 driver hang: connected, echoed a little, then the loop parked for good. */
    @Test
    fun anEchoLoopThatStopsIsAStallAndItsRecoveryIsReportedOnce() =
        runTest {
            val watch = watch()
            val resumed = CompletableDeferred<Unit>()
            backgroundScope.launch {
                watch.nextAttempt()
                delay(200.milliseconds)
                watch.connected()
                repeat(5) {
                    delay(1.seconds)
                    watch.progressed(it + 1)
                }
                resumed.await()
                watch.progressed(6)
            }

            val beats = watch.beats(4)
            resumed.complete(Unit)
            val after = watch.beats(1)

            assertEquals(
                listOf(LaneWatch.Beat.OnTime, LaneWatch.Beat.OnTime),
                beats.take(2),
                "silent since 5.2 s: within 120 s at the 60 s and 120 s beats",
            )
            assertIs<LaneWatch.Beat.Overdue>(beats[2])
            val stalled = assertIs<LaneWatch.Beat.Stalled>(beats[3], "$beats")
            assertEquals(
                "STALL-SUSPECTED phase=Echoing attempt=1 loopTicks=5 waited=234s bound=120s — expected loopTicks to advance; " +
                    "the echo loop is not running. Dumping the last 256 trace events.",
                stalled.line(ringSize = 256),
            )
            val recovered = assertIs<LaneWatch.Beat.Recovered>(after.single())
            assertEquals("STALL-RECOVERED phase=Echoing attempt=1 loopTicks=5 now phase=Echoing attempt=1 loopTicks=6", recovered.line)
        }

    /**
     * A process the OS stopped scheduling wakes with every deadline behind it, and the heartbeat may
     * run before the lane does. One overdue sighting is not a stall; the lane gets a beat to move.
     */
    @Test
    fun aHeartbeatThatWakesBeforeItsLaneIsNotAStall() {
        var now = Duration.ZERO
        val watch = LaneWatch(lane, bounds, clock = { now }, startsAfter = Duration.ZERO)
        watch.nextAttempt()
        watch.connected()
        watch.progressed(1)

        now = 300.seconds
        val woke = watch.beat()
        watch.progressed(2)
        now += heartbeat
        val next = watch.beat()

        assertIs<LaneWatch.Beat.Overdue>(woke)
        assertEquals(LaneWatch.Beat.OnTime, next)
    }

    /** The time with no network was the connects' to answer for; the new connection's silence starts when it connects. */
    @Test
    fun aConnectionAfterFailedConnectsStartsItsSilenceAfresh() {
        var now = Duration.ZERO
        val watch = LaneWatch(lane, bounds, clock = { now }, startsAfter = Duration.ZERO)
        watch.nextAttempt()
        watch.connected()
        watch.progressed(1)
        now += 10.seconds
        watch.backingOff(3.seconds)
        repeat(10) {
            now += 3.seconds
            watch.nextAttempt()
            now += 1.seconds
            watch.backingOff(60.seconds)
            now += 60.seconds
        }
        watch.nextAttempt()
        now += 1.seconds
        watch.connected()
        now += 30.seconds

        assertEquals(LaneWatch.Beat.OnTime, watch.beat(), "${watch.phase}")
        assertEquals(LanePhase.Echoing(attempt = 12, since = now - 30.seconds, loopTicks = 1, silentSince = now - 30.seconds), watch.phase)
    }

    @Test
    fun theStartStaggerIsABackoffAndALaneThatNeverStartsIsAStall() =
        runTest {
            val watch = watch(startsAfter = 125.milliseconds)
            assertEquals(LanePhase.Backoff(attempt = 0, since = Duration.ZERO, until = 125.milliseconds), watch.phase)

            val beats = watch.beats(2)

            assertIs<LaneWatch.Beat.Overdue>(beats[0])
            val stalled = assertIs<LaneWatch.Beat.Stalled>(beats[1], "$beats")
            assertEquals(
                "STALL-SUSPECTED phase=Backoff attempt=0 waited=120s bound=10s — expected attempt 1 when the 0s backoff ended; " +
                    "none started. Dumping the last 256 trace events.",
                stalled.line(ringSize = 256),
            )
        }

    @Test
    fun nothingIsOwedOnceTheWalkIsOver() =
        runTest {
            val watch = watch()
            watch.nextAttempt()
            watch.walkOver()
            assertEquals(List(5) { LaneWatch.Beat.Unwatched }, watch.beats(5))
        }
}
