package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.path.PathNews
import com.ditchoom.socket.quic.core.path.PathNewsCell
import com.ditchoom.socket.quic.core.path.Silence
import com.ditchoom.socket.quic.core.path.SilenceRule
import com.ditchoom.socket.quic.core.path.SilenceThreshold
import com.ditchoom.socket.quic.core.path.ThresholdOf
import com.ditchoom.socket.quic.core.path.ThresholdProblem
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.LoopWork
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A client connection's silence watch, on a [ConnRig] over [StubQuicheApi] with quiche's PTO counters written by hand and
 * virtual time: what it does with particular counter values, which a real pair can only produce by accident. Each read
 * is one firing of quiche's timer; [PathNewsCell] is what the edge reads.
 */
class PathSilenceTests {
    private val threshold = SilenceThreshold.SHIPPED

    /** The verdict lands at the run's floor, not at quiche's next timer: nothing else wakes the connection then. */
    @Test
    fun theVerdictLandsAtTheFloorNotAtTheNextExpiry() {
        val f = Fixture(TICK)
        f.read(0)
        f.read(1)
        val runStarted = f.now
        repeat(EXPIRIES - 1) { f.read(it + 2L) }
        assertEquals(PathNews.Answering, f.news(), "the count was met short of the floor and called silent")

        // quiche will not ask to be woken for half a minute: only the watch's floor deadline can declare.
        f.api.connTimeout = 30.seconds
        val slice = 50.milliseconds.inWholeNanoseconds
        var silentAt = -1L
        while (f.now < runStarted + 2 * threshold.floor.nanos) {
            f.passAt(f.now + slice)
            if (silentAt < 0 && f.news() == PathNews.Silent) silentAt = f.now
        }
        assertTrue(silentAt >= 0, "the run met both halves and nothing declared it")
        assertTrue(
            silentAt in runStarted + threshold.floor.nanos..runStarted + threshold.floor.nanos + slice,
            "declared ${silentAt - runStarted} ns into the run; its floor is ${threshold.floor.nanos}",
        )
        assertTrue("Silent" in f.marks(), "the verdict is in the flight: ${f.marks()}")
    }

    /** The floor deadline is spent once it declares, even when quiche has no active path to read: no spin. */
    @Test
    fun aFloorDeadlineIsDisarmedEvenWhenQuicheHasNoActivePath() {
        val f = Fixture(TICK)
        f.read(0)
        repeat(EXPIRIES) { f.read(it + 1L) }
        f.api.connTimeout = 30.seconds
        f.api.pathStats = listOf(stats(active = false, probes = EXPIRIES.toLong()))
        val next = f.passAt(f.now + 2 * threshold.floor.nanos)
        assertEquals(PathNews.Silent, f.news(), "the floor fell due with no active path and nothing declared")
        assertTrue(next > f.now, "the connection's next wake is ${next - f.now} ns away: a spent deadline would spin")
    }

    /** Past the count and short of the floor is a blip; the same run past the floor is silence. */
    @Test
    fun aRunPastTheExpiryThresholdIsNotSilentUntilTheTimeFloorIsMetToo() {
        val f = Fixture(TICK)
        f.read(0)
        var expiries = 0L
        f.read(++expiries)
        val runStarted = f.now
        repeat(EXPIRIES) { f.read(++expiries) }
        assertEquals(PathNews.Answering, f.news(), "$expiries expiries inside ${f.now - runStarted} ns are a blip")
        while (f.now < runStarted + threshold.floor.nanos + 2 * TICK) f.read(++expiries)
        assertEquals(PathNews.Silent, f.news(), "$expiries expiries over ${f.now - runStarted} ns are silence")
    }

    /** Elapsed time alone is not evidence: past the floor and short of the count still answers. */
    @Test
    fun theTimeFloorAloneIsNotEnoughWithoutTheExpiryCount() {
        val f = Fixture(SLOW_TICK)
        f.read(0)
        f.read(1)
        val runStarted = f.now
        var expiries = 1L
        while (expiries < EXPIRIES - 1) f.read(++expiries)
        assertTrue(f.now - runStarted >= threshold.floor.nanos, "the run is past the floor")
        assertTrue(f.now - runStarted < threshold.patience.nanos, "the run is short of the patience")
        assertEquals(PathNews.Answering, f.news(), "$expiries expiries past the floor were called silence")
        f.read(++expiries)
        assertEquals(PathNews.Silent, f.news(), "both halves met and the path still answers")
    }

    /** A run the count will not finish in time is declared at the patience. */
    @Test
    fun aRunPastThePatienceCeilingIsSilentWithoutTheCount() {
        val f = Fixture(threshold.patience.nanos / 2 + 100.milliseconds.inWholeNanoseconds)
        f.read(0)
        f.read(1)
        val runStarted = f.now
        f.read(2)
        assertEquals(PathNews.Answering, f.news(), "declared before either bound")
        f.read(3)
        assertTrue(3 < EXPIRIES && f.now - runStarted >= threshold.patience.nanos)
        assertEquals(PathNews.Silent, f.news(), "three expiries past the patience still answer")
    }

    /** A run that stopped growing is never declared on elapsed time alone. */
    @Test
    fun aRunThatStopsGrowingIsNeverDeclaredOnElapsedTimeAlone() {
        val f = Fixture(SLOW_TICK)
        f.read(0)
        f.read(1)
        val runStarted = f.now
        repeat((threshold.patience.nanos / SLOW_TICK).toInt() + 2) { f.read(1) }
        assertTrue(f.now - runStarted > threshold.patience.nanos)
        assertEquals(PathNews.Answering, f.news(), "a run stuck at one expiry was declared after ${f.now - runStarted} ns")
    }

    /** A threshold that cannot mean anything is not built, and says which half is wrong. */
    @Test
    fun aCeilingAtOrUnderTheFloorIsNotConstructible() {
        fun problem(of: ThresholdOf): ThresholdProblem = assertIs<ThresholdOf.Invalid>(of).problem
        assertEquals(ThresholdProblem.PatienceUnderFloor, problem(SilenceThreshold.of(4, Span(SECOND * 2), Span(SECOND * 2))))
        assertEquals(ThresholdProblem.PatienceUnderFloor, problem(SilenceThreshold.of(4, Span(SECOND * 2), Span(SECOND))))
        assertEquals(ThresholdProblem.NotEnoughExpiries, problem(SilenceThreshold.of(0, Span(SECOND * 2), Span(SECOND * 4))))
        assertEquals(ThresholdProblem.NoTimeFloor, problem(SilenceThreshold.of(4, Span(0L), Span(SECOND * 4))))
        assertIs<ThresholdOf.Valid>(SilenceThreshold.of(4, Span(SECOND * 2), Span(SECOND * 4)))
    }

    /** Unanswered sends meet the threshold with no expiry, and only at the patience. */
    @Test
    fun unansweredSendsMeetTheCeilingOnlyAtThePatience() {
        assertTrue(!threshold.isMetByUnansweredSends(Span(threshold.patience.nanos - 1_000_000L)))
        assertTrue(threshold.isMetByUnansweredSends(threshold.patience))
    }

    /** A read of another path is a baseline, never a delta. */
    @Test
    fun aRunDoesNotCarryAcrossAChangeOfActivePath() {
        val f = Fixture(TICK)
        f.read(0)
        var expiries = 0L
        while (f.news() != PathNews.Silent && expiries < 2 * threshold.floor.nanos / TICK) f.read(++expiries)
        assertEquals(PathNews.Silent, f.news(), "the run on path 0 never went silent")
        f.read(expiries + 100, active = 1, paths = 2)
        assertEquals(PathNews.Answering, f.news(), "path 0's run was carried onto path 1")
    }

    /** A counter that goes back means another path behind the index: a baseline, and a fresh run can still declare. */
    @Test
    fun aCounterThatGoesBackwardsRebaselinesRatherThanCarryingTheOldPathsRun() {
        val f = Fixture(SLOW_TICK)
        f.read(0)
        f.read(500)
        f.read(3)
        f.read(4)
        assertEquals(PathNews.Answering, f.news(), "the old path's run was carried across a counter that went back")
        var expiries = 4L
        while (f.news() == PathNews.Answering && expiries < 4 + FRESH_RUN_LIMIT) f.read(++expiries)
        assertEquals(PathNews.Silent, f.news(), "after a regression a fresh run never declared: the watch is disarmed")
    }

    /** An arrival ends a declared run: the path answers again, and the flight says so. */
    @Test
    fun anArrivalAnswersADeclaredRun() {
        val f = Fixture(SLOW_TICK)
        f.read(0)
        repeat(EXPIRIES) { f.read(it + 1L) }
        assertEquals(PathNews.Silent, f.news())
        f.io.pending = 1
        f.rig.endpoint.readable()
        f.passAt(f.now + 1_000_000L)
        assertEquals(PathNews.Answering, f.news(), "a datagram arrived and the path is still silent")
        assertEquals(listOf("Silent", "Answering"), f.marks().filter { it == "Silent" || it == "Answering" })
    }

    /** Ack-eliciting data unanswered for the patience is silence with no PTO expiry at all: here a keep-alive's PING. */
    @Test
    fun aPingUnansweredForThePatienceIsSilentWithNoExpiry() {
        val keepAlive = SECOND
        val rules =
            SlotRules.forWaits(
                WaitResolution(1_000_000L),
                CoreTestRules.handshake,
                CoreTestRules.close,
                keepAlive = KeepAliveRule.Every(keepAlive),
                mismatch = MismatchResponse.Fail,
            )
        val f = Fixture(TICK, rules = rules)
        val established = f.now
        f.api.connTimeout = 30.seconds
        var silentAt = -1L
        while (f.now < established + keepAlive + 2 * threshold.patience.nanos) {
            f.passAt(f.now + TICK)
            if (silentAt < 0 && f.news() == PathNews.Silent) silentAt = f.now
        }
        assertTrue(f.api.ackElicitingCount >= 1, "the keep-alive pinged")
        val owedFrom = established + keepAlive
        assertTrue(
            silentAt in owedFrom + threshold.patience.nanos..owedFrom + threshold.patience.nanos + TICK,
            "declared ${silentAt - owedFrom} ns after the PING; the patience is ${threshold.patience.nanos}",
        )
    }

    /** A connection whose silence is not judged never reads the path's counters and never declares. */
    @Test
    fun aWatchThatIsOffReadsNothingAndDeclaresNothing() {
        val f = Fixture(TICK, watch = Silence.Off)
        f.read(0)
        repeat(4 * EXPIRIES) { f.read(it + 1L) }
        while (f.now < START + 2 * threshold.patience.nanos) f.read(4L * EXPIRIES)
        assertEquals(PathNews.Answering, f.news())
        assertEquals(0, f.reads, "the counters were read for a connection nothing acts on")
    }

    /** A rig whose quiche timer fires once per [read], [tick] apart, and whose socket delivers [Arrivals.pending]. */
    private class Fixture(
        val tick: Long,
        watch: Silence = Silence.On,
        rules: SlotRules = CoreTestRules,
    ) {
        val api = StubQuicheApi()
        var reads = 0
        private val counting =
            object : QuicheApi by api {
                override fun connActivePathProbes(conn: QuicheConn): Long {
                    reads++
                    return api.connActivePathProbes(conn)
                }
            }
        val io = Arrivals()
        private val cell = PathNewsCell()
        val rig = ConnRig(counting, rules = rules, io = io, silence = SilenceRule(watch, Span(30 * SECOND), cell))
        var now = START

        init {
            api.pathStats = listOf(stats(active = true, probes = 0L))
            // quiche's timer is armed from the start: the first read is its first firing.
            api.connTimeout = tick.nanoseconds
            rig.pass(now)
        }

        fun news(): PathNews = cell.read()

        /** quiche's counters now read [probes] on the path at [active] of [paths], and its timer fires [tick] later. */
        fun read(
            probes: Long,
            active: Int = 0,
            paths: Int = 1,
        ) {
            api.pathStats = List(paths) { stats(active = it == active, probes = if (it == active) probes else 0L) }
            api.connTimeout = Duration.ZERO
            passAt(now + tick)
        }

        /** One loop pass at [at]; answers when the connection next wants the loop. */
        fun passAt(at: Long): Long {
            now = at
            return rig.pass(at).fold({ Long.MAX_VALUE }, { it })
        }

        /** The path marks in the connection's flight, in order. */
        fun marks(): List<String> {
            val recorded = ArrayList<TraceEvent>()
            rig.loop.port.submit(Dump(rig, TraceSink { recorded += it }))
            rig.pass(now)
            rig.pass(now)
            return recorded.filterIsInstance<TraceEvent.PathState>().map { it.phase }
        }
    }

    /** A socket that delivers [pending] datagrams from the rig's peer at its next read. */
    private class Arrivals : DatagramBatchIo by FakeIo() {
        var pending = 0

        override fun receive(into: ReceiveBatch): ReceiveBatchResult {
            if (pending == 0) return ReceiveBatchResult.Nothing
            while (pending > 0) {
                pending--
                into.received(into.take() as ReceiveBuffer, 100, PathKey(4, 443, 0L, 0x7F000001L), ArrivedOn.Unreported)
            }
            return ReceiveBatchResult.Got
        }
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
        const val SECOND = 1_000_000_000L
        const val START = SECOND
        const val EXPIRIES = 4
        const val FRESH_RUN_LIMIT = 20
        val TICK = 100.milliseconds.inWholeNanoseconds
        const val SLOW_TICK = SECOND

        /** Only the two fields the watch reads are set: the rest are zero on purpose. */
        fun stats(
            active: Boolean,
            probes: Long,
        ) = QuicPathStats(
            validationState = 0,
            active = active,
            recv = 0,
            sent = 0,
            lost = 0,
            retrans = 0,
            totalPtoCount = probes,
            rtt = Duration.ZERO,
            minRtt = Duration.ZERO,
            maxRtt = Duration.ZERO,
            rttvar = Duration.ZERO,
            cwnd = 0,
            sentBytes = 0,
            recvBytes = 0,
            lostBytes = 0,
            streamRetransBytes = 0,
            pmtu = 0,
            deliveryRate = 0,
        )
    }
}
