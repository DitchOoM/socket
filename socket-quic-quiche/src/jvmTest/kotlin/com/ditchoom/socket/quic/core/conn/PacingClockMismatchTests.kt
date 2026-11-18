package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
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
        val rig = Rig(rawDueTimes = longArrayOf(origin + 2 * ms, origin + 4 * ms, origin + 6 * ms))

        MismatchResponse.sendingNowDuring { rig.conn.pass(at = 0) }

        assertEquals(3L, rig.conn.counts.pacingClockMismatches, "each of the three datagrams was counted")
        assertEquals(3, rig.sent, "and each was sent at once")
    }

    @Test
    fun aMismatchInATestRunEndsTheConnectionWithItsTypedCause() {
        val rig = Rig(rawDueTimes = longArrayOf(origin + 2 * ms, origin + 4 * ms))
        val before = MismatchResponse.choose(MismatchResponse.Fail)
        try {
            rig.conn.pass(at = 0)
        } finally {
            MismatchResponse.choose(before)
        }

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
        val rig = Rig(rawDueTimes = longArrayOf(2 * ms, 4 * ms, 6 * ms))

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

    /** A connection over a quiche that stamps each datagram with the given raw due time, whatever the loop's clock reads. */
    private class Rig(
        rawDueTimes: LongArray,
    ) {
        private val io = FakeIo()
        var sent = 0
        val conn = ConnRig(Stamping(StubQuicheApi(), rawDueTimes), io = io)

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
