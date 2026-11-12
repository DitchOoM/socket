package com.ditchoom.socket.quic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [DeadlineTimer] under concurrent arming, disarming, firing and sweeping, on real threads. The model-checked
 * version of the queue's protocol is `DeadlineQueueLincheckTest`; these run it at volume with the real clock and
 * the real park and unpark, which the model does not.
 */
class DeadlineTimerRaceTests {
    private class Armed(
        val fires: AtomicInteger,
        val node: DeadlineQueue.Node,
        val disarmedByUs: Boolean,
    )

    @Test
    fun everyDeadlineEitherFiresExactlyOnceOrIsDisarmedAndNeverFiresWhileOthersArmDisarmAndSweep() {
        val timer = DeadlineTimer()
        val results = List(THREADS) { ArrayList<Armed>(PER_THREAD) }
        val workers =
            List(THREADS) { t ->
                thread(name = "arm-$t") {
                    val random = Random(t)
                    repeat(PER_THREAD) {
                        val fires = AtomicInteger()
                        // Most are far deadlines armed and disarmed at once (the stream-bound shape, enough of them to
                        // trigger sweeps); the rest are due within a few milliseconds and race the timer thread firing them.
                        val far = random.nextInt(10) < 7
                        val node = timer.schedule(if (far) 60_000L else random.nextLong(0, 4)) { fires.incrementAndGet() }
                        val disarmed =
                            if (far) {
                                node.disarm()
                            } else {
                                repeat(random.nextInt(50)) { Thread.onSpinWait() }
                                random.nextBoolean() && node.disarm()
                            }
                        results[t].add(Armed(fires, node, disarmed))
                    }
                }
            }
        workers.forEach { it.join() }
        // Everything near has been due for a while; give the timer thread its passes.
        val settle = TimeSource.Monotonic.markNow() + 20.seconds

        fun outcomes() = results.flatten().count { it.disarmedByUs || it.fires.get() == 1 }
        while (outcomes() < THREADS * PER_THREAD && settle.hasNotPassedNow()) Thread.sleep(10)
        results.flatten().forEach {
            if (it.disarmedByUs) {
                assertEquals(0, it.fires.get(), "a deadline fired after disarm() returned true")
            } else {
                assertEquals(1, it.fires.get(), "a deadline whose disarm() lost to firing did not fire exactly once")
            }
        }
        val limit = 2 * DeadlineQueue.COMPACT_MIN
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (timer.queued > limit && deadline.hasNotPassedNow()) Thread.sleep(10)
        assertTrue(timer.queued <= limit, "${timer.queued} deadlines still queued after the sweeps, more than $limit")
    }

    @Test
    fun anEarlierDeadlineArmedWhileTheThreadWorksOutHowLongToSleepIsStillMetWithinItsBound() {
        val timer = DeadlineTimer()
        val far = timer.schedule(60_000) { }
        val random = Random(7)
        var worst = 0L
        repeat(ROUNDS) {
            val fired = CountDownLatch(1)
            val mark = TimeSource.Monotonic.markNow()
            // No wait for the thread to park: this arming lands anywhere in its pass, which is the race.
            timer.schedule(random.nextLong(0, 2)) { fired.countDown() }
            assertTrue(fired.await(5, TimeUnit.SECONDS), "an earlier deadline was not met within 5 s (round $it): a lost wake-up")
            worst = maxOf(worst, mark.elapsedNow().inWholeMilliseconds)
        }
        println("[race] worst lateness of an earlier deadline over $ROUNDS rounds: $worst ms")
        far.dispose()
    }

    private companion object {
        const val THREADS = 8
        const val PER_THREAD = 20_000
        const val ROUNDS = 1_500
    }
}
