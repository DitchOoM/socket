package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.CongestionControl
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import com.ditchoom.socket.quic.migrationSimOptions
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
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

    /** How the pacer judged a run's client datagrams: how far ahead the furthest was due, and the first mismatch. */
    private sealed interface Verdict {
        val furthestNanos: Long

        class Paced(
            override val furthestNanos: Long,
        ) : Verdict

        class Mismatch(
            override val furthestNanos: Long,
            val nowNanos: Long,
            val dueNanos: Long,
            val trace: String,
        ) : Verdict

        /** This platform's libquiche stamps no send time (Windows): nothing to judge. */
        data object Unstamped : Verdict {
            override val furthestNanos = 0L
        }
    }

    /**
     * Red: BBR2's delivery rate collapses on the link and it paces the next datagram more than a second ahead, on the
     * loop's own clock. The core takes that for a clock mismatch and ends the connection (PacingClockMismatch) where it
     * should hold it. Remove the ignore with the fix.
     */
    @Ignore // Pacing bug: a BBR2 send time over a second ahead ends the connection as a PacingClockMismatch.
    @Test
    fun aConnectionWhoseLinkCollapsedIsHeldForItsPacingTimeNotEnded() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                when (val verdict = drive(CongestionControl.Bbr2, loopLagNanos = 0L)) {
                    is Verdict.Paced, Verdict.Unstamped -> Unit
                    is Verdict.Mismatch ->
                        fail(
                            "libquiche paced a datagram ${(verdict.dueNanos - verdict.nowNanos).nanoseconds} ahead on the loop's " +
                                "clock; the pacer judged it a ClockMismatch and the core ended the connection: " +
                                "${coreEndFor(verdict.nowNanos, verdict.dueNanos)}\n${verdict.trace}",
                        )
                }
            }
        }

    /** BBR2 paces past the limit with the pin (both clocks one instant) and without it (the loop read a little earlier). */
    @Test
    fun bbr2PacesPastTheLimitOnOneClockWithAndWithoutThePin() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                for (lag in longArrayOf(0L, PIN_OFF_LAG)) {
                    val verdict = drive(CongestionControl.Bbr2, loopLagNanos = lag)
                    println("[pacing collapse] BBR2 lag=${lag.nanoseconds} furthest=${verdict.furthestNanos.nanoseconds} $verdict")
                    if (verdict is Verdict.Unstamped) return@skipOnMissingNativeLib
                    assertTrue(
                        verdict.furthestNanos > LIMIT,
                        "BBR2 paced at most ${verdict.furthestNanos.nanoseconds} ahead with the loop ${lag.nanoseconds} behind",
                    )
                }
            }
        }

    /**
     * CUBIC paces a datagram at most about half a smoothed round trip apart, so it passes the limit only once the round
     * trip nears two seconds; on this link it is held by its congestion window, not its pacer.
     */
    @Test
    fun cubicStaysWithinTheLimitOnTheSameLink() =
        runTest {
            skipOnMissingNativeLib(PacingCollapseTests::class) {
                val verdict = drive(CongestionControl.Cubic(), loopLagNanos = 0L)
                println("[pacing collapse] CUBIC furthest=${verdict.furthestNanos.nanoseconds} $verdict")
                assertTrue(verdict.furthestNanos <= LIMIT, "CUBIC paced ${verdict.furthestNanos.nanoseconds} ahead")
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
    ): Verdict {
        val pair = QuichePairByHand.open(env, migrationSimOptions().copy(congestionControl = cc))
        val pacer = Pacer(CoreTestRules.pacing as PacingRule.ByQuiche)
        val trace = ArrayDeque<String>()
        val inFlight = ArrayList<Crossing>()
        val oneWay = ONE_WAY.inWholeNanoseconds
        var furthest = 0L
        try {
            val initial = pair.sendOne(pair.client) as QuichePairByHand.Written.One
            val stamps = Stamps.of(initial.dueNanos, pair.nowNanos(pair.client))
            if (stamps is Stamps.None) return Verdict.Unstamped
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
                        furthest = maxOf(furthest, dueNanos - now)
                        note(trace, "t=${now.nanoseconds} client ${size}B due +${(dueNanos - now).nanoseconds} $due")
                        if (size <= LINK) inFlight += Crossing(now + oneWay, w.datagram, toServer = true)
                        when (due) {
                            is Due.Now -> Unit
                            is Due.Hold -> break
                            is Due.ClockMismatch -> return Verdict.Mismatch(furthest, loopNow.value, dueNanos, trace.joinToString("\n"))
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
            return Verdict.Paced(furthest)
        } finally {
            pair.close()
        }
    }

    /**
     * Where libquiche's send times count from. Off Apple and Windows an instant is a timespec: the pair's virtual time
     * from a fixed anchor, read off the client's first Initial, which is due the instant it is written. On Apple the
     * patched `std_time_to_c` writes the host's uptime clock plus how far the time is past quiche's own now, so it is read
     * against `System.nanoTime`, which reads that clock. Windows stamps nothing.
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

        data object None : Stamps {
            override fun ahead(
                dueNanos: Long,
                nowNanos: Long,
            ) = 0L
        }

        companion object {
            fun of(
                initialDue: Long,
                nowNanos: Long,
            ): Stamps {
                val os = System.getProperty("os.name")
                return when {
                    os.startsWith("Mac") -> HostUptime
                    os.startsWith("Windows") -> None
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

    /** How the core ends, given one datagram libquiche stamped [dueNanos] at [nowNanos]: "open" while it is not ended. */
    private fun coreEndFor(
        nowNanos: Long,
        dueNanos: Long,
    ): String {
        val api = StampedApi(StubQuicheApi(), dueNanos)
        val io = FakeIo()
        io.onSend = { SendBatchResult.AllTaken }
        val rig = ConnRig(api, io = io)
        api.stub.connSendQueue.addLast(FULL)
        rig.pass(nowNanos)
        val end = rig.sink.ended.firstOrNull() ?: return "open"
        if (end !is ConnEnd.ProcessingFailed) return end.toString()
        val thrown = end.thrown.throwable
        if (thrown !is PacingClockMismatch) return "ProcessingFailed($thrown)"
        return "ProcessingFailed(PacingClockMismatch(aheadNanos=${thrown.aheadNanos}))"
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

    /** The stub, with libquiche's send time for its one datagram. */
    private class StampedApi(
        val stub: StubQuicheApi,
        private val dueNanos: Long,
    ) : QuicheApi by stub {
        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = dueNanos
    }

    private companion object {
        const val LINK = 1280
        const val FULL = 1350
        const val CHUNK = 16 * 1024
        const val TRACE_LINES = 16
        const val MAX_TURNS = 1_000_000
        const val PIN_OFF_LAG = 5_000_000L

        /** The pacer's limit: [SlotRules.forWaits]'s one second. */
        val LIMIT = (CoreTestRules.pacing as PacingRule.ByQuiche).limitNanos

        /** A 200 ms round trip: BBR2 then paces about 1.6 s ahead. Its hold grows with the round trip, ~4 per second of delay. */
        val ONE_WAY = 100.milliseconds
        val RUN_FOR: Duration = 60.seconds
        val CID_EVERY = 100.milliseconds
    }
}
