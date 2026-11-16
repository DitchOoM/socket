package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * A recording replays as a network schedule: each outgoing datagram is keyed by its ordinal in its direction, never by
 * its bytes (a fresh connection's ciphertext differs), and takes the fate the recording gave the datagram at that
 * ordinal. Two fresh connections replayed through the same schedule record the same flight.
 */
class FlightReplayTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private enum class Fate { Delivered, Dropped }

    private class Outcome(
        val flight: List<String>,
        val fates: List<Fate>,
    )

    /** One run: the connection writes on every pass; the network applies [schedule] to the n-th datagram out. */
    private fun run(schedule: List<Fate>): Outcome {
        val api = StubQuicheApi().apply { connTimeout = 5.milliseconds }
        val io = FakeIo()
        val fates = ArrayList<Fate>()
        io.onSend = { _ ->
            val fate = schedule.getOrElse(fates.size) { Fate.Delivered }
            fates += fate
            when (fate) {
                Fate.Delivered -> SendBatchResult.AllTaken
                Fate.Dropped -> SendBatchResult.Partial(0, UdpError.NetworkUnreachable)
            }
        }
        val rig = ConnRig(api, io = io, maxDatagrams = 1)
        repeat(schedule.size) { pass ->
            api.connSendQueue.addLast(100 + pass)
            rig.conn.submit(Nudge)
            rig.pass(pass * 1_000_000L)
        }
        val flight = ArrayList<String>()
        rig.loop.port.submit(Dump(rig, TraceSink { flight += it.toString() }))
        rig.pass(schedule.size * 1_000_000L)
        return Outcome(flight, fates)
    }

    @Test
    fun aScheduleKeyedByOrdinalReplaysTheSameFlightTwiceThroughFreshConnections() {
        val recorded = run(listOf(Fate.Delivered, Fate.Dropped, Fate.Delivered, Fate.Dropped, Fate.Delivered))
        // The schedule is what the recording says happened to each outgoing datagram, by ordinal.
        val schedule = recorded.fates

        val first = run(schedule)
        val second = run(schedule)

        assertEquals(schedule, first.fates)
        assertEquals(first.fates, second.fates)
        assertEquals(first.flight, second.flight, "the same schedule gives the same flight")
        assertEquals(schedule.size, first.flight.count { TraceEvent.parse(it) is TraceEvent.DgramOut })
    }

    private object Nudge : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: com.ditchoom.socket.quic.core.life.ConnEnd) = Unit
    }

    private class Dump(
        private val rig: ConnRig,
        private val sink: TraceSink,
    ) : com.ditchoom.socket.quic.core.sched.LoopWork() {
        override fun run(port: com.ditchoom.socket.quic.core.sched.LoopPort) = rig.conn.submit(DumpInside(sink))

        override fun gone(end: com.ditchoom.socket.quic.core.life.ConnEnd) = Unit
    }

    private class DumpInside(
        private val sink: TraceSink,
    ) : ConnWork() {
        override fun run(inside: Inside) = inside.dump(sink)

        override fun gone(end: com.ditchoom.socket.quic.core.life.ConnEnd) = Unit
    }
}
