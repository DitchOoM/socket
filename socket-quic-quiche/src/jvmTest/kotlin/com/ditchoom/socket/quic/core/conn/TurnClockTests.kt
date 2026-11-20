package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PinAnswer
import com.ditchoom.socket.quic.QuicheClockPin
import com.ditchoom.socket.quic.core.testing.VirtualClock
import com.ditchoom.socket.quic.core.time.ClockBase
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.QuicheTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a pinned connection reads inside, and what libquiche is pinned to while it does. */
class TurnClockTests {
    @Test
    fun aConnectionACallerServedAfterThePassBeganKeepsTheCallersTimeSoItsTimeNeverRunsBackwards() {
        // The loop read 100 when its pass began; a caller then served one connection at 105, on its own thread. When the
        // pass reaches that connection, libquiche must not read 100 for it: an acknowledgement of a packet sent at 105
        // would measure a round trip of less than nothing.
        val pin = RecordingPin()
        val time = QuicheTime.Pinned(pin)
        val clock = VirtualClock(Nanos(0L), ClockBase.Libquiche)
        val served = TurnClock(TurnTime.of(time, clock), clock)
        val other = TurnClock(TurnTime.of(time, clock), clock)

        time.loop.pass(Nanos(100L))
        served.pinHere(Nanos(105L))
        served.unpinHere()
        assertEquals(Nanos(105L), served.held(Nanos(100L)), "the served connection holds the caller's later time")
        assertEquals(Nanos(105L), served.now())
        assertEquals(Nanos(100L), other.held(Nanos(100L)), "the next connection holds the pass's time")
        assertEquals(listOf(100L, 105L, 105L, 100L), pin.pins, "libquiche follows each connection's time, and only when it changes")
        assertEquals(0L, clock.reads, "and nothing read the clock")
    }

    private class RecordingPin : QuicheClockPin {
        val pins = ArrayList<Long>()

        override fun pinMonotonic(nanos: Long): PinAnswer {
            pins += nanos
            return PinAnswer.Pinned
        }

        override fun unpin() = Unit
    }
}
