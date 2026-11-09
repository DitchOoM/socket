package com.ditchoom.socket.udp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** The timeline separates the three places a slow connect can lose time, on one clock. */
class NwConnectTraceTests {
    @Test
    fun eachStateChangeSaysWhenNwReportedItAndWhenKotlinSawIt() {
        var clock = 1_000_000_000L
        val trace = NwConnectTrace(now = { clock })
        trace.mark("create begun")
        clock += 150_000 // 0.15 ms
        trace.mark("created")
        clock += 6_400_000_000 // NW stays silent for 6.4 s
        val nwEntry = clock
        clock += 2_000_000 // the callback takes 2 ms to reach Kotlin
        trace.state(1, 0, 0, NW_PATH_UNSATISFIED, 0, nwEntry)

        val text = trace.render()
        assertContains(text, "+0.000 ms create begun")
        assertContains(text, "+0.150 ms created")
        assertContains(
            text,
            "+6402.150 ms state=waiting path=unsatisfied reason=0 (NW reported at +6400.150 ms, reached Kotlin 2.000 ms later)",
        )
    }

    @Test
    fun theSummaryNamesTheFirstNwReportNotTheConnectsOwnSteps() {
        var clock = 0L
        val trace = NwConnectTrace(now = { clock })
        trace.mark("create begun")
        clock += 3_000_000
        trace.mark("started")
        assertContains(trace.firstReportSummary(), "NW never reported")
        val nwEntry = clock + 1_000_000
        clock += 1_500_000
        trace.state(1, 0, 0, NW_PATH_UNSATISFIED, 0, nwEntry)
        clock += 9_000_000_000
        trace.state(5, 0, 0, NW_PATH_UNSATISFIED, 0, clock)
        assertContains(trace.firstReportSummary(), "NW first reported at +4.000 ms, Kotlin saw it at +4.500 ms; load average ")
    }

    @Test
    fun eventsPastCapacityAreCountedNotKept() {
        val trace = NwConnectTrace(now = { 0L })
        repeat(NwConnectTrace.CAPACITY + 3) { trace.mark("step $it") }
        val text = trace.render()
        assertContains(text, "3 later events not kept")
        assertTrue("step ${NwConnectTrace.CAPACITY}" !in text, text)
    }
}
