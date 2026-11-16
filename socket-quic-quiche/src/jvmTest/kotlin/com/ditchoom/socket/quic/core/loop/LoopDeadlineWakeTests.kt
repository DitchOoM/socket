package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.ready.SelectorPoller
import com.ditchoom.socket.quic.core.ready.jdkStackOfThisRuntime
import com.ditchoom.socket.quic.core.testing.loopOver
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deadlines armed from threads that are not the loop's, while the loop sleeps and wakes: none is lost and none is
 * late. A lost wake shows as a deadline that never fires, because the loop sleeps without a deadline of its own.
 */
class LoopDeadlineWakeTests {
    @Test
    fun deadlinesArmedFromOtherThreadsAllFireOnTime() {
        val clock = MonotonicClock
        val loop = loopOver(SelectorPoller(clock, jdkStackOfThisRuntime().scan), clock, maxDatagrams = 4)
        startLoopThread(loop, "core-deadline-wake")

        val armers = 4
        val perArmer = 400
        val fired = CountDownLatch(armers * perArmer)
        val worstLateNanos = AtomicLong()
        val threads =
            List(armers) { armer ->
                Thread {
                    val random = Random(armer)
                    repeat(perArmer) {
                        val due = clock.now().value + random.nextLong(0, 3_000_000)
                        loop.port.arm(
                            Nanos(due),
                            Runnable {
                                worstLateNanos.accumulateAndGet(clock.now().value - due, ::maxOf)
                                fired.countDown()
                            },
                        )
                        // Long enough for the loop to go back to sleep between some of the arms.
                        if (random.nextInt(4) == 0) Thread.sleep(random.nextLong(1, 6))
                    }
                }.also(Thread::start)
            }
        threads.forEach { it.join() }
        val allFired = fired.await(10, TimeUnit.SECONDS)
        val unfired = fired.count
        val closed = CompletableFuture<Unit>()
        loop.close { closed.complete(Unit) }
        closed.get(5, TimeUnit.SECONDS)
        println(
            "[loop deadline wake] ${armers * perArmer} deadlines, worst lateness ${worstLateNanos.get() / 1_000} µs, loop wakes by others ${loop.callerCounts.loopWakes}",
        )
        assertTrue(allFired, "$unfired of ${armers * perArmer} deadlines never fired: a wake was lost")
        assertTrue(worstLateNanos.get() < 250_000_000L, "a deadline fired ${worstLateNanos.get() / 1_000_000} ms late")
    }
}
