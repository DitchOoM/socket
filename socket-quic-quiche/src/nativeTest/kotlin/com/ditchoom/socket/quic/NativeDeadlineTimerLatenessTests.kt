@file:OptIn(ObsoleteWorkersApi::class)

package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How late [NativeDeadlineTimer] runs a deadline, on its own thread and with the machine otherwise as it is.
 *
 * What this suite learned the hard way: on a GitHub-hosted macOS runner every wait primitive (`Worker.park`,
 * `usleep`, `nanosleep`, a condition variable's timed wait, `dispatch_semaphore_wait`) returns late by tens of
 * milliseconds, up to 150 ms for a 100 ms wait, while on Linux and on a laptop all of them are within about
 * 100 us. So a wall-clock bound on the timer measures the runner, not the timer; the timer is judged against
 * the raw primitives measured in the same run ([printsHowLateRawWaitsReturn]), and the strategy that bounds
 * its own overshoot is tested in virtual time, where nothing is noisy ([halvingAbsorbsAnOvershootOfLessThanTwo]).
 */
class NativeDeadlineTimerLatenessTests {
    private suspend fun lateness(delay: Duration): List<Long> =
        withContext(Dispatchers.Default) {
            val timer = NativeDeadlineTimer()
            try {
                val samples = ArrayList<Long>()
                val fired = Channel<Long>(Channel.UNLIMITED)
                repeat(200) {
                    val start = TimeSource.Monotonic.markNow()
                    timer.schedule(delay) { fired.trySend(start.elapsedNow().inWholeMicroseconds) }
                    val took = withTimeout(10.seconds) { fired.receive() }
                    samples.add(maxOf(took - delay.inWholeMicroseconds, 0L))
                }
                samples.sorted()
            } finally {
                timer.close()
            }
        }

    @Test
    fun printsHowLateRawWaitsReturn() {
        rawWaitLateness(listOf(500L, 2_000L, 10_000L, 100_000L, 200_000L)) { if (it >= 100_000L) 5 else 100 }
            .forEach { println(it) }
    }

    @Test
    fun printsHowLateDeadlinesFire() =
        runTest {
            for (delay in listOf(500.microseconds, 2.milliseconds, 10.milliseconds)) {
                val s = lateness(delay)
                println("TIMER-LATENESS delay=$delay p50_us=${s[s.size / 2]} p99_us=${s[s.size * 99 / 100]} max_us=${s.last()}")
                assertTrue(s[s.size / 2] < 1_000_000, "a deadline fired a second late at the median")
            }
        }

    /**
     * The timer against the machine it runs on: a 200 ms deadline must not fire meaningfully later than a 200 ms
     * `Worker.park`, the wait the timer itself is built on, does in the same rounds. Each round takes one park and
     * one deadline back to back, so both see the machine as it was then; the bound is [longDeadlineBoundMicros] of
     * the parks. It cannot tell the halving from a whole-remainder park on a machine where parks are as late as
     * everything else; [halvingAbsorbsAnOvershootOfLessThanTwo] does.
     */
    @Test
    fun aLongDeadlineIsNotLaterThanTheMachinesOwnWaits() =
        runTest {
            val parker = Worker.start(name = "raw-park-baseline")
            val timer = NativeDeadlineTimer()
            try {
                val parks = ArrayList<Long>()
                val late = ArrayList<Long>()
                withContext(Dispatchers.Default) {
                    val fired = Channel<Long>(Channel.UNLIMITED)
                    repeat(ROUNDS) {
                        parks += parkLateness(parker, LONG_DELAY_MICROS)
                        val start = TimeSource.Monotonic.markNow()
                        timer.schedule(LONG_DELAY_MICROS.microseconds) { fired.trySend(start.elapsedNow().inWholeMicroseconds) }
                        late += withTimeout(10.seconds) { fired.receive() } - LONG_DELAY_MICROS
                    }
                }
                parks.sort()
                late.sort()
                val bound = longDeadlineBoundMicros(parks)
                println("TIMER-LATENESS-LONG late_us=$late park_late_us=$parks bound_us=$bound")
                assertTrue(late[0] >= 0, "a 200 ms deadline fired early: $late")
                assertTrue(
                    late[late.size / 2] <= bound,
                    "a 200 ms deadline fired ${late[late.size / 2]} us late at the median of $late; " +
                        "a 200 ms park in the same rounds was late by $parks, bound $bound us",
                )
            } finally {
                timer.close()
                parker.requestTermination().result
            }
        }

    /**
     * The bound follows the timer's own wait in the same rounds. The quietest of several waits, each measured at another
     * time, can sit far below what a park costs while the timer runs: parks about 75 ms late beside one condition wait
     * 2.8 ms late at its median would bound a timer 47 ms late, faster than its own park, by 38.5 ms.
     */
    @Test
    fun theLongDeadlineBoundIsTheParksMedianInTheSameRounds() {
        assertEquals(33L * 3 + 30_000L, longDeadlineBoundMicros(listOf(0L, 10L, 20L, 33L, 40L, 50L, 60L)))
        val ciParks = listOf(61_000L, 66_000L, 70_000L, 74_971L, 90_000L, 105_000L, 116_755L)
        val ciTimer = listOf(11_076L, 13_216L, 16_457L, 46_787L, 51_671L, 53_467L, 62_870L)
        assertTrue(ciTimer[ciTimer.size / 2] <= longDeadlineBoundMicros(ciParks), "the CI run's timer is within its own park's bound")
    }

    /** How late one park of [micros] returns on [parker]'s thread. */
    private fun parkLateness(
        parker: Worker,
        micros: Long,
    ): Long =
        parker
            .execute(TransferMode.SAFE, { micros }) { d ->
                val start = TimeSource.Monotonic.markNow()
                Worker.current.park(d, true)
                maxOf(start.elapsedNow().inWholeMicroseconds - d, 0L)
            }.result

    /**
     * The timer's wait strategy in virtual time: [waitStepFor] is asked what to wait, and a park returns
     * [overshoot] times later than asked. `Worker.park` measured 1.5 on a laptop (a 10 ms park returned after
     * 14.6 ms). A timer that parked for the whole remainder would be half the delay late here (100 ms for a
     * 200 ms deadline); halving ends every park before the deadline, so the deadline fires on the final nap.
     */
    @Test
    fun halvingAbsorbsAnOvershootOfLessThanTwo() {
        for (delay in listOf(500L, 2_000L, 10_000L, 200_000L, 5_000_000L, 60_000_000L)) {
            for (overshoot in listOf(1.0, 1.5, 1.9)) {
                var elapsed = 0L
                var parks = 0
                while (elapsed < delay) {
                    when (val step = waitStepFor(delay - elapsed)) {
                        is WaitStep.Nap -> elapsed += maxOf(step.micros, 1L)
                        is WaitStep.Park -> {
                            assertTrue(
                                step.micros <= (delay - elapsed) / 2,
                                "a park of ${step.micros} us for ${delay - elapsed} us remaining",
                            )
                            elapsed += (step.micros * overshoot).toLong()
                            parks++
                        }
                    }
                    assertTrue(parks < 1_000, "no progress toward a $delay us deadline")
                }
                assertTrue(
                    elapsed - delay <= 1L,
                    "a $delay us deadline was ${elapsed - delay} us late with parks returning ${overshoot}x late",
                )
            }
        }
    }

    @Test
    fun aRemainderOfNothingIsNoWait() {
        assertEquals(0L, (waitStepFor(0L) as WaitStep.Nap).micros)
        assertEquals(0L, (waitStepFor(-5L) as WaitStep.Nap).micros)
    }
}

private const val ROUNDS = 7
private const val LONG_DELAY_MICROS = 200_000L

/** How late a long deadline may fire at the median: three times its own park's median lateness, plus 30 ms. */
internal fun longDeadlineBoundMicros(parkLateMicros: List<Long>): Long = parkLateMicros.sorted()[parkLateMicros.size / 2] * 3 + 30_000L
