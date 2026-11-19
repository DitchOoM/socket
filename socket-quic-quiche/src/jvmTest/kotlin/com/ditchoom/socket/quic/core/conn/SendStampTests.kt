package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A recording's datagram-out time is when quiche wrote the datagram, not when the turn that wrote it began: the turn
 * runs its work first, and on a slow host that work (a caller's write, a copied datagram's receive) takes milliseconds.
 * CoreRecordingReplayTests failed on the macOS CI lane because a write's datagram was recorded 26 ms before quiche sent
 * it, so the replay sent it then and its PTO fell due 26 ms before the recording's; and an acknowledgement was recorded
 * before the datagrams it acknowledged.
 */
class SendStampTests {
    @Test
    fun aDatagramIsRecordedWhenQuicheWroteItAfterTheTurnsWork() {
        val api = StubQuicheApi()
        val rig = ConnRig(api)
        api.connSendQueue += BEFORE
        rig.pass(START)

        rig.loop.port.submit(Submit(rig, SlowWork(rig)))
        api.connSendQueue += LENGTH
        rig.pass(rig.clock.at.value)

        val recorded = ArrayList<TraceEvent>()
        rig.loop.port.submit(Submit(rig, Dump(TraceSink { recorded += it })))
        rig.pass(rig.clock.at.value)
        val outs = recorded.filterIsInstance<TraceEvent.DgramOut>()
        assertEquals(listOf(BEFORE, LENGTH), outs.map { it.len }, "each turn wrote its datagram: $recorded")
        val before = outs.single { it.len == BEFORE }
        val after = outs.single { it.len == LENGTH }
        assertEquals(
            WORK_TAKES.nanoseconds,
            after.at - before.at,
            "both turns began at one time; the second datagram is stamped after the work its turn ran first: $outs",
        )
    }

    /** Work inside the turn that takes [WORK_TAKES] of the loop's clock. */
    private class SlowWork(
        private val rig: ConnRig,
    ) : ConnWork() {
        override fun run(inside: Inside) {
            rig.clock.at = Nanos(rig.clock.at.value + WORK_TAKES)
        }

        override fun gone(end: ConnEnd) = Unit
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
        const val BEFORE = 100
        const val LENGTH = 123
        const val START = 1_000_000L
        const val WORK_TAKES = 2_000_000L
    }
}
