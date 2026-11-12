package com.ditchoom.socket.quic

import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun DeadlineQueue.nextDueNanos(now: Long = 0L): Long =
    when (val next = nextDue(now)) {
        is NextDue.At -> next.nanos
        NextDue.None -> error("nothing is queued")
    }

/**
 * [DeadlineQueue], the one implementation behind the JVM's `DeadlineTimer` and Kotlin/Native's
 * `NativeDeadlineTimer`: ordering, disarm and sweep, with the time passed in, so no clock decides anything.
 * Runs on every target. (The model-checked version of the same protocol is `DeadlineQueueLincheckTest`, JVM only.)
 */
class DeadlineQueueTests {
    private val fired = mutableListOf<String>()

    private fun DeadlineQueue.armLogging(
        due: Long,
        name: String,
    ) = arm(due, Runnable { fired += name })

    @Test
    fun firesInDueOrderAndEqualDueTimesInTheOrderTheyWereArmed() {
        val q = DeadlineQueue()
        q.armLogging(30, "c")
        q.armLogging(10, "a1")
        q.armLogging(20, "b")
        q.armLogging(10, "a2")
        q.fireDue(25)
        assertEquals(listOf("a1", "a2", "b"), fired)
        q.fireDue(100)
        assertEquals(listOf("a1", "a2", "b", "c"), fired)
    }

    @Test
    fun nothingFiresBeforeItIsDue() {
        val q = DeadlineQueue()
        q.armLogging(10, "a")
        q.fireDue(9)
        assertEquals(emptyList(), fired)
        q.fireDue(10)
        assertEquals(listOf("a"), fired)
    }

    @Test
    fun aDisarmedDeadlineNeverFiresAndDisarmingAfterItFiredChangesNothing() {
        val q = DeadlineQueue()
        val a = q.armLogging(10, "a")
        val b = q.armLogging(10, "b")
        assertTrue(a.disarm())
        assertFalse(a.disarm(), "a second disarm reported success")
        q.fireDue(10)
        assertEquals(listOf("b"), fired)
        assertFalse(b.disarm(), "disarming a deadline that fired reported success")
        q.fireDue(1_000)
        assertEquals(listOf("b"), fired)
    }

    @Test
    fun reArmingIsDisarmAndArmAndOnlyTheNewOneFires() {
        val q = DeadlineQueue()
        val old = q.armLogging(10, "old")
        old.dispose()
        q.armLogging(50, "new")
        q.fireDue(20)
        assertEquals(emptyList(), fired)
        q.fireDue(50)
        assertEquals(listOf("new"), fired)
    }

    @Test
    fun nextDueStillNamesAFarDisarmedDeadlineSoTheThreadKeepsSleepingTowardsIt() {
        val q = DeadlineQueue()
        assertEquals(NextDue.None, q.nextDue(0))
        val far = 10 * DeadlineQueue.STALE_HORIZON_NANOS
        val a = q.armLogging(far, "a")
        q.armLogging(far + 5, "b")
        a.dispose()
        assertEquals(far, q.nextDueNanos(), "a far disarmed deadline was skipped: the thread would go on to wake for the next arming")
        q.fireDue(far + 5)
        assertEquals(listOf("b"), fired)
    }

    @Test
    fun nextDueDropsANearDisarmedDeadlineAndGoesIdleWhenNothingElseIsQueued() {
        val q = DeadlineQueue()
        val near = q.armLogging(DeadlineQueue.STALE_HORIZON_NANOS / 2, "near")
        val far = q.armLogging(10 * DeadlineQueue.STALE_HORIZON_NANOS, "far")
        near.dispose()
        assertEquals(10 * DeadlineQueue.STALE_HORIZON_NANOS, q.nextDueNanos(), "a near disarmed deadline was slept towards")
        far.dispose()
        // Only the far one is left and it is disarmed too: still named, it is far.
        assertEquals(10 * DeadlineQueue.STALE_HORIZON_NANOS, q.nextDueNanos())
        // Time passes until it is near: now it is dropped and there is nothing to wait for.
        assertEquals(NextDue.None, q.nextDue(10 * DeadlineQueue.STALE_HORIZON_NANOS - 1))
        assertEquals(0, q.size)
    }

    @Test
    fun anActionThatArmsAnotherFiresItOnTheNextPassOnlyWhenDue() {
        val q = DeadlineQueue()
        q.arm(
            10,
            Runnable {
                fired += "first"
                q.armLogging(15, "second")
            },
        )
        q.fireDue(10)
        assertEquals(listOf("first"), fired)
        q.fireDue(15)
        assertEquals(listOf("first", "second"), fired)
    }

    @Test
    fun anActionThatThrowsDoesNotStopTheOthersFromFiring() {
        val q = DeadlineQueue()
        q.arm(10, Runnable { error("an action that throws") })
        q.armLogging(20, "after")
        q.fireDue(100)
        assertEquals(listOf("after"), fired)
    }

    @Test
    fun aSweepDropsDisarmedDeadlinesWhenTheyAreMostOfTheQueueAndKeepsTheLiveOnes() {
        val q = DeadlineQueue()
        val dead = (1..5_000).map { q.arm(1_000_000L + it, Runnable { fired += "dead$it" }) }
        val live = q.armLogging(2_000_000, "live")
        dead.forEach { it.dispose() }
        assertTrue(q.needsSweep(), "5,000 disarmed of 5,001 asked for no sweep")
        // The next drain moves them into the heap, and the sweep runs with the disarmed ones in it.
        q.armLogging(3_000_000, "live2")
        assertEquals(2_000_000L, q.nextDueNanos(), "a disarmed deadline survived the sweep")
        assertTrue(q.size <= 3, "the queue holds only what is live, not ${q.size}")
        assertFalse(q.needsSweep())
        q.fireDue(10_000_000)
        assertEquals(listOf("live", "live2"), fired)
        live.dispose()
    }

    @Test
    fun aSweepOfASmallQueueKeepsEveryLiveDeadline() {
        val q = DeadlineQueue(compactMin = 2)
        val a = q.armLogging(10, "a")
        q.armLogging(20, "b")
        val c = q.armLogging(30, "c")
        a.dispose()
        c.dispose()
        q.fireDue(100)
        assertEquals(listOf("b"), fired)
    }
}
