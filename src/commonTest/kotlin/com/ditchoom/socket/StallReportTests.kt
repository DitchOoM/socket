package com.ditchoom.socket

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StallReportTests {
    @Test
    fun aWaitPastTheThreshold_reportsOnce_andRunsItsBlockExactlyOnce() =
        runTest {
            val reports = mutableListOf<String>()
            var starts = 0
            val result =
                reportingStall("a slow close", { "state" }, { reports += it }) {
                    starts++
                    delay(STALL_REPORT_AFTER * 3)
                    7
                }
            assertEquals(7, result)
            assertEquals(1, starts, "the block is an action such as close(); a report must not cancel and restart it")
            assertEquals(1, reports.size, reports.toString())
        }

    @Test
    fun aWaitInsideTheThreshold_reportsNothing() =
        runTest {
            val reports = mutableListOf<String>()
            val result =
                reportingStall("a quick close", { "state" }, { reports += it }) {
                    delay(STALL_REPORT_AFTER / 2)
                    7
                }
            assertEquals(7, result)
            assertEquals(emptyList(), reports)
        }
}
