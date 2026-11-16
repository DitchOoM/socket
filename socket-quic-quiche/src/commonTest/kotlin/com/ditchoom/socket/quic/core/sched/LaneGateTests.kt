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
    fun aStoppedLoopsCloseIsNeverHandedBackToTheLoop() {
        val gate = LaneGate()
        assertEquals(Lend.Lent, gate.reserve())
        assertSame(Shutdown.LanesOut, gate.shut(Closer.LastLane))
        assertSame(Shutdown.LanesOut, gate.shut(Closer.Loop))
        assertEquals(LaneBack.ThisThreadCloses, gate.back())
    }
}
