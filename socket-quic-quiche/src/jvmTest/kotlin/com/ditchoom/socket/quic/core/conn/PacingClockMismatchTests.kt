package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PinAnswer
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Whether libquiche stamps send times on the loop's clock is judged once, by the connection's first datagram, which
 * quiche stamps with its own now: off the loop's readings around that write, in either direction, it is another clock.
 * A test run ends the connection then (processing failed with a [PacingClockMismatch]); a running library counts and
 * records it and runs that connection unpaced. Every later send time is pacing, however far ahead.
 */
class PacingClockMismatchTests {
    private val ms = 1_000_000L

    /** How far apart the two clocks count from: hours, as a clock counted from its own start is. */
    private val origin = 2_431_000_000L * ms

    @Test
    fun aLoopClockBehindLibquichesEndsATestRunsConnectionAtItsFirstDatagram() {
        val rig = Rig(start = 0, longArrayOf(origin + 2 * ms, origin + 4 * ms), CoreTestRules)

        rig.pass(at = 0)

        assertEquals(origin + 2 * ms, rig.mismatch().offsetNanos, "the first stamp was this far ahead of the loop's clock")
        assertEquals(1L, rig.mismatches())
    }

    @Test
    fun aLoopClockAheadOfLibquichesEndsATestRunsConnectionAtItsFirstDatagram() {
        val rig = Rig(start = origin, longArrayOf(2 * ms, 4 * ms), CoreTestRules)

        rig.pass(at = origin)

        assertEquals(2 * ms - origin, rig.mismatch().offsetNanos, "the first stamp was this far behind the loop's clock")
        assertEquals(1L, rig.mismatches())
    }

    @Test
    fun aRunningLibrarysConnectionOnAnotherClockRunsUnpacedAndCountsItOnce() {
        val rig = Rig(start = 0, longArrayOf(origin + 2 * ms, origin + 40 * ms, origin + 80 * ms), SlotRules.Default)

        val next = rig.pass(at = 0)

        assertTrue(rig.ended().isEmpty(), "the connection runs on")
        assertEquals(3, rig.sent, "every datagram went at once")
        assertEquals(NextDue.None, next, "nothing is held for a time on another clock")
        assertEquals(1L, rig.mismatches(), "judged once, by the first datagram")
    }

    /** A first stamp on the loop's clock, then BBR2 pacing a collapsed link seconds ahead: held for, not ended. */
    @Test
    fun aSendTimeSecondsAheadAfterAFirstOnTheLoopsClockIsHeldFor() {
        val rig = Rig(start = 0, longArrayOf(0, 2_000 * ms, 2_010 * ms), CoreTestRules)

        val next = rig.pass(at = 0)

        assertTrue(rig.ended().isEmpty(), "the connection runs on: ${rig.ended()}")
        assertEquals(0L, rig.mismatches())
        assertEquals(2, rig.sent, "the first, and the held one already written")
        assertEquals(NextDue.at(2_000 * ms), next, "the loop wakes at the held datagram's time")
    }

    /** Where the loop pins libquiche to its own reading the clocks are one by construction, so nothing is judged. */
    @Test
    fun aPinnedConnectionIsNeverJudged() {
        val rig = Rig(start = 0, longArrayOf(origin + 2 * ms), CoreTestRules, QuicheTime.Pinned(NoPin))

        rig.pass(at = 0)

        assertTrue(rig.ended().isEmpty(), "the connection runs on: ${rig.ended()}")
        assertEquals(0L, rig.mismatches())
    }

    /** A loop on a clock of its own is not paced at all, so a stamp on another clock is not judged either. */
    @Test
    fun aConnectionOnAClockOfItsOwnIsUnpacedAndUnjudged() {
        assertEquals(ClockBase.Libquiche, MonotonicClock.base())
        val rig = Rig(start = 0, longArrayOf(origin + 2 * ms, origin + 40 * ms), CoreTestRules, base = ClockBase.Own)

        val next = rig.pass(at = 0)

        assertTrue(rig.ended().isEmpty())
        assertEquals(2, rig.sent)
        assertEquals(NextDue.None, next)
        assertEquals(0L, rig.mismatches())
    }

    @Test
    fun theFlightRecordingNamesAMismatchAsATypedError() {
        val flight = ConnFlight(FlightSize(8), FlightArena(nativeBytes(64)), PathKey(4, 1, 0L, 1L), TraceCapture.Off, 0L)
        flight.pacingClockMismatch(Nanos(5 * ms), offsetNanos = -123_456L)
        val seen = ArrayList<TraceEvent>()

        flight.dump(TraceSink { seen += it })

        val error = assertIs<TraceEvent.Error>(seen.single())
        assertEquals("com.ditchoom.socket.quic.core.conn.PacingClockMismatch", error.type)
        assertTrue("-123456" in error.message, error.message)
    }

    @Test
    fun theResponseIsTheRulesAndNotAProcessWideSwitch() {
        val failing = Rig(start = 0, longArrayOf(origin + 2 * ms), CoreTestRules)
        val running = Rig(start = 0, longArrayOf(origin + 2 * ms), SlotRules.Default)

        failing.pass(at = 0)
        running.pass(at = 0)

        assertEquals(1, failing.ended().size, "the rig whose rules fail ended")
        assertTrue(running.ended().isEmpty(), "the rig whose rules run unpaced, built beside it, did not")
        assertEquals(1, running.sent)
    }

    /** A pin that pins nothing: libquiche here is a stub, and the connection only needs to be told it is pinned. */
    private object NoPin : QuicheClockPin {
        override fun pinMonotonic(nanos: Long): PinAnswer = PinAnswer.Pinned

        override fun unpin() = Unit
    }

    /** A connection whose loop's clock starts at [start], over a quiche that stamps each datagram with the given time. */
    private class Rig(
        start: Long,
        dueTimes: LongArray,
        rules: SlotRules,
        time: QuicheTime = QuicheTime.Own,
        base: ClockBase = ClockBase.Libquiche,
    ) {
        private val io = FakeIo()
        private val api = Stamping(StubQuicheApi(), dueTimes)
        private val conn = ConnRig(api, rules = rules, io = io, clock = VirtualClock(Nanos(start), base), time = time)
        var sent = 0

        init {
            io.onSend = { pending ->
                sent += pending
                SendBatchResult.AllTaken
            }
            repeat(dueTimes.size) { api.stub.connSendQueue.addLast(1200) }
        }

        fun pass(at: Long): NextDue = conn.pass(at)

        fun ended(): List<ConnEnd> = conn.sink.ended

        fun mismatches(): Long = conn.counts.pacingClockMismatches

        fun mismatch(): PacingClockMismatch {
            val end = assertIs<ConnEnd.ProcessingFailed>(ended().single())
            return assertIs<PacingClockMismatch>(end.thrown.throwable)
        }
    }

    private class Stamping(
        val stub: StubQuicheApi,
        private val dueTimes: LongArray,
    ) : QuicheApi by stub {
        private var written = 0

        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = dueTimes[written++.coerceAtMost(dueTimes.size - 1)]
    }
}
