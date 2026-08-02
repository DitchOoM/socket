package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
    fun opensNetlinkSocketAndReportsARoutableState() {
        if (!isLinux) return
        val monitor = NetlinkNetworkMonitor()
        try {
            // Constructing the monitor runs socket()/bind() via FFM and seeds the state from
            // resolveJvmNetworkState(). This dev/CI box always has a non-loopback interface up AND a
            // default route, so the seed must be Routable — not merely "not Unknown", which the old
            // any-interface-is-up check would have satisfied even inside a routeless container.
            assertNotEquals(NetworkState.Unknown, monitor.state.value)
            val state = monitor.state.value
            assertTrue(state is NetworkState.Routable, "a CI/dev host with a default route is Routable, got $state")
            // RouteOnly: the routing socket says which link carries the route and nothing about the
            // internet, so reachability must be Unobserved rather than an invented verdict.
            assertEquals(InternetAccess.Unobserved, state.internet)
            assertTrue(monitor.capability.resolution == ReachResolution.RouteOnly)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun seedsRouteAwareNetworkIdOrUnidentified() {
        if (!isLinux) return
        val monitor = NetlinkNetworkMonitor()
        try {
            // State resolution seeds identity from the /proc/net/route default-route interface.
            // Host-independent invariant: a Link with a non-zero index handle, or Unidentified — never a
            // bare string/null, never KindOnly.
            when (val id = monitor.state.value.networkId) {
                is NetworkId.Link -> assertTrue(id.handle != 0L, "a real link carries a non-zero interface handle")
                NetworkId.Unidentified -> Unit
                is NetworkId.KindOnly -> throw AssertionError("the JVM raw-scan resolver never produces KindOnly")
            }
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
    fun stateFlowEmitsInitialValue() =
        runTest {
            if (!isLinux) return@runTest
            val monitor = NetlinkNetworkMonitor()
            try {
                val value = withTimeout(2_000) { monitor.state.first() }
                assertNotEquals(NetworkState.Unknown, value)
            } finally {
                monitor.close()
            }
        }

    @Test
    fun everyEmittedStateMatchesTheDeclaredCapability() =
        runTest {
            if (!isLinux) return@runTest
            val monitor = NetlinkNetworkMonitor()
            try {
                // The pairing rule, checked against a real monitor rather than a fixture: whatever this
                // actually emits must be something its declared RouteOnly resolution could produce.
                val value = withTimeout(2_000) { monitor.state.first() }
                assertTrue(
                    monitor.capability.resolution.permits(value),
                    "emitted $value but declares ${monitor.capability.resolution}",
                )
            } finally {
                monitor.close()
            }
        }
}
