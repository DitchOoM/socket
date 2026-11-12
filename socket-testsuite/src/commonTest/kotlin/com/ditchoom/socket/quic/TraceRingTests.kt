package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The trace ring that rides the shared QUIC suites: what it keeps, how it chains, what a failure carries. */
class TraceRingTests {
    private fun state(n: Int) = TraceEvent.State(n.milliseconds, "S$n", null)

    private fun options() = QuicOptions(alpnProtocols = listOf("test"))

    @Test
    fun aRingKeepsTheNewestEventsInOrder() {
        val ring = TraceRing("t", capacity = 4)
        repeat(10) { ring.sink.emit(state(it)) }
        assertEquals(10, ring.seen())
        val kept = ring.snapshot()
        assertEquals(4, kept.size)
        assertTrue(kept.first().contains("S6") && kept.last().contains("S9"), "kept $kept")
    }

    @Test
    fun aDatagramIsRecordedByItsHeaderNotItsPayload() {
        val ring = TraceRing("t")
        ring.sink.emit(TraceEvent.DgramOut(5.milliseconds, 1200, null, "ab".repeat(1200)))
        val line = ring.snapshot().single()
        assertTrue(line.contains("DGRAM_OUT 1200"), line)
        assertTrue(line.length < 100, "payload hex must not bury the report: ${line.length}")
    }

    @Test
    fun aLaneThatDidNotAskGetsTheOptionsBackUntouched() {
        val o = options()
        assertSame(o, o.withTraceRing("x", enabled = false))
        assertNull(o.withTraceRing("x", enabled = false).trace)
    }

    @Test
    fun anEnabledRingMintsOneRingPerConnectionAndKeepsTheTestsOwnCapture() {
        TraceRings.beginTest()
        val seen = mutableListOf<TraceEvent>()
        val own = QuicTraceCapture({ QuicConnectionCapture(TraceSink { seen += it }, qlog = QlogTarget.File("/tmp/x.sqlog")) })
        val traced = options().copy(trace = own).withTraceRing("peer", enabled = true)
        val capture = assertNotNull(traced.trace)
        val first = capture.captureFor()
        val second = capture.captureFor()
        first.sink.emit(state(1))
        second.sink.emit(state(2))
        assertEquals(2, seen.size, "the test's own sink still receives every event")
        assertEquals(QlogTarget.File("/tmp/x.sqlog"), first.qlog, "its qlog target is left as it was")
        val report = assertNotNull(TraceRings.report())
        assertTrue(report.contains("peer#0") && report.contains("peer#1"), report)
        assertTrue(report.contains("S1") && report.contains("S2"), report)
    }

    @Test
    fun onlyTheLastFewConnectionsAreKept() {
        TraceRings.beginTest()
        repeat(30) { TraceRings.ringFor("c").add("e$it") }
        val report = assertNotNull(TraceRings.report())
        assertTrue(report.contains("c#29") && !report.contains("c#0:"), report)
    }

    @Test
    fun aFailingTestCarriesTheTimelineAsASuppressedExceptionAndKeepsItsOwnType() {
        val thrown =
            assertFailsWith<IllegalStateException> {
                runQuicTest {
                    val ring = TraceRings.ringFor("client")
                    ring.sink.emit(state(7))
                    error("deliberate failure")
                }
            }
        assertEquals("deliberate failure", thrown.message)
        val timeline = thrown.suppressedExceptions.filterIsInstance<QuicProtocolTimeline>().single()
        assertTrue(timeline.message!!.contains("client#0") && timeline.message!!.contains("S7"), timeline.message)
    }

    @Test
    fun aPassingTestAndATestWithNoConnectionsCarryNothing() {
        runQuicTest { TraceRings.ringFor("client").sink.emit(state(1)) }
        TraceRings.beginTest()
        assertNull(TraceRings.report())
        val thrown = assertFailsWith<IllegalStateException> { runQuicTest { error("no connection opened") } }
        assertTrue(thrown.suppressedExceptions.none { it is QuicProtocolTimeline })
    }
}
