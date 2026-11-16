package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A recording's datagram-in time is when its socket's receive phase of the pass began, not when quiche got to the
 * datagram: everything one receive phase reads reaches quiche before the connection sends, so it is one receive pass,
 * and a replay groups a pass by time. CoreRecordingReplayTests failed on every CI lane because quiche's ~300 µs on the
 * server's first handshake datagram pushed the second one past the replay's 250 µs pass window: read with it on
 * macOS and Linux, and by the next read of the same phase on Windows.
 */
class ReceiveStampTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

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
    fun datagramsOneReadReturnedAreRecordedAtOneTime() = assertOnePass(Reads(2))

    @Test
    fun datagramsTwoReadsOfOneReceivePhaseReturnedAreRecordedAtOneTime() = assertOnePass(Reads(1, 1))

    private fun assertOnePass(io: DatagramBatchIo) {
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
        assertEquals(ins[0].at, ins[1].at, "both datagrams of the receive phase are stamped with one time: $ins")
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
