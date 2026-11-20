package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.CongestionControl
import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import com.ditchoom.socket.quic.migrationSimOptions
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client configured for 1350-byte datagrams on a link that carries [LINK] bytes at most: its data never crosses, only
 * its small packets do. A real libquiche pair runs on one virtual clock, so a send time is pacing and never a second
 * clock's reading; the core's [Pacer] judges each one as the loop would.
 */
class PacingCollapseTests {
    private val env = jvmMigrationSimEnv()

    /** What a run's client paced: how far ahead the furthest datagram was due, when it was written and when it was due. */
    private class Paced(
        val furthestNanos: Long,
        val writtenNanos: Long,
        val dueNanos: Long,
        val trace: String,
    )

    /**
     * BBR2's delivery rate collapses on the link and it paces a datagram more than a second ahead, on the loop's own
     * clock. Before the clock check moved to the first datagram, the core took any time over a second ahead for another
     * clock and ended the connection (PacingClockMismatch); now the connection holds for it.
     */
    @Test
    fun aConnectionWhoseLinkCollapsedIsHeldForItsPacingTimeNotEnded() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                val paced = drive(CongestionControl.Bbr2, loopLagNanos = 0L)
                assertTrue(paced.furthestNanos > FORMER_LIMIT, "the link collapsed: ${paced.furthestNanos.nanoseconds}\n${paced.trace}")
                val core = CoreRig(paced.writtenNanos, paced.dueNanos)
                assertTrue(core.ended().isEmpty(), "the core ended the connection: ${core.ended().map(::describe)}")
                assertEquals(0L, core.mismatches(), "no clock mismatch was counted")
                assertEquals(NextDue.at(paced.dueNanos), core.next, "the core holds until the paced time")
            }
        }

    /** BBR2 paces past a second with the pin (both clocks one instant) and without it (the loop read a little earlier). */
    @Test
    fun bbr2PacesPastASecondOnOneClockWithAndWithoutThePin() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                for (lag in longArrayOf(0L, PIN_OFF_LAG)) {
                    val paced = drive(CongestionControl.Bbr2, loopLagNanos = lag)
                    println("[pacing collapse] BBR2 lag=${lag.nanoseconds} furthest=${paced.furthestNanos.nanoseconds}")
                    assertTrue(
                        paced.furthestNanos > FORMER_LIMIT,
                        "BBR2 paced at most ${paced.furthestNanos.nanoseconds} ahead with the loop ${lag.nanoseconds} behind",
                    )
                }
            }
        }

    /** quiche 0.30's CUBIC (its legacy recovery) stamps every datagram with its own now: it is never paced ahead. */
    @Test
    fun cubicIsNeverPacedAheadOnTheSameLink() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                val paced = drive(CongestionControl.Cubic(), loopLagNanos = 0L)
                println("[pacing collapse] CUBIC furthest=${paced.furthestNanos.nanoseconds}")
                assertTrue(paced.furthestNanos <= ALLOWANCE, "CUBIC paced ${paced.furthestNanos.nanoseconds} ahead")
            }
        }

    /**
     * The client writes a stream over the link, [ONE_WAY] each way, and issues a spare connection ID every [CID_EVERY]: a
     * small ack-eliciting packet, as a core client sends. It is paced as the core paces: a datagram due later than the
     * allowance holds sending until then. Each send time is judged at the pair's clock less [loopLagNanos], the loop's
     * reading taken that much before libquiche read its own; zero is the pin.
     */
    private suspend fun drive(
        cc: CongestionControl,
        loopLagNanos: Long,
    ): Paced {
        val pair = QuichePairByHand.open(env, migrationSimOptions().copy(congestionControl = cc))
        // Both ends read the pair's virtual clock, pinned: one clock by construction.
        val pacer = Pacer(CoreTestRules.pacing as PacingRule.ByQuiche, ClockVerdict.Pinned)
        val trace = ArrayDeque<String>()
        val inFlight = ArrayList<Crossing>()
        val oneWay = ONE_WAY.inWholeNanoseconds
        var furthest = 0L
        var writtenAt = 0L
        var dueAt = 0L
        try {
            val initial = pair.sendOne(pair.client) as QuichePairByHand.Written.One
            val stamps = Stamps.of(initial.dueNanos, pair.nowNanos(pair.client))
            pair.elapse(ONE_WAY)
            pair.deliver(listOf(initial.datagram), pair.server)
            pair.handshakeUpToTheClientsFinished(ONE_WAY)
            pair.exchange(ONE_WAY) { true }
            var nextCid = pair.nowNanos(pair.client) + CID_EVERY.inWholeNanoseconds
            val stream = QuicStreamId(0)
            val chunk = "x".repeat(CHUNK)
            var turns = 0
            while (pair.elapsed < RUN_FOR) {
                check(++turns < MAX_TURNS) { "the pair made no progress past ${pair.elapsed}" }
                while (pair.streamSend(pair.client, stream, chunk, fin = false) > 0) Unit
                pair.readAll(pair.server, stream)
                val now = pair.nowNanos(pair.client)
                val loopNow = Nanos(now - loopLagNanos)
                if (now >= nextCid) {
                    pair.issueSpareCids(pair.client, atMost = 1)
                    nextCid = now + CID_EVERY.inWholeNanoseconds
                }
                if (pacer.state(loopNow) == PaceState.Free) {
                    while (true) {
                        val w = pair.sendOne(pair.client) as? QuichePairByHand.Written.One ?: break
                        val size = w.datagram.bytes.size
                        val dueNanos = now + stamps.ahead(w.dueNanos, now)
                        val due = pacer.sent(dueNanos, loopNow)
                        if (dueNanos - now > furthest) {
                            furthest = dueNanos - now
                            writtenAt = loopNow.value
                            dueAt = dueNanos
                        }
                        note(trace, "t=${now.nanoseconds} client ${size}B due +${(dueNanos - now).nanoseconds} $due")
                        if (size <= LINK) inFlight += Crossing(now + oneWay, w.datagram, toServer = true)
                        when (due) {
                            is Due.Now -> Unit
                            is Due.Hold -> break
                        }
                    }
                }
                for (d in pair.sends(pair.server)) inFlight += Crossing(now + oneWay, d, toServer = false)
                val next =
                    listOfNotNull(
                        inFlight.minOfOrNull { it.arrives },
                        // The loop reads its clock loopLagNanos behind the pair's: its hold ends that much later here.
                        holdEnd(pacer)?.plus(loopLagNanos),
                        pair.nextTimer(pair.client)?.let { now + it.inWholeNanoseconds },
                        pair.nextTimer(pair.server)?.let { now + it.inWholeNanoseconds },
                        nextCid,
                    ).min()
                // A timer due now fires too: elapse runs every quiche timer due by the end of its span.
                pair.elapse(maxOf(0L, next - now).nanoseconds)
                val at = pair.nowNanos(pair.client)
                val arrived = inFlight.filter { it.arrives <= at }
                inFlight.removeAll(arrived)
                for (c in arrived) pair.deliver(listOf(c.datagram), if (c.toServer) pair.server else pair.client)
            }
            return Paced(furthest, writtenAt, dueAt, trace.joinToString("\n"))
        } finally {
            pair.close()
        }
    }

    /**
     * Where libquiche's send times count from. Off Apple and Windows an instant is a timespec: the pair's virtual time
     * from a fixed anchor, read off the client's first Initial, which is due the instant it is written. On Apple the
     * patched `std_time_to_c` writes the host's uptime clock plus how far the time is past quiche's own now, and on Windows
     * the performance counter likewise, so it is read against `System.nanoTime`, which reads that clock.
     */
    private sealed interface Stamps {
        fun ahead(
            dueNanos: Long,
            nowNanos: Long,
        ): Long

        class Anchored(
            private val anchor: Long,
        ) : Stamps {
            override fun ahead(
                dueNanos: Long,
                nowNanos: Long,
            ) = dueNanos - anchor - nowNanos
        }

        data object HostUptime : Stamps {
            override fun ahead(
                dueNanos: Long,
                nowNanos: Long,
            ) = dueNanos - System.nanoTime()
        }

        companion object {
            fun of(
                initialDue: Long,
                nowNanos: Long,
            ): Stamps {
                val os = System.getProperty("os.name")
                return when {
                    os.startsWith("Mac") || os.startsWith("Windows") -> HostUptime
                    else -> Anchored(initialDue - nowNanos)
                }
            }
        }
    }

    private fun holdEnd(pacer: Pacer): Long? {
        val sink = WakeSink()
        pacer.offerWake(sink)
        return if (sink.state() == WakeState.Asked) sink.earliest().value else null
    }

    /**
     * A core connection on a clock libquiche stamps on, given a first datagram stamped when written at [writtenNanos], as
     * quiche stamps its first, then one stamped [dueNanos]: one pass at [writtenNanos].
     */
    private class CoreRig(
        writtenNanos: Long,
        dueNanos: Long,
    ) {
        private val api = StampedApi(StubQuicheApi(), longArrayOf(writtenNanos, dueNanos))
        private val io = FakeIo()
        private val rig = ConnRig(api, io = io, clock = VirtualClock(Nanos(writtenNanos), ClockBase.Libquiche))
        val next: NextDue

        init {
            io.onSend = { SendBatchResult.AllTaken }
            repeat(2) { api.stub.connSendQueue.addLast(FULL) }
            next = rig.pass(writtenNanos)
        }

        fun ended(): List<ConnEnd> = rig.sink.ended

        fun mismatches(): Long = rig.counts.pacingClockMismatches
    }

    private fun describe(end: ConnEnd): String =
        when (end) {
            is ConnEnd.ProcessingFailed -> "ProcessingFailed(${end.thrown.throwable})"
            else -> end.toString()
        }

    private fun note(
        trace: ArrayDeque<String>,
        line: String,
    ) {
        trace.addLast(line)
        if (trace.size > TRACE_LINES) trace.removeFirst()
    }

    private class Crossing(
        val arrives: Long,
        val datagram: QuichePairByHand.Datagram,
        val toServer: Boolean,
    )

    /** The stub, with libquiche's send time for each datagram, in the order it writes them. */
    private class StampedApi(
        val stub: StubQuicheApi,
        private val dueTimes: LongArray,
    ) : QuicheApi by stub {
        private var written = 0

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = dueTimes[written++.coerceAtMost(dueTimes.size - 1)]
    }

    private companion object {
        const val LINK = 1280
        const val FULL = 1350
        const val CHUNK = 16 * 1024
        const val TRACE_LINES = 16
        const val MAX_TURNS = 1_000_000
        const val PIN_OFF_LAG = 5_000_000L

        /** The time limit the core once took any send time past for another clock's reading. */
        const val FORMER_LIMIT = 1_000_000_000L

        /** The pacer's allowance: a time within it is sent now. */
        val ALLOWANCE = (CoreTestRules.pacing as PacingRule.ByQuiche).allowanceNanos

        /** A 200 ms round trip: BBR2 then paces about 1.6 s ahead. Its hold grows with the round trip, ~4 per second of delay. */
        val ONE_WAY = 100.milliseconds
        val RUN_FOR: Duration = 60.seconds
        val CID_EVERY = 100.milliseconds
    }
}
