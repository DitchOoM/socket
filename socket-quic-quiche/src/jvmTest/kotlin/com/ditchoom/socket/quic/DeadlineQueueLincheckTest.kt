package com.ditchoom.socket.quic

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * [DeadlineQueue]'s lock-free protocol, model checked as the timer thread and the threads that arm and disarm use it.
 *
 * Any thread arms ([arm0]..) and disarms ([disarm0]..) a deadline; one thread, the timer's, fires what is due
 * ([fireDue15], [fireDue100]) at a time it is given, which is the virtual clock.
 *
 * The check is an audit, not a linearizability specification: `fireDue` is several steps (drain the inbox, then pop
 * and claim one by one), so a deadline armed or disarmed while it runs may or may not be fired by it, which no
 * sequential specification can express. Every outcome is recorded and a final [audit], after a `fireDue` that sees
 * everything, checks that each deadline fires at most once, not after a disarm that succeeded, not before it is due,
 * and not never unless it was disarmed (so a sweep that lost a live deadline is caught).
 *
 * The queue is built with a sweep size of 2 so that a few deadlines reach the sweep. Each slot is armed by one actor.
 */
class DeadlineQueueLincheckTest {
    private val queue = DeadlineQueue(compactMin = 2)
    private val handles = arrayOfNulls<DeadlineNode>(SLOTS)
    private val fires = Array(SLOTS) { AtomicInteger() }
    private val disarmedOk = Array(SLOTS) { AtomicInteger() }
    private val violations = AtomicInteger()

    // Written and read by the timer's actor only (one non-parallel group).
    private var now = 0L

    @Operation(nonParallelGroup = "arming")
    fun arm0(): Unit = arm(0)

    @Operation(nonParallelGroup = "arming")
    fun arm1(): Unit = arm(1)

    @Operation(nonParallelGroup = "arming")
    fun arm2(): Unit = arm(2)

    @Operation(nonParallelGroup = "disarming")
    fun disarm0(): Unit = disarm(0)

    @Operation(nonParallelGroup = "disarming")
    fun disarm1(): Unit = disarm(1)

    @Operation(nonParallelGroup = "disarming")
    fun disarm2(): Unit = disarm(2)

    @Operation(nonParallelGroup = "timer")
    fun fireDue15(): Unit = fireDue(15)

    @Operation(nonParallelGroup = "timer")
    fun fireDue100(): Unit = fireDue(100)

    /** After a `fireDue` that sees everything armed: every deadline has exactly one outcome. True when the audit holds. */
    @Operation(nonParallelGroup = "timer")
    fun audit(): Boolean {
        fireDue(100)
        for (slot in 0 until SLOTS) {
            if (handles[slot] == null) continue
            val fired = fires[slot].get()
            val disarmed = disarmedOk[slot].get()
            if (fired > 1 || fired + disarmed != 1) violations.incrementAndGet()
        }
        return violations.get() == 0
    }

    private fun arm(slot: Int) {
        val due = DUES[slot]
        handles[slot] =
            queue.arm(due) {
                fires[slot].incrementAndGet()
                if (due > now) violations.incrementAndGet()
            }
    }

    private fun disarm(slot: Int) {
        if (handles[slot]?.disarm() == true) disarmedOk[slot].incrementAndGet()
    }

    private fun fireDue(at: Long) {
        now = at
        queue.fireDue(at)
    }

    @Test
    fun aDeadlineFiresOnceNeverAfterADisarmNeverEarlyAndTheSweepLosesNone() {
        ModelCheckingOptions()
            .sequentialSpecification(DeadlineQueueSpec::class.java)
            .addCustomScenario {
                initial { actor(DeadlineQueueLincheckTest::arm0) }
                parallel {
                    thread {
                        actor(DeadlineQueueLincheckTest::arm1)
                        actor(DeadlineQueueLincheckTest::arm2)
                        actor(DeadlineQueueLincheckTest::disarm1)
                    }
                    thread {
                        actor(DeadlineQueueLincheckTest::disarm0)
                        actor(DeadlineQueueLincheckTest::fireDue15)
                        actor(DeadlineQueueLincheckTest::fireDue100)
                    }
                }
                post { actor(DeadlineQueueLincheckTest::audit) }
            }.addCustomScenario {
                parallel {
                    thread {
                        actor(DeadlineQueueLincheckTest::arm0)
                        actor(DeadlineQueueLincheckTest::arm1)
                        actor(DeadlineQueueLincheckTest::arm2)
                    }
                    thread {
                        actor(DeadlineQueueLincheckTest::disarm0)
                        actor(DeadlineQueueLincheckTest::disarm1)
                        actor(DeadlineQueueLincheckTest::disarm2)
                    }
                    thread {
                        actor(DeadlineQueueLincheckTest::fireDue15)
                        actor(DeadlineQueueLincheckTest::fireDue100)
                    }
                }
                post { actor(DeadlineQueueLincheckTest::audit) }
            }.threads(3)
            .actorsPerThread(3)
            .actorsAfter(1)
            // Only the scenarios above: a generated one could run the audit beside an arming, which it is not for.
            .iterations(0)
            .invocationsPerIteration(LincheckBudget.scaled(5_000))
            .check(this::class)
    }

    private companion object {
        const val SLOTS = 3
        val DUES = longArrayOf(10, 20, 30)
    }
}

/** What every operation promises to a caller: nothing observable but that [audit] holds. */
class DeadlineQueueSpec {
    fun arm0() = Unit

    fun arm1() = Unit

    fun arm2() = Unit

    fun disarm0() = Unit

    fun disarm1() = Unit

    fun disarm2() = Unit

    fun fireDue15() = Unit

    fun fireDue100() = Unit

    fun audit(): Boolean = true
}
