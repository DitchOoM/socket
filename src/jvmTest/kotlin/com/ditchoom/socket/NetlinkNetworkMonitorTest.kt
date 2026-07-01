package com.ditchoom.socket

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Exercises the JDK 21+ FFM netlink monitor directly on Linux.
 *
 * [NetlinkNetworkMonitor] lives in the `jvm21Main` compilation, whose output is
 * added to the JVM test classpath by the build. The test is Linux-gated because
 * `AF_NETLINK` only exists on Linux; on other hosts it is a no-op.
 */
class NetlinkNetworkMonitorTest {
    private val isLinux =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("linux")

    @Test
    fun opensNetlinkSocketAndReportsAvailability() {
        if (!isLinux) return
        val monitor = NetlinkNetworkMonitor()
        try {
            // Constructing the monitor runs socket()/bind() via FFM and seeds the
            // availability from getNetworkInterfaces(). This dev/CI box always has a
            // non-loopback interface up, so the seed must be a resolved (non-UNKNOWN) state.
            assertNotEquals(NetworkAvailability.UNKNOWN, monitor.availability.value)
            assertEquals(NetworkAvailability.AVAILABLE, monitor.availability.value)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun closeIsIdempotentAndUnblocksRecv() {
        if (!isLinux) return
        val monitor = NetlinkNetworkMonitor()
        // The recv loop is blocked in a native downcall; close() must close the fd
        // to unblock it. Calling close() twice must not throw.
        monitor.close()
        monitor.close()
    }

    @Test
    fun availabilityFlowEmitsInitialValue() =
        runTest {
            if (!isLinux) return@runTest
            val monitor = NetlinkNetworkMonitor()
            try {
                val value = withTimeout(2_000) { monitor.availability.first() }
                assertNotEquals(NetworkAvailability.UNKNOWN, value)
            } finally {
                monitor.close()
            }
        }
}
