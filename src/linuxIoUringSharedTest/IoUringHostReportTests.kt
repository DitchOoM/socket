package com.ditchoom.socket.iouring

import kotlin.test.Test
import kotlin.test.assertEquals

class IoUringHostReportTests {
    /**
     * Each section of the failure-path host report appears once. Merging #691 and #727 left the ring census
     * printed twice; with the sections declared as data, a repeat is a repeated label, caught here rather
     * than by a reader of an ENOMEM report wondering whether two censuses disagree.
     */
    @Test
    fun everySectionOfTheHostReportAppearsExactlyOnce() {
        val labels = HOST_REPORT_SECTIONS.map { it.label }
        assertEquals(
            emptyMap(),
            labels.groupingBy { it }.eachCount().filterValues { it > 1 },
            "host report sections declared more than once",
        )
        val report = ioUringHostReport()
        val printed = report.lines().map { it.trim().substringBefore(": ") }
        assertEquals(labels, printed, "the report prints each declared section once, in order:\n$report")
    }
}
