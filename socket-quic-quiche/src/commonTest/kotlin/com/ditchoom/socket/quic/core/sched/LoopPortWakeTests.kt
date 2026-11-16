package com.ditchoom.socket.quic.core.sched

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.FakePoller
import com.ditchoom.socket.quic.core.testing.RecordingWork
import com.ditchoom.socket.quic.core.time.Nanos
import kotlinx.coroutines.Runnable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The wake rule of [LoopPort]: a thread wakes a sleeping loop when it submits work or arms a deadline earlier than the
 * time the loop sleeps until, and never wakes a loop that is awake or already woken.
 */
class LoopPortWakeTests {
    private val poller = FakePoller()
    private val port = LoopPort(poller)

    @Test
    fun anAwakeLoopIsNeverWoken() {
        port.submit(RecordingWork())
        port.arm(Nanos(10L), Runnable { })
        assertEquals(0, poller.wakes)
    }

    @Test
    fun workWakesASleepingLoopOnce() {
        port.beginSleep(NextDue.at(1_000L))
        port.submit(RecordingWork())
        port.submit(RecordingWork())
        assertEquals(1, poller.wakes)
    }

    @Test
    fun aDeadlineEarlierThanTheSleepWakesAndALaterOneDoesNot() {
        port.beginSleep(NextDue.at(1_000L))
        port.arm(Nanos(2_000L), Runnable { })
        assertEquals(0, poller.wakes)
        port.arm(Nanos(1_000L), Runnable { })
        assertEquals(0, poller.wakes)
        port.arm(Nanos(999L), Runnable { })
        assertEquals(1, poller.wakes)
        port.arm(Nanos(1L), Runnable { })
        assertEquals(1, poller.wakes)
    }

    @Test
    fun aLoopThatSleepsWithNoDeadlineIsWokenByAnyDeadline() {
        port.beginSleep(NextDue.None)
        port.arm(Nanos(Long.MAX_VALUE - 1), Runnable { })
        assertEquals(1, poller.wakes)
    }

    @Test
    fun endingTheSleepLetsTheNextSleepBeWoken() {
        port.beginSleep(NextDue.None)
        port.submit(RecordingWork())
        port.endSleep()
        port.runWork()
        port.beginSleep(NextDue.None)
        port.submit(RecordingWork())
        assertEquals(2, poller.wakes)
    }

    @Test
    fun aSleepIsSkippedWhenWorkOrADeadlineArrivedFirst() {
        port.submit(RecordingWork())
        assertEquals(SleepPlan.Skip, port.beginSleep(NextDue.None))
        port.endSleep()
        port.runWork()
        assertEquals(SleepPlan.Await, port.beginSleep(NextDue.None))
        port.endSleep()
        port.arm(Nanos(5L), Runnable { })
        assertEquals(SleepPlan.Skip, port.beginSleep(NextDue.None))
    }

    @Test
    fun workRunsOnceOldestFirstAndDeadlinesFireWhenDue() {
        val first = RecordingWork()
        val second = RecordingWork()
        port.submit(first)
        port.submit(second)
        var fired = 0
        port.arm(Nanos(10L), Runnable { fired++ })
        assertEquals(2, port.runWork())
        assertEquals(0, port.runWork())
        assertEquals(1, first.runs)
        assertEquals(1, second.runs)
        port.fireDue(Nanos(9L))
        assertEquals(0, fired)
        port.fireDue(Nanos(10L))
        assertEquals(1, fired)
    }

    @Test
    fun stoppingTellsQueuedAndLaterWorkHowTheLoopEnded() {
        val queued = RecordingWork()
        port.submit(queued)
        port.stop(ConnEnd.LoopStopped)
        val late = RecordingWork()
        port.submit(late)
        assertSame(ConnEnd.LoopStopped, queued.goneWith.single())
        assertSame(ConnEnd.LoopStopped, late.goneWith.single())
        assertEquals(0, queued.runs + late.runs)
    }
}
