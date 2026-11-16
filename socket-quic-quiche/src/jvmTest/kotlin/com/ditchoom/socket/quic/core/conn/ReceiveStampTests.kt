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

/**
 * A recording's datagram-in time is when the socket was read, not when quiche got to the datagram: datagrams one read
 * returned are one receive pass, and a replay groups a pass by time. CoreRecordingReplayTests failed on every CI lane
 * because quiche's ~300 µs on the server's first handshake datagram pushed the second one, read with it, past the
 * replay's 250 µs pass window.
 */
class ReceiveStampTests {
    /** Returns [count] datagrams from one receive call, once. */
    private class OneRead(
        private val count: Int,
    ) : DatagramBatchIo by FakeIo() {
        private var read = false

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            if (read) return ReceiveBatchResult.Nothing
            read = true
            repeat(count) { into.received(into.take() as ReceiveBuffer, LENGTH, PathKey(4, 443, 0L, 0x7F000001L), ArrivedOn.Unreported) }
            return ReceiveBatchResult.Got
        }
    }

    @Test
    fun datagramsOneReadReturnedAreRecordedAtTheTimeOfThatRead() {
        val api = StubQuicheApi()
        val rig = ConnRig(api, io = OneRead(2))
        api.onConnRecv = { rig.clock.at = Nanos(rig.clock.at.value + QUICHE_TAKES) }
        rig.pass(START)

        val recorded = ArrayList<TraceEvent>()
        rig.loop.port.submit(Dump(rig, TraceSink { recorded += it }))
        rig.pass(rig.clock.at.value)
        rig.pass(rig.clock.at.value)
        val ins = recorded.filterIsInstance<TraceEvent.DgramIn>()
        assertEquals(2, ins.size, "recorded: $recorded")
        assertEquals(ins[0].at, ins[1].at, "both datagrams of the read are stamped with its time: $ins")
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
