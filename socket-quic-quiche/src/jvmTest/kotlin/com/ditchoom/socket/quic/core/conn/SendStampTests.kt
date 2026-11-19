package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.VirtualClock
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

    /**
     * quiche reads its clock well after the write began, as a cold downcall does: the datagram is stamped then, and its
     * round trip and probe are measured from then. CoreRecordingReplayTests on FFM, the JVM's first connection: the
     * Initial was recorded 0.71 ms before quiche stamped it, the replay's first round trip was that much longer, its
     * probe fell due 2.5 ms after the recorded one ran, and the replay waited 200 ms for the next.
     */
    @Test
    fun aDatagramIsRecordedWhenQuicheStampedItNotWhenTheCallReachingQuicheBegan() {
        val recorded = sentOnce(ColdSend(StubQuicheApi(), VirtualClock()))
        assertEquals(ENTRY_TAKES.nanoseconds, recorded.at, "stamped when quiche read its clock: $recorded")
    }

    /** A pacer's time later than the write is not when the datagram left: it is in the batch once quiche returns. */
    @Test
    fun aDatagramQuicheWantsLaterIsRecordedNoLaterThanItsWrite() {
        val recorded = sentOnce(ColdSend(StubQuicheApi(), VirtualClock(), pacedBy = PACED_BY))
        assertEquals((ENTRY_TAKES + QUICHE_TAKES).nanoseconds, recorded.at, "in the batch when quiche returned: $recorded")
    }

    /** The one datagram a turn at zero wrote over [api], as recorded. */
    private fun sentOnce(api: ColdSend): TraceEvent.DgramOut {
        val rig = ConnRig(api, clock = api.clock)
        api.stub.connSendQueue += LENGTH
        rig.pass(0)
        val recorded = ArrayList<TraceEvent>()
        rig.loop.port.submit(Submit(rig, Dump(TraceSink { recorded += it })))
        rig.pass(rig.clock.at.value)
        return recorded.filterIsInstance<TraceEvent.DgramOut>().single()
    }

    /**
     * quiche, reached [ENTRY_TAKES] into a send call, reads [clock] and returns [QUICHE_TAKES] later; its send info's
     * time is that read, or [pacedBy] past it when its pacer wants the datagram later.
     */
    private class ColdSend(
        val stub: StubQuicheApi,
        val clock: VirtualClock,
        private val pacedBy: Long = 0L,
    ) : QuicheApi by stub {
        private var stamp = 0L

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            clock.at = Nanos(clock.at.value + ENTRY_TAKES)
            stamp = clock.at.value
            return stub.connSend(conn, buf, bufLen, sendInfo).also { clock.at = Nanos(clock.at.value + QUICHE_TAKES) }
        }

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = stamp + pacedBy
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
        const val ENTRY_TAKES = 700_000L
        const val QUICHE_TAKES = 1_500_000L
        const val PACED_BY = 5_000_000L
    }
}
