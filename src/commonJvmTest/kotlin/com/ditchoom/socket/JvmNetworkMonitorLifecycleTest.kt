package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle smoke for the JVM (and Android) [NetworkMonitor]s reachable from the test classpath —
 * [PollingNetworkMonitor] and whatever [NetworkMonitor.default] resolves to here.
 *
 * These assert the boring-but-load-bearing invariants: the monitor *seeds* both flows from a real
 * interface scan on startup, its [NetworkId] respects the sealed contract (never a bare string/null;
 * only [NetworkId.Link] or [NetworkId.Unidentified] on a raw-scan platform), and [close] tears the
 * polling scope down without throwing. Host-independent — a link may or may not exist on the runner.
 *
 * Note: [NetworkMonitor.default] here returns the polling base — the JDK only version-shadows the
 * reactive FFM routing-socket monitors (`NetlinkNetworkMonitor`/`RouteNetworkMonitor`, in the
 * `jvm21Main` compilation) in from the *assembled* multi-release JAR at runtime. Those monitors are
 * exercised directly by `NetlinkNetworkMonitorTest`, whose `jvmTest` classpath the build augments with
 * the `java21` compilation output.
 */
class JvmNetworkMonitorLifecycleTest {
    private fun assertNetworkIdInvariant(id: NetworkId) {
        when (id) {
            is NetworkId.Link -> assertTrue(id.handle != 0L, "a real link must carry a non-zero interface handle")
            NetworkId.Unidentified -> Unit // valid on a runner with no qualifying interface
            is NetworkId.KindOnly -> throw AssertionError("a JVM raw-scan monitor never produces KindOnly")
        }
    }

    @Test
    fun pollingMonitorSeedsBothFlowsThenClosesCleanly() =
        runBlocking(Dispatchers.IO) {
            val monitor = PollingNetworkMonitor(interval = 50.milliseconds)
            try {
                // The first poll iteration runs before the first delay, so both flows settle promptly.
                val availability = withTimeout(5.seconds) { monitor.availability.first { it != NetworkAvailability.UNKNOWN } }
                assertTrue(
                    availability == NetworkAvailability.AVAILABLE || availability == NetworkAvailability.UNAVAILABLE,
                    "availability must settle to a definite value from the interface scan",
                )
                assertNetworkIdInvariant(monitor.networkId.value)
            } finally {
                monitor.close()
            }
        }

    @Test
    fun defaultMonitorIsFunctionalAndCloses() =
        runBlocking(Dispatchers.IO) {
            val monitor = NetworkMonitor.default()
            try {
                // Whatever the platform default is, it must expose the contract without throwing.
                assertNetworkIdInvariant(monitor.networkId.value)
            } finally {
                monitor.close()
            }
        }
}
