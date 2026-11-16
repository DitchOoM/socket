package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.nanoseconds

/** The flight recorder writes the trace grammar the existing tools read, from records taken without allocating. */
class ConnFlightTests {
    private val peer = PathKey(4, 443, 0L, 0x7F000001L)

    private fun datagram(vararg bytes: Int) =
        nativeBytes(bytes.size).also { memory ->
            bytes.forEachIndexed { i, b -> memory.owner.set(i, b.toByte()) }
        }

    @Test
    fun aDumpIsTheV1GrammarAndParsesBack() {
        val flight = ConnFlight(FlightSize(8), FlightArena(nativeBytes(64)), peer, TraceCapture.Off, originNanos = 100L)
        flight.datagramOut(Nanos(150L), datagram(0xC0, 0x01, 0xFF), 0, 3)
        flight.datagramIn(Nanos(170L), datagram(0x40, 0x02), 0, 2)
        val events = ArrayList<TraceEvent>()
        flight.dump(TraceSink { events += it })

        val path = TracePath(4, 443, 0L, 0x7F000001L)
        assertEquals(
            listOf(TraceEvent.DgramOut(50.nanoseconds, 3, path, "c001ff"), TraceEvent.DgramIn(70.nanoseconds, 2, path, "4002")),
            events,
        )
        events.forEach { assertEquals(it, TraceEvent.parse(it.toString())) }
    }

    @Test
    fun aPayloadTheArenaOverwroteIsLeftOutAndTheTraceIsWrittenOnce() {
        val recorded = ArrayList<TraceEvent>()
        val capture = TraceCapture.On(QuicTraceRecorder(TraceSink { recorded += it }))
        val arena = FlightArena(nativeBytes(4))
        val length = arena.capacity / 2 + 1
        val flight = ConnFlight(FlightSize(8), arena, peer, capture, originNanos = 0L)
        flight.datagramOut(Nanos(1L), datagram(*IntArray(length) { 1 }), 0, length)
        flight.datagramOut(Nanos(2L), datagram(*IntArray(length) { 2 }), 0, length)
        flight.toTrace()
        flight.toTrace()
        assertEquals(listOf("02".repeat(length)), recorded.map { (it as TraceEvent.DgramOut).payloadHex })
    }
}
