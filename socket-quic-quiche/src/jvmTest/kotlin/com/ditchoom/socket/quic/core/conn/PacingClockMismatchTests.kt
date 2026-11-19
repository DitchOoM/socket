package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.CoreTestRules
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A due time from a clock the loop does not read is never taken for pacing: the pacer names it, the connection counts it
 * and records it, and a test run ends the connection with it (processing failed with a [PacingClockMismatch]), so no test passes on a loop that pacing did not hold.
 */
class PacingClockMismatchTests {
    private val ms = 1_000_000L

    /** What the loop's clock counted from before the raw monotonic clock: its origin, hours before the stamps libquiche reads. */
    private val origin = 2_431_000_000L * ms

    @Test
    fun aLoopClockCountedFromAnOriginReportsEveryDatagramAsAMismatch() {
        val rig = Rig(longArrayOf(origin + 2 * ms, origin + 4 * ms, origin + 6 * ms), SlotRules.Default)

        rig.conn.pass(at = 0)

        assertEquals(3L, rig.conn.counts.pacingClockMismatches, "each of the three datagrams was counted")
        assertEquals(3, rig.sent, "and each was sent at once")
    }

    @Test
    fun aMismatchInATestRunEndsTheConnectionWithItsTypedCause() {
        val rig = Rig(longArrayOf(origin + 2 * ms, origin + 4 * ms), CoreTestRules)

        rig.conn.pass(at = 0)

        val end =
            assertIs<ConnEnd.ProcessingFailed>(
                rig.conn.sink.ended
                    .single(),
            )
        val cause = assertIs<PacingClockMismatch>(end.thrown.throwable)
        assertEquals(origin + 2 * ms, cause.aheadNanos, "it names how far ahead the due time was")
        assertEquals(1L, rig.conn.counts.pacingClockMismatches)
    }

    @Test
    fun aRealClockReadingNeverIsAMismatch() {
        val rig = Rig(longArrayOf(2 * ms, 4 * ms, 6 * ms), CoreTestRules)

        rig.conn.pass(at = 0)

        assertEquals(0L, rig.conn.counts.pacingClockMismatches)
        assertTrue(
            rig.conn.sink.ended
                .isEmpty(),
            "the connection is still running",
        )
    }

    @Test
    fun theFlightRecordingNamesAMismatchAsATypedError() {
        val flight = ConnFlight(FlightSize(8), FlightArena(nativeBytes(64)), PathKey(4, 1, 0L, 1L), TraceCapture.Off, 0L)
        flight.pacingClockMismatch(Nanos(5 * ms), aheadNanos = 123_456L)
        val seen = ArrayList<TraceEvent>()

        flight.dump(TraceSink { seen += it })

        val error = assertIs<TraceEvent.Error>(seen.single())
        assertEquals("com.ditchoom.socket.quic.core.conn.PacingClockMismatch", error.type)
        assertTrue("123456" in error.message, error.message)
    }

    @Test
    fun onlyTheClockLibquicheReadsIsPacedAndEveryOtherClockIsOfItsOwn() {
        assertEquals(ClockBase.Libquiche, MonotonicClock.base())
        assertEquals(ClockBase.Own, LoopClock { Nanos(0) }.base())
        val waits = WaitResolution(ms)

        fun rulesOn(clock: ClockBase) = SlotRules.forWaits(waits, SlotRules.Default.handshake, SlotRules.Default.close, clock = clock)
        assertIs<PacingRule.ByQuiche>(rulesOn(ClockBase.Libquiche).pacing)
        assertEquals(PacingRule.Unpaced, rulesOn(ClockBase.Own).pacing)
    }

    @Test
    fun theResponseIsTheRulesAndNotAProcessWideSwitch() {
        val failing = Rig(longArrayOf(origin + 2 * ms), CoreTestRules)
        val sending = Rig(longArrayOf(origin + 2 * ms), SlotRules.Default)

        failing.conn.pass(at = 0)
        sending.conn.pass(at = 0)

        assertEquals(1, failing.conn.sink.ended.size, "the rig whose rules fail ended")
        assertTrue(
            sending.conn.sink.ended
                .isEmpty(),
            "the rig whose rules send at once, built beside it, did not",
        )
        assertEquals(1, sending.sent)
    }

    /** A connection over a quiche that stamps each datagram with the given raw due time, whatever the loop's clock reads. */
    private class Rig(
        rawDueTimes: LongArray,
        rules: SlotRules,
    ) {
        private val io = FakeIo()
        var sent = 0
        val conn = ConnRig(Stamping(StubQuicheApi(), rawDueTimes), rules = rules, io = io)

        init {
            io.onSend = { pending ->
                sent += pending
                SendBatchResult.AllTaken
            }
            repeat(rawDueTimes.size) { (conn.api as Stamping).stub.connSendQueue.addLast(1200) }
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
