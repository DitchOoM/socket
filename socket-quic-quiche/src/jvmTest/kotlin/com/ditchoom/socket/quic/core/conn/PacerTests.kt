package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlin.test.Test
import kotlin.test.assertEquals

class PacerTests {
    private val ms = 1_000_000L
    private val pacer = Pacer(PacingRule.ByQuiche(allowanceNanos = ms, limitNanos = 1_000 * ms, mismatch = MismatchResponse.Fail))

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

    @Test
    fun aTimeBeyondTheLimitIsAClockMismatchAndHoldsNothing() {
        assertEquals(Due.ClockMismatch, pacer.sent(atNanos = 3_600_000 * ms, now = Nanos(0)))
        assertEquals(3_600_000 * ms, pacer.aheadNanos())
        val sink = WakeSink()
        pacer.offerWake(sink)
        assertEquals(WakeState.NotAsked, sink.state())
    }
}
