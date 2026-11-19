package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A recording's timer firing is a run of quiche's timeout, so it is recorded only when quiche's own timer has fallen due.
 * The deadline is armed from the turn's start, before quiche read its clock for the wait, so it can fall due first; quiche
 * then has nothing to do. CoreRecordingReplayTests on CI FFM: the client's deadline fell due 0.15 ms before the server's
 * HANDSHAKE_DONE arrived and quiche sent nothing, but the firing was recorded, and the replay, whose Handshake probe was
 * due by then, sent one there ("our datagram 3 [Handshake] is one more than the capture holds").
 */
class TimerFiredStampTests {
    @Test
    fun aDeadlineThatFallsDueBeforeQuichesTimerIsNeitherRunNorRecorded() {
        val api = StubQuicheApi()
        val rig = ConnRig(api)
        api.connTimeout = ARMED_FOR.nanoseconds
        rig.pass(0)

        // quiche read its clock after the turn began: at the deadline, its timer is still LEFT away.
        api.connTimeout = LEFT.nanoseconds
        rig.pass(ARMED_FOR)
        assertEquals(0, api.onTimeoutCount, "quiche's timer had not fallen due")

        api.connTimeout = Duration.ZERO
        rig.pass(ARMED_FOR + LEFT)
        assertEquals(1, api.onTimeoutCount, "quiche's timer fell due")

        api.connTimeout = null
        assertEquals(listOf((ARMED_FOR + LEFT).nanoseconds), firings(rig), "only the run of quiche's timeout is recorded")
    }

    private fun firings(rig: ConnRig): List<Duration> {
        val recorded = ArrayList<TraceEvent>()
        rig.loop.port.submit(Submit(rig, Dump(TraceSink { recorded += it })))
        rig.pass(rig.clock.at.value)
        return recorded.filterIsInstance<TraceEvent.TimerFired>().map { it.at }
    }

    private class Dump(
        private val sink: TraceSink,
    ) : ConnWork() {
        override fun run(inside: Inside) = inside.dump(sink)

        override fun gone(end: ConnEnd) = Unit
    }

    private class Submit(
        private val rig: ConnRig,
        private val work: ConnWork,
    ) : LoopWork() {
        override fun run(port: LoopPort) = rig.conn.submitFromLoop(work)

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        val ARMED_FOR = 5.milliseconds.inWholeNanoseconds
        val LEFT = 2.milliseconds.inWholeNanoseconds
    }
}
