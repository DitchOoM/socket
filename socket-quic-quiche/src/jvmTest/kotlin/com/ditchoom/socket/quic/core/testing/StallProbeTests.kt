package com.ditchoom.socket.quic.core.testing

import java.lang.ref.Reference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** [StallProbe] names what a stalled thread was doing: two stalls of known cause, each attributed to it. */
class StallProbeTests {
    @Test
    fun aReaderAsleepIsTimedWaitingWithNoCpuAndNotHeldBySafepoints() {
        // On a runner short of CPU the sampler parks 1 ms and is run again a scheduler slice later (10 ms seen on a
        // 3-vCPU macOS runner), so it takes about one sample per slice. The sleep is long enough for MIN_SAMPLES
        // inside it plus a slice lost at each edge, measured on this runner now, and never under 20 ms.
        val sleepNanos = maxOf(MIN_SLEEP_NANOS, (StallProbe.MIN_SAMPLES + 2) * slice())
        val slept = CompletableFuture<LongArray>()
        val reader =
            Thread({
                spin(5)
                val from = System.nanoTime()
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                slept.complete(longArrayOf(from, System.nanoTime()))
            }, "stall-probe-sleeper")
        val probe = StallProbe.start(reader, StallProbe.Queue.Unseen)
        reader.start()
        val (from, to) = slept.get(10, TimeUnit.SECONDS)
        reader.join()
        val explanation = probe.stop().explain(from, to)
        println("[stall probe] sleep of ${sleepNanos / 1_000_000} ms: $explanation")
        val stall = attributed(explanation)
        assertEquals(Thread.State.TIMED_WAITING, stall.dominant, "$stall")
        assertTrue(stall.targetCpuMicros < stall.windowMicros / 4, "the sleeper used CPU: $stall")
        // The JVM may still pause during the sleep (a G1 remark took 10 ms of a 72 ms sleep on a loaded macOS runner):
        // what the probe must not do is blame the sleep on it.
        val safepoints = assertIs<Safepoints.Logged>(stall.safepoints, "$stall")
        assertTrue(safepoints.overlapMicros < stall.windowMicros / 2, "safepoints covered half the sleep: $stall")
    }

    @Test
    fun aSpinningReaderStoppedBySafepointsIsAttributedToThem() {
        val spinning = AtomicBoolean(true)
        val spins = AtomicLong()
        val reader =
            Thread({
                while (spinning.get()) spins.incrementAndGet()
            }, "stall-probe-spinner")
        reader.start()
        val probe = StallProbe.start(reader, StallProbe.Queue.Unseen)
        Thread.sleep(20)
        // A live heap for the full collections to mark, so each holds every thread at a safepoint for a while.
        val live = List(LIVE_ARRAYS) { LongArray(16) }
        val from = System.nanoTime()
        repeat(COLLECTIONS) { System.gc() }
        val to = System.nanoTime()
        Reference.reachabilityFence(live)
        Thread.sleep(5)
        spinning.set(false)
        reader.join()
        val explanation = probe.stop().explain(from, to)
        println("[stall probe] $explanation")
        // The safepoint attribution comes from the JVM's log, not from samples: the probe is held at the safepoint too.
        val safepoints = assertIs<Safepoints.Logged>(explanation.safepoints, "$explanation")
        val window = (to - from) / 1_000
        assertTrue(safepoints.overlapMicros >= window / 2, "safepoints covered under half the stall: $explanation")
        when (explanation) {
            is Explanation.Attributed -> assertEquals(Thread.State.RUNNABLE, explanation.stall.dominant, "$explanation")
            is Explanation.TooFewSamples -> Unit
        }
    }

    @Test
    fun aStallTooShortToHoldTwoSamplesIsTooFewSamplesNotAnError() {
        val probe = StallProbe.start(Thread.currentThread(), StallProbe.Queue.Unseen)
        Thread.sleep(5)
        // One microsecond: the sampler, 1 ms apart at best, cannot land two samples inside it.
        val from = System.nanoTime()
        val to = from + 1_000
        Thread.sleep(5)
        val explanation = assertIs<Explanation.TooFewSamples>(probe.stop().explain(from, to))
        assertTrue(explanation.samplesInAll >= 1, "the probe never sampled although start() returned: $explanation")
    }

    /** The stall attributed, or a failure carrying why it could not be. */
    private fun attributed(explanation: Explanation): Stall =
        when (explanation) {
            is Explanation.Attributed -> explanation.stall
            is Explanation.TooFewSamples -> fail("$explanation")
        }

    /** The longest of [PARKS] 1 ms parks, as this thread saw it: how late this runner runs a parked thread again. */
    private fun slice(): Long =
        (1..PARKS).maxOf {
            val before = System.nanoTime()
            LockSupport.parkNanos(1_000_000)
            System.nanoTime() - before
        }

    private fun spin(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) Thread.onSpinWait()
    }

    private companion object {
        const val LIVE_ARRAYS = 500_000
        const val COLLECTIONS = 3
        const val PARKS = 20
        const val MIN_SLEEP_NANOS = 20_000_000L
    }
}
