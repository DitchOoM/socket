package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.DeadlineFired
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.Rearmables
import com.ditchoom.socket.quic.Wanted
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals

class RearmablesTests {
    private val loop = Rearmables()
    private var fired = 0
    private val deadline = loop.make(DeadlineFired { fired++ })

    @Test
    fun anArmedDeadlineFiresOnceWhenDueAndThenIsNotArmed() {
        deadline.arm(10)
        loop.fireDue(9)
        assertEquals(0, fired)
        loop.fireDue(10)
        loop.fireDue(20)
        assertEquals(1, fired)
        assertEquals(Wanted.None, deadline.wanted())
        assertEquals(0, loop.size)
    }

    @Test
    fun aDeadlineMovedLaterDoesNotFireAtItsOldTimeAndTheLoopSleepsTowardsTheNewOne() {
        deadline.arm(10)
        assertEquals(NextDue.at(10), loop.nextDue())
        deadline.arm(30)
        loop.fireDue(10)
        assertEquals(0, fired)
        assertEquals(NextDue.at(30), loop.nextDue())
        loop.fireDue(30)
        assertEquals(1, fired)
    }

    @Test
    fun aDeadlineMovedLaterWithoutTheLoopHavingLookedIsMovedWhenItsOldKeyComesUp() {
        deadline.arm(10)
        loop.fireDue(0)
        deadline.arm(30)
        // Taken by the pass at 10, which finds the new time and moves it instead of firing.
        loop.fireDue(10)
        assertEquals(0, fired)
        assertEquals(1, loop.size)
        loop.fireDue(30)
        assertEquals(1, fired)
    }

    @Test
    fun aDeadlineMovedEarlierFiresAtTheEarlierTime() {
        deadline.arm(30)
        loop.fireDue(0)
        deadline.arm(10)
        assertEquals(NextDue.at(10), loop.nextDue())
        loop.fireDue(10)
        assertEquals(1, fired)
        loop.fireDue(30)
        assertEquals(1, fired)
    }

    @Test
    fun aDisarmedDeadlineNeverFiresAndLeavesTheHeapAtTheNextPass() {
        deadline.arm(10)
        loop.fireDue(0)
        deadline.disarm()
        assertEquals(NextDue.None, loop.nextDue())
        assertEquals(0, loop.size)
        loop.fireDue(100)
        assertEquals(0, fired)
    }

    @Test
    fun anActionThatReArmsItsOwnDeadlineIsPlacedByTheNextPassAndNotFiredTwiceInOne() {
        var runs = 0
        val made = ArrayList<Rearmable>(1)
        made.add(loop.make(DeadlineFired { runs++.also { made[0].arm(5) } }))
        val self = made[0]
        self.arm(5)
        loop.fireDue(10)
        assertEquals(1, runs)
        assertEquals(Wanted.at(5), self.wanted())
        loop.fireDue(10)
        assertEquals(2, runs)
    }

    @Test
    fun manyDeadlinesFireInTheOrderOfTheirTimesWhateverOrderTheyMovedIn() {
        val order = ArrayList<Int>()
        val all = List(8) { i -> loop.make(DeadlineFired { order.add(i) }) }
        all.forEachIndexed { i, d -> d.arm(100L - i) }
        loop.fireDue(0)
        all.forEachIndexed { i, d -> d.arm(10L + (i * 7) % 8) }
        all[3].disarm()
        loop.fireDue(1_000)
        // Wanted at 10, 17, 16, (15, disarmed), 14, 13, 12, 11.
        assertEquals(listOf(0, 7, 6, 5, 4, 2, 1), order)
    }

    @Test
    fun theEarlierOfALoopPortsNodesAndRearmablesIsWhenItNextNeedsToRun() {
        val port = LoopPort(FakePoller())
        val timer = port.rearmable(DeadlineFired { fired++ })
        port.arm(Nanos(20), Runnable { })
        port.arm(timer, Nanos(10))
        assertEquals(NextDue.at(10), port.nextDue(Nanos(0)))
        port.arm(timer, Nanos(30))
        assertEquals(NextDue.at(20), port.nextDue(Nanos(0)))
        port.fireDue(Nanos(30))
        assertEquals(1, fired)
    }

    @Test
    fun theActionIsToldTheTimeOfTheArmingThatFellDue() {
        val told = ArrayList<Long>()
        val timed = loop.make(DeadlineFired { told.add(it) })
        timed.arm(30)
        loop.fireDue(0)
        timed.arm(12)
        loop.fireDue(20)
        assertEquals(listOf(12L), told)
    }
}
