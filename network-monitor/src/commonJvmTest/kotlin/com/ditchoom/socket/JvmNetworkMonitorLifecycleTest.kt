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
 * These assert the boring-but-load-bearing invariants: the monitor *seeds* [NetworkMonitor.state] from a
 * real interface scan on startup, its [NetworkId] respects the sealed contract (never a bare string/null;
 * only [NetworkId.Link] or [NetworkId.Unidentified] on a raw-scan platform), the state it publishes is one
 * its own declared [MonitorCapability] permits, and [close] tears the polling scope down without throwing.
 * Host-independent — a link may or may not exist on the runner.
 *
 * Note: which monitor [NetworkMonitor.default] returns here is deliberately NOT asserted. The reactive
 * FFM routing-socket monitors (`NetlinkNetworkMonitor`/`RouteNetworkMonitor`) are compiled by the
 * `java21` compilation and ship shadowed under the multi-release JAR's `META-INF/versions/21`, so which
 * one `defaultJvmNetworkMonitor()` selects depends on whether the run resolves the *assembled* JAR or
 * loose class directories — a packaging fact, not a contract. What every arrangement must satisfy is
 * what this asserts: the returned monitor works and honours its own declared capability. The FFM
 * monitors are exercised directly by `NetlinkNetworkMonitorTest`, whose `jvmTest` classpath this
 * module's build augments with the `java21` output.
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
    fun pollingMonitorSeedsItsStateThenClosesCleanly() =
        runBlocking(Dispatchers.IO) {
            val monitor = PollingNetworkMonitor(interval = 50.milliseconds)
            try {
                // The first poll iteration runs before the first delay, so the state settles promptly.
                val state = withTimeout(5.seconds) { monitor.state.first { it != NetworkState.Unknown } }
                assertTrue(
                    state == NetworkState.Offline || state is NetworkState.Up,
                    "the state must settle to a definite rung from the interface scan, was $state",
                )
                assertNetworkIdInvariant(state.networkId)
                assertCapabilityHonoured(monitor, state)
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
                assertNetworkIdInvariant(monitor.state.value.networkId)
                assertCapabilityHonoured(monitor, monitor.state.value)
            } finally {
                monitor.close()
            }
        }

    /**
     * A monitor must never publish a state its own [MonitorCapability] forbids — the pairing rules of
     * [ReachResolution] are enforced here, not merely documented (RFC_NETWORK_REACHABILITY §3.2).
     */
    private fun assertCapabilityHonoured(
        monitor: NetworkMonitor,
        state: NetworkState,
    ) = assertTrue(
        monitor.capability.resolution.permits(state),
        "${monitor::class.simpleName} declares ${monitor.capability.resolution} but published $state",
    )
}
