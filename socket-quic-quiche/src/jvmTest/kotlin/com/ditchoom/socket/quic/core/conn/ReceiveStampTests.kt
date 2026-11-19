package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A recording's datagram-in time is when quiche read the datagram, not when its socket's receive phase began: quiche
 * measures round trips to that time, and a replay delivers the datagram then. CoreRecordingReplayTests left the
 * recording under load ("our datagram 2 [Handshake] is one more than the capture holds"): stamped at the phase's start,
 * the replay's round trip was shorter than the recorded client's, and its Handshake probe fell due before the server's
 * answer. Datagrams of one phase that quiche reads apart are still taken before our endpoint answers: the replay
 * holds our endpoint's output until the recording's next send.
 */
class ReceiveStampTests {
    /** Returns the [reads] in order, one per receive call, then nothing. */
    private class Reads(
        private vararg val reads: Int,
    ) : DatagramBatchIo by FakeIo() {
        private var next = 0

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            if (next == reads.size) return ReceiveBatchResult.Nothing
            repeat(reads[next++]) {
                into.received(into.take() as ReceiveBuffer, LENGTH, PathKey(4, 443, 0L, 0x7F000001L), ArrivedOn.Unreported)
            }
            return ReceiveBatchResult.Got
        }
    }

    @Test
    fun datagramsOneReadReturnedAreRecordedWhenQuicheReadEach() = assertReadByQuiche(Reads(2))

    @Test
    fun datagramsTwoReadsOfOneReceivePhaseReturnedAreRecordedWhenQuicheReadEach() = assertReadByQuiche(Reads(1, 1))

    private fun assertReadByQuiche(io: DatagramBatchIo) {
        val api = StubQuicheApi()
        val rig = ConnRig(api, io = io)
        api.onConnRecv = { rig.clock.at = Nanos(rig.clock.at.value + QUICHE_TAKES) }
        rig.pass(START)

        val recorded = ArrayList<TraceEvent>()
        rig.loop.port.submit(Dump(rig, TraceSink { recorded += it }))
        rig.pass(rig.clock.at.value)
        rig.pass(rig.clock.at.value)
        val ins = recorded.filterIsInstance<TraceEvent.DgramIn>()
        assertEquals(2, ins.size, "recorded: $recorded")
        assertEquals(
            QUICHE_TAKES.nanoseconds,
            ins[1].at - ins[0].at,
            "the second datagram is stamped when quiche got to it, after its work on the first: $ins",
        )
    }

    private class Dump(
        private val rig: ConnRig,
        private val sink: TraceSink,
    ) : LoopWork() {
        override fun run(port: LoopPort) =
            rig.conn.submit(
                object : ConnWork() {
                    override fun run(inside: Inside) = inside.dump(sink)

                    override fun gone(end: ConnEnd) = Unit
                },
            )

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        const val LENGTH = 100
        const val START = 1_000_000L
        const val QUICHE_TAKES = 300_000L
    }
}
