package com.ditchoom.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Closing the FFM routing-socket monitor ends its reader, with no routing event to wake it.
 *
 * Closing a descriptor does not wake a thread blocked in `recv` on it on Linux, and `shutdown(2)` is
 * refused by netlink sockets (`ENOTSUP`), so a close that only closes the fd leaves the reader parked on
 * a quiet host until the next netlink message, which may never come. The monitor's scope has no other
 * child than the reader, so the scope's job finishes exactly when the reader has.
 *
 * This runs on Linux (netlink, the case the defect was found on) and on macOS (PF_ROUTE); anywhere else it
 * records a skip line and does nothing. It was written without a Linux host to hand: the Linux JVM lanes
 * in CI are where it runs for the defect it names.
 */
class RoutingMonitorCloseTest {
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    private fun monitorOrNull(): FfmRoutingSocketNetworkMonitor? =
        when {
            os.contains("linux") -> NetlinkNetworkMonitor()
            os.contains("mac") -> RouteNetworkMonitor()
            else -> null
        }

    private fun readerJobOf(monitor: FfmRoutingSocketNetworkMonitor): Job {
        val field = FfmRoutingSocketNetworkMonitor::class.java.getDeclaredField("scope")
        field.isAccessible = true
        return checkNotNull((field.get(monitor) as CoroutineScope).coroutineContext[Job]) { "the monitor's scope has no job" }
    }

    @Test
    fun closeEndsTheRoutingReaderWithoutAnyRoutingEvent() {
        val monitor = monitorOrNull()
        if (monitor == null) {
            println("[TEST-SKIPPED] site=RoutingMonitorCloseTest reason=host-has-no-routing-socket detail=os.name=$os")
            return
        }
        val scopeJob = readerJobOf(monitor)
        runBlocking {
            // Let the reader reach its blocking wait; a reader not yet started could not show the defect.
            kotlinx.coroutines.delay(500.milliseconds)
            val readers = scopeJob.children.toList()
            assertTrue(readers.isNotEmpty(), "no reader was running, so nothing was tested")
            monitor.close()
            assertNotNull(
                withTimeoutOrNull(5.seconds) { readers.forEach { it.join() } },
                "the routing-socket reader was still parked 5 s after close() with no routing event arriving",
            )
        }
    }
}
