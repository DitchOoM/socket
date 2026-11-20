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
