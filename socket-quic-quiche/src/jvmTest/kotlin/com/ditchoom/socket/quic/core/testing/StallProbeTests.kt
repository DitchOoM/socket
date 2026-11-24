package com.ditchoom.socket.quic.core.testing

import java.lang.ref.Reference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [StallProbe] names what a stalled thread was doing: two stalls of known cause, each attributed to it. */
class StallProbeTests {
    @Test
    fun aReaderAsleepFor20MsIsTimedWaitingWithNoCpuAndNotHeldBySafepoints() {
        val slept = CompletableFuture<LongArray>()
        val reader =
            Thread({
                spin(5)
                val from = System.nanoTime()
                Thread.sleep(20)
                slept.complete(longArrayOf(from, System.nanoTime()))
            }, "stall-probe-sleeper")
        val probe = StallProbe.start(reader, StallProbe.Queue.Unseen)
        reader.start()
        val (from, to) = slept.get(10, TimeUnit.SECONDS)
        reader.join()
        val stall = probe.stop().explain(from, to)
        println("[stall probe] $stall")
        assertEquals(Thread.State.TIMED_WAITING, stall.dominant, "$stall")
        assertTrue(stall.targetCpuMicros < stall.windowMicros / 4, "the sleeper used CPU: $stall")
        val safepoints = assertIs<Safepoints.Logged>(stall.safepoints, "$stall")
        // The JVM may still pause during the sleep (a G1 remark took 10 ms of a 72 ms sleep on a loaded macOS runner):
        // what the probe must not do is blame the sleep on it.
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
        val stall = probe.stop().explain(from, to)
        println("[stall probe] $stall")
        assertEquals(Thread.State.RUNNABLE, stall.dominant, "$stall")
        val safepoints = assertIs<Safepoints.Logged>(stall.safepoints, "$stall")
        assertTrue(safepoints.overlapMicros >= stall.windowMicros / 2, "safepoints covered under half the stall: $stall")
    }

    private fun spin(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) Thread.onSpinWait()
    }

    private companion object {
        const val LIVE_ARRAYS = 500_000
        const val COLLECTIONS = 3
    }
}
