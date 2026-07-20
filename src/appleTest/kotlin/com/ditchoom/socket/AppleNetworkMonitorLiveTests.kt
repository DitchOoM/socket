package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Live-path smoke coverage for [AppleNetworkMonitor] — the first test that actually starts
 * `NWPathMonitor` and drives the full chain the pure-mapper [AppleNetworkIdMappingTests] can't reach:
 * `nw_path_monitor_start` → the Objective-C block on the dispatch queue → the K/N callback → the
 * [availability]/[networkId] StateFlows → [close] (cancel). Until this ran, Network.framework was
 * compiled but never invoked in any Apple test (build-apple was contract-only), so this is also where
 * a K/N thread-confinement / freeze bug on the callback would first surface.
 *
 * Assertions are environment-invariant on purpose (mirrors the Linux netns lesson: assert liveness and
 * identity, never a specific semantic kind a virtualized CI runner can't guarantee). A headless macOS
 * or simulator runner's primary interface may be wired, a bridge, or `other` — so this asserts only
 * that the path resolves at all, not that it is Wi-Fi.
 */
class AppleNetworkMonitorLiveTests {
    @Test
    fun pathMonitorDeliversAFirstUpdateAndResolvesTheNetwork() =
        runTestNoTimeSkipping(timeout = 30.seconds) {
            val monitor = AppleNetworkMonitor()
            try {
                // NWPathMonitor delivers the current path once, promptly, after start. The single thing
                // every environment must show is that the callback fired at all: availability leaves the
                // just-started UNKNOWN sentinel. A timeout here is a real failure (monitor never woke).
                val availability =
                    monitor.availability.first { it != NetworkAvailability.UNKNOWN }

                // If the runner has a usable path (the normal case — it just cloned the repo), the same
                // callback must have produced a concrete primary-link identity: a Link with a non-zero OS
                // interface index and one of the typed kinds. We do NOT require AVAILABLE: an offline or
                // firewalled runner legitimately reports UNAVAILABLE, and that is still a live callback.
                when (availability) {
                    NetworkAvailability.AVAILABLE -> {
                        val id = monitor.networkId.value
                        val link =
                            id as? NetworkId.Link
                                ?: fail("AVAILABLE path must resolve to a NetworkId.Link, was $id")
                        assertTrue(link.handle > 0, "Link handle must be a real OS interface index, was ${link.handle}")
                        assertTrue(
                            link.kind is NetworkKind.Wifi ||
                                link.kind is NetworkKind.Cellular ||
                                link.kind is NetworkKind.Ethernet ||
                                link.kind is NetworkKind.Vpn ||
                                link.kind is NetworkKind.Other,
                            "Link kind must be a known NetworkKind, was ${link.kind}",
                        )
                    }

                    NetworkAvailability.UNAVAILABLE ->
                        // No usable path — still a live callback; networkId stays Unidentified by contract.
                        assertTrue(
                            monitor.networkId.value == NetworkId.Unidentified,
                            "UNAVAILABLE path must leave networkId Unidentified, was ${monitor.networkId.value}",
                        )

                    NetworkAvailability.UNKNOWN ->
                        fail("unreachable — awaited a non-UNKNOWN availability")
                }
            } finally {
                // close() must cancel the NWPathMonitor without crashing or hanging.
                monitor.close()
            }
        }
}
