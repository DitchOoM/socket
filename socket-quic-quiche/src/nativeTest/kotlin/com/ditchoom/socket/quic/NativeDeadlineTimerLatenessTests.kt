package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How late [NativeDeadlineTimer] runs a deadline, on its own thread and with the machine otherwise as it is.
 * It prints the distribution (`TIMER-LATENESS`) rather than asserting a bound a loaded machine would break;
 * the one assertion is a sanity limit that only a timer that does not fire at all would exceed.
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
    fun printsHowLateDeadlinesFire() =
        runTest {
            for (delay in listOf(500.microseconds, 2.milliseconds, 10.milliseconds)) {
                val s = lateness(delay)
                println("TIMER-LATENESS delay=$delay p50_us=${s[s.size / 2]} p99_us=${s[s.size * 99 / 100]} max_us=${s.last()}")
                assertTrue(s[s.size / 2] < 1_000_000, "a deadline fired a second late at the median")
            }
        }

    /**
     * `Worker.park` returns about 1.5 times later than it is asked to, so a timer that parked for the whole wait
     * fired a 200 ms deadline 100 ms late, every time. The bound is loose enough for a loaded runner (the median
     * of five, 60 ms) and tight enough that parking for the whole wait fails it.
     */
    @Test
    fun aLongDeadlineDoesNotFireHalfItsLengthLate() =
        runTest {
            val timer = NativeDeadlineTimer()
            try {
                val late =
                    withContext(Dispatchers.Default) {
                        val fired = Channel<Long>(Channel.UNLIMITED)
                        List(5) {
                            val start = TimeSource.Monotonic.markNow()
                            timer.schedule(200.milliseconds) { fired.trySend(start.elapsedNow().inWholeMilliseconds) }
                            withTimeout(10.seconds) { fired.receive() } - 200
                        }.sorted()
                    }
                println("TIMER-LATENESS-LONG late_ms=$late")
                assertTrue(late[0] >= 0, "a 200 ms deadline fired early: $late")
                assertTrue(late[2] < 60, "a 200 ms deadline fired ${late[2]} ms late at the median of $late")
            } finally {
                timer.close()
            }
        }
}
