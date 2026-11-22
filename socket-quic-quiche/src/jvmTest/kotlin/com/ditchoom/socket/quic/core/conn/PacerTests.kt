package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PacerTests {
    private val ms = 1_000_000L
    private val pacer = Pacer(PacingRule.ByQuiche(allowanceNanos = ms, mismatch = MismatchResponse.Fail), ClockVerdict.Unjudged)

    @Test
    fun aDatagramDueWithinTheAllowanceDoesNotHold() {
        assertEquals(Due.Now, pacer.sent(atNanos = ms, now = Nanos(0)))
        assertEquals(PaceState.Free, pacer.state(Nanos(0)))
    }

    @Test
    fun aDatagramDueLaterHoldsUntilItsTimeAndOffersIt() {
        assertEquals(Due.Hold, pacer.sent(atNanos = 5 * ms, now = Nanos(0)))
        assertEquals(5 * ms, pacer.aheadNanos())
        assertEquals(PaceState.Holding, pacer.state(Nanos(3 * ms)))
        val sink = WakeSink()
        pacer.offerWake(sink)
        assertEquals(WakeState.Asked, sink.state())
        assertEquals(Nanos(5 * ms), sink.earliest())
        assertEquals(PaceState.Free, pacer.state(Nanos(4 * ms + 1)))
    }

    /** A collapsed link's BBR2 paces seconds ahead: still pacing, so still held. */
    @Test
    fun aDatagramDueAnHourAheadIsHeldForAnHour() {
        val hour = 3_600_000 * ms
        assertEquals(Due.Hold, pacer.sent(atNanos = hour, now = Nanos(0)))
        assertEquals(PaceState.Holding, pacer.state(Nanos(hour - 2 * ms)))
        val sink = WakeSink()
        pacer.offerWake(sink)
        assertEquals(Nanos(hour), sink.earliest())
    }

    /** A new connection has no credit: its first flight is quiche's, held as quiche asks. */
    @Test
    fun aConnectionThatHasNotGoneQuietSendsNothingAheadButTheDatagramThatStopsTheWrite() {
        assertEquals(Due.Now, pacer.sent(atNanos = 0, now = Nanos(0)))
        assertEquals(Due.Hold, pacer.sent(atNanos = 5 * ms, now = Nanos(0)))
    }

    /** A quiet connection's datagram due now restores the credit: the burst's datagrams after it go ahead of their times. */
    @Test
    fun aQuietConnectionSendsABurstAheadOfQuichesTimesAndIsHeldAtItsLast() {
        pacer.drained()

        assertEquals(Due.Now, pacer.sent(atNanos = 0, now = Nanos(0)))
        repeat(Pacer.BURST_DATAGRAMS - 1) { n ->
            assertEquals(Due.Early, pacer.sent(atNanos = (n + 1) * 5 * ms, now = Nanos(0)), "datagram ${n + 1} goes ahead of its time")
        }
        val last = Pacer.BURST_DATAGRAMS * 5 * ms
        assertEquals(Due.Hold, pacer.sent(atNanos = last, now = Nanos(0)), "the burst's last datagram ahead stops the write")
        assertEquals(PaceState.Holding, pacer.state(Nanos(last - 2 * ms)))
        assertEquals(PaceState.Free, pacer.state(Nanos(last)))
        assertEquals(Due.Hold, pacer.sent(atNanos = last + 5 * ms, now = Nanos(last)), "past the burst, quiche's times hold")
    }

    /** The credit comes back only once quiche's times caught up with the clock: a quiet connection still ahead gets none. */
    @Test
    fun aQuietConnectionWhoseNextDatagramIsStillAheadGetsNoCredit() {
        pacer.drained()
        assertEquals(Due.Now, pacer.sent(atNanos = 0, now = Nanos(0)))
        repeat(Pacer.BURST_DATAGRAMS - 1) { n -> pacer.sent(atNanos = (n + 1) * 5 * ms, now = Nanos(0)) }
        pacer.drained()

        // Quiet again, but the next datagram is still due after the burst's: nothing of the burst has caught up.
        assertEquals(Due.Hold, pacer.sent(atNanos = 60 * ms, now = Nanos(1 * ms)))
    }

    /** A connection that keeps sending, held or not, never goes quiet: a datagram due now does not restore the credit. */
    @Test
    fun aDatagramDueNowOnAConnectionThatKeptSendingRestoresNothing() {
        assertEquals(Due.Hold, pacer.sent(atNanos = 5 * ms, now = Nanos(0)))
        pacer.drained()
        assertEquals(PaceState.Holding, pacer.state(Nanos(1 * ms)), "a held connection stays held when quiche has nothing more")
        assertEquals(PaceState.Free, pacer.state(Nanos(5 * ms)))

        assertEquals(Due.Now, pacer.sent(atNanos = 5 * ms, now = Nanos(5 * ms)))
        assertEquals(Due.Hold, pacer.sent(atNanos = 10 * ms, now = Nanos(5 * ms)))
    }

    /** Spent credit stays spent until the connection is quiet and quiche's times are due again. */
    @Test
    fun aBurstLeftUnspentKeepsWhatIsLeftAndAQuietDueDatagramRestoresAll() {
        pacer.drained()
        assertEquals(Due.Now, pacer.sent(atNanos = 0, now = Nanos(0)))
        assertEquals(Due.Early, pacer.sent(atNanos = 5 * ms, now = Nanos(0)))
        pacer.drained()

        // Due now after the quiet: every datagram sent before it is past its time, and the whole burst is back.
        assertEquals(Due.Now, pacer.sent(atNanos = 100 * ms, now = Nanos(100 * ms)))
        repeat(Pacer.BURST_DATAGRAMS - 1) { n ->
            assertEquals(Due.Early, pacer.sent(atNanos = (100 + (n + 1) * 5) * ms, now = Nanos(100 * ms)))
        }
        assertEquals(Due.Hold, pacer.sent(atNanos = 160 * ms, now = Nanos(100 * ms)))
    }

    @Test
    fun aFirstStampBetweenTheReadingsAroundItsWriteAgrees() {
        assertEquals(ClockVerdict.Agrees, pacer.judge(atNanos = 5 * ms, before = Nanos(5 * ms), after = Nanos(9 * ms)))
        assertEquals(ClockVerdict.Agrees, pacer.verdict())
    }

    @Test
    fun aFirstStampWithinTheToleranceOutsideTheReadingsAgrees() {
        val tolerance = Pacer.CLOCK_TOLERANCE_NANOS
        assertEquals(ClockVerdict.Agrees, pacer.judge(atNanos = 5 * ms - tolerance, before = Nanos(5 * ms), after = Nanos(5 * ms)))
        assertEquals(ClockVerdict.Agrees, pacer.judge(atNanos = 5 * ms + tolerance, before = Nanos(5 * ms), after = Nanos(5 * ms)))
    }

    @Test
    fun aFirstStampBehindTheWritesStartDiffersByHowFarBehind() {
        val verdict = assertIs<ClockVerdict.Differs>(pacer.judge(atNanos = 2 * ms, before = Nanos(10 * ms), after = Nanos(11 * ms)))
        assertEquals(-8 * ms, verdict.offsetNanos)
    }

    @Test
    fun aFirstStampAheadOfTheWritesEndDiffersByHowFarAhead() {
        val verdict = assertIs<ClockVerdict.Differs>(pacer.judge(atNanos = 20 * ms, before = Nanos(10 * ms), after = Nanos(11 * ms)))
        assertEquals(9 * ms, verdict.offsetNanos)
    }
}
