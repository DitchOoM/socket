package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PacerTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val ms = 1_000_000L
    private val pacer = Pacer(PacingRule.ByQuiche(allowanceNanos = ms, limitNanos = 1_000 * ms))

    @Test
    fun aDatagramDueWithinTheAllowanceDoesNotHold() {
        assertEquals(PaceState.Free, pacer.sent(atNanos = ms, now = Nanos(0)))
        assertEquals(PaceState.Free, pacer.state(Nanos(0)))
    }

    @Test
    fun aDatagramDueLaterHoldsUntilItsTimeAndOffersIt() {
        assertEquals(PaceState.Holding, pacer.sent(atNanos = 5 * ms, now = Nanos(0)))
        assertEquals(PaceState.Holding, pacer.state(Nanos(3 * ms)))
        val sink = WakeSink()
        pacer.offerWake(sink)
        assertEquals(WakeState.Asked, sink.state())
        assertEquals(Nanos(5 * ms), sink.earliest())
        assertEquals(PaceState.Free, pacer.state(Nanos(4 * ms + 1)))
    }

    @Test
    fun aTimeBeyondTheLimitIsNotPacing() {
        assertEquals(PaceState.Free, pacer.sent(atNanos = 3_600_000 * ms, now = Nanos(0)))
        val sink = WakeSink()
        pacer.offerWake(sink)
        assertEquals(WakeState.NotAsked, sink.state())
    }
}
