package com.ditchoom.socket.testkit.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/** The `TIMER_FIRED` line survives the text boundary a recording crosses. */
class TimerFiredCodecTests {
    private val event = TraceEvent.TimerFired(36_806_196L.nanoseconds)

    @Test
    fun theLineIsV1TimestampTimerFired() {
        assertEquals("v1 36806196 TIMER_FIRED", event.toString())
    }

    @Test
    fun itRoundTripsAndIsAnObservation() {
        assertEquals(event, TraceEvent.parse(event.toString()))
        assertTrue(!event.isInput, "what this endpoint's host did is never injected as a network input")
    }
}
