package com.ditchoom.socket.quic.core.sched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** [LaneGate]: a shut socket lends no lane, and it closes only once every lane is back. */
class LaneGateTests {
    @Test
    fun aSocketWithNoLaneOutIsQuietAtOnce() {
        val gate = LaneGate()
        assertSame(Shutdown.Quiet, gate.shut(Closer.Loop))
        assertEquals(Lend.Shut, gate.reserve())
    }

    @Test
    fun aShutSocketLendsNoLaneAndTheLastLaneBackWakesTheLoop() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertEquals(Lend.Lent, gate.reserve())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertEquals(Lend.Shut, gate.reserve(), "no lane after the shut")
        assertEquals(LaneBack.Returned, gate.back())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop), "one lane is still out")
        assertEquals(LaneBack.LoopCloses, gate.back())
        assertSame(Shutdown.Quiet, gate.shut(Closer.Loop))
        assertEquals(0, gate.lanesOut())
    }

    @Test
    fun withTheLoopStoppedTheLastLaneBackClosesTheSocket() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertSame(Shutdown.LanesOut, gate.shut(Closer.LastLane), "the loop stopped while the lane was out")
        assertEquals(LaneBack.ThisThreadCloses, gate.back())
    }

    @Test
    fun aCutSocketWithNoThreadInsideASendIsQuietWithItsLanesStillOutAndAdmitsNoSend() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertSame(Shutdown.Quiet, gate.cut(Closer.Loop), "no thread is inside a send: the socket closes now")
        assertEquals(1, gate.lanesOut())
        assertEquals(SendEntry.Cut, gate.enterSend(), "the lane still out never reaches the socket")
        assertEquals(LaneBack.Returned, gate.back(), "the socket was closed already: its lane coming back closes nothing")
        assertEquals(0, gate.lanesOut())
    }

    @Test
    fun aCutWithAThreadInsideASendLeavesTheCloseToTheLastSenderOut() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertEquals(Lend.Lent, gate.reserve())
        assertEquals(SendEntry.Entered, gate.enterSend())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertSame(Shutdown.Sending, gate.cut(Closer.Loop))
        assertEquals(SendEntry.Cut, gate.enterSend(), "the other lane is refused")
        assertSame(Shutdown.Sending, gate.shut(Closer.Loop), "asked again while the send is inside")
        assertEquals(LaneBack.LoopCloses, gate.leaveSend(), "the last sender out wakes the loop")
        assertSame(Shutdown.Quiet, gate.shut(Closer.Loop))
        assertEquals(2, gate.lanesOut())
    }

    @Test
    fun aCutSocketWhoseLoopStoppedIsClosedByTheLastSenderOut() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertEquals(SendEntry.Entered, gate.enterSend())
        assertSame(Shutdown.Sending, gate.cut(Closer.Loop))
        assertSame(Shutdown.Sending, gate.shut(Closer.LastLane), "the loop stopped while the send was inside")
        assertEquals(LaneBack.ThisThreadCloses, gate.leaveSend())
        assertEquals(SendEntry.Cut, gate.enterSend())
        assertSame(Shutdown.Quiet, gate.cut(Closer.Loop))
    }

    @Test
    fun anOpenSocketsSendsAreCountedInAndOut() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertEquals(SendEntry.Entered, gate.enterSend())
        assertEquals(1, gate.sending())
        assertEquals(LaneBack.Returned, gate.leaveSend())
        assertEquals(0, gate.sending())
        assertEquals(LaneBack.Returned, gate.back())
    }

    @Test
    fun aStoppedLoopsCloseIsNeverHandedBackToTheLoop() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.LastLane))
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertEquals(LaneBack.ThisThreadCloses, gate.back())
    }
}
