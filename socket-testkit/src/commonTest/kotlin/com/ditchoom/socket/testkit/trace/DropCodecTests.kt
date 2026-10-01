package com.ditchoom.socket.testkit.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/** The `DROP` line survives the text boundary a walk's trace file crosses, and is never read as an error. */
class DropCodecTests {
    private val event =
        TraceEvent.Drop(
            3_000_000L.nanoseconds,
            "com.ditchoom.socket.quic.ServerDatagramDrop.ForClosedConnection",
            "42 B from /192.0.2.7:4433: for a connection this server closed within its draining period",
        )

    @Test
    fun theLineIsV1TimestampDropTypeMessage() {
        assertEquals(
            "v1 3000000 DROP com.ditchoom.socket.quic.ServerDatagramDrop.ForClosedConnection " +
                "42 B from /192.0.2.7:4433: for a connection this server closed within its draining period",
            event.toString(),
        )
    }

    @Test
    fun itRoundTripsAndIsAnObservation() {
        val parsed = TraceEvent.parse(event.toString())
        assertEquals(event, parsed)
        assertTrue(parsed !is TraceEvent.Error, "an expected drop must never parse back as an error")
        assertTrue(!event.isInput, "this endpoint's disposition of a datagram is never replayed as a network input")
    }
}
