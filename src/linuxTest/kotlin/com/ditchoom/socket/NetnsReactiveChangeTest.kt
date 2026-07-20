@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.linux.if_nametoindex
import com.ditchoom.socket.transport.NetworkId
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import platform.posix.system
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reactive integration test for the **live** [LinuxNetworkMonitor] against a controlled network
 * namespace: unlike [NetnsRouteResolutionTest] (which asserts the one-shot [LinuxNetworkMonitor.primaryNetworkId]
 * seed), this constructs the monitor — starting its `AF_NETLINK` `RTMGRP_LINK`/`RTMGRP_IPV4_IFADDR`
 * event loop — and proves its [NetworkMonitor.networkId] `StateFlow` **re-emits** the new primary link
 * when the kernel's routing state changes, which is the monitor's entire reason to exist (it is what
 * drives QUIC auto-migration). Purely seed-based tests never exercise the event loop.
 *
 * The mutation is driven from **inside** the test (`system("ip link set … down")`) so it happens in this
 * namespace, precisely after the initial value is observed — deterministic, no sleep-race. Bringing the
 * lower-metric primary (`eth-a`) down fires a `RTMGRP_LINK` event (waking the loop) and drops its default
 * route, leaving the higher-metric `eth-b` default as the sole primary, so the flow must flip to `eth-b`.
 *
 * Self-skips unless `NETMON_REACT_PRIMARY`/`NETMON_REACT_AFTER` are set, so a plain host `:linuxX64Test`
 * run is a no-op. The `test-harness/netns` runner builds the two-interface namespace and runs this inside it.
 */
class NetnsReactiveChangeTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

    @Test
    fun networkIdReemitsWhenPrimaryLinkGoesDown() {
        val primary = env("NETMON_REACT_PRIMARY") ?: return // not under the reactive netns harness — skip
        val after = env("NETMON_REACT_AFTER") ?: return
        val primaryIdx = if_nametoindex(primary).toLong()
        val afterIdx = if_nametoindex(after).toLong()
        assertTrue(primaryIdx > 0L && afterIdx > 0L, "both '$primary' and '$after' must exist in the namespace")

        val monitor = LinuxNetworkMonitor()
        try {
            runBlocking {
                // Initial seed: the lower-metric default-route interface is primary.
                val initial =
                    withTimeout(REACT_TIMEOUT_MS) {
                        monitor.networkId.first { it is NetworkId.Link && it.handle == primaryIdx }
                    }
                assertEquals(primaryIdx, (initial as NetworkId.Link).handle, "initial primary must be '$primary'")

                // Drive the change from within this namespace: a RTMGRP_LINK event that also removes
                // '$primary's default route, leaving '$after' as the only default.
                val rc = system("ip link set $primary down")
                assertEquals(0, rc, "failed to bring '$primary' down (system rc=$rc)")

                // The event loop must react and re-emit the new primary — not a stale cached value.
                val flipped =
                    withTimeout(REACT_TIMEOUT_MS) {
                        monitor.networkId.first { it is NetworkId.Link && it.handle == afterIdx }
                    }
                assertEquals(afterIdx, (flipped as NetworkId.Link).handle, "networkId must re-emit '$after' after the link-down")
            }
        } finally {
            monitor.close()
        }
    }

    private companion object {
        // Netlink events are near-instant; this is only a deadlock guard, not a settle poll.
        private const val REACT_TIMEOUT_MS = 5_000L
    }
}
