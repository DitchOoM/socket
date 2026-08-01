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
 * seed), this constructs the monitor — starting its `AF_NETLINK` event loop over the link, address
 * and route multicast groups — and proves its [NetworkMonitor.state] `StateFlow` **re-emits** the new
 * primary link when the kernel's routing state changes, which is the monitor's entire reason to exist
 * (it is what drives QUIC auto-migration). Purely seed-based tests never exercise the event loop.
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
                        monitor.state.first { it.networkId.let { id -> id is NetworkId.Link && id.handle == primaryIdx } }
                    }
                assertEquals(
                    primaryIdx,
                    (initial.networkId as NetworkId.Link).handle,
                    "initial primary must be '$primary'",
                )

                // Drive the change from within this namespace: a RTMGRP_LINK event that also removes
                // '$primary's default route, leaving '$after' as the only default.
                val rc = system("ip link set $primary down")
                assertEquals(0, rc, "failed to bring '$primary' down (system rc=$rc)")

                // The event loop must react and re-emit the new primary — not a stale cached value.
                val flipped =
                    withTimeout(REACT_TIMEOUT_MS) {
                        monitor.state.first { it.networkId.let { id -> id is NetworkId.Link && id.handle == afterIdx } }
                    }
                assertEquals(
                    afterIdx,
                    (flipped.networkId as NetworkId.Link).handle,
                    "state must re-emit '$after' after the link-down",
                )
            }
        } finally {
            monitor.close()
        }
    }

    /**
     * The route-only leg: proves the monitor is subscribed to the `RTMGRP_*_ROUTE` multicast groups,
     * not just link/ifaddr. Every mutation below is `ip route ...` — no link flap, no address change —
     * so the ONLY netlink traffic is route messages; if the bind ever regresses to the pre-rung
     * `RTMGRP_LINK | RTMGRP_IPV4_IFADDR` set, each wait here times out. This is the DHCP shape
     * (RFC_NETWORK_REACHABILITY §1.1): the address is already configured, and the default route —
     * which alone decides [NetworkState.LinkLocal] vs [NetworkState.Routable] — arrives and leaves
     * on its own.
     *
     * Runs in the same two-dummy-link namespace as [networkIdReemitsWhenPrimaryLinkGoesDown]. The
     * `NETMON_REACT_AFTER` link is already up and addressed with no route of its own to add, so it
     * plays the "link with an address but no default route" role; the `finally` restores the harness's
     * default routes so the two tests pass in either execution order.
     */
    @Test
    fun rungFlipsOnRouteOnlyMutations() {
        val primary = env("NETMON_REACT_PRIMARY") ?: return // not under the reactive netns harness — skip
        val after = env("NETMON_REACT_AFTER") ?: return
        val afterIdx = if_nametoindex(after).toLong()
        assertTrue(afterIdx > 0L, "'$after' must exist in the namespace")

        val monitor = LinuxNetworkMonitor()
        try {
            runBlocking {
                // Baseline: the harness always leaves at least one default route (both when this runs
                // first and after the link-down test), so the seed must already be Routable.
                withTimeout(REACT_TIMEOUT_MS) { monitor.state.first { it is NetworkState.Routable } }

                // Route-only mutation #1: drop every default route. '$after' stays up and addressed —
                // a link with nothing reachable off it — so the rung must fall to LinkLocal. Identity is
                // deliberately unasserted here: with no route, the getifaddrs scan picks the first up
                // non-loopback link, and which of the two dummies that is is not this test's contract.
                assertEquals(0, system("ip -4 route flush default"), "failed to flush default routes")
                withTimeout(REACT_TIMEOUT_MS) { monitor.state.first { it is NetworkState.LinkLocal } }

                // Route-only mutation #2 (the DHCP shape): install a default route on the already-
                // addressed '$after'. A gateway-less device route suffices — the monitor reads only
                // RTA_OIF + RTA_PRIORITY, never the gateway.
                assertEquals(0, system("ip route add default dev $after"), "failed to add default route")
                val routable =
                    withTimeout(REACT_TIMEOUT_MS) {
                        monitor.state.first { it is NetworkState.Routable } as NetworkState.Routable
                    }
                assertEquals(afterIdx, (routable.id as NetworkId.Link).handle, "default route names '$after'")
                assertEquals(InternetAccess.Unobserved, routable.internet, "kernel-only monitor never probes")

                // Route-only mutation #3: remove it again — back down the rung.
                assertEquals(0, system("ip route del default dev $after"), "failed to del default route")
                withTimeout(REACT_TIMEOUT_MS) { monitor.state.first { it is NetworkState.LinkLocal } }
            }
        } finally {
            monitor.close()
            // Best-effort restore of the harness's two default routes so the link-down test still sees
            // '$primary' as the lower-metric primary when it runs after this one. Device routes with the
            // harness metrics are equivalent to the originals for the monitor (oif + metric is all it
            // reads). If '$primary' is already down — the link-down test ran first — its restore fails,
            // which is exactly the state that test left behind; rc is intentionally ignored.
            system("ip route add default dev $primary metric 100")
            system("ip route add default dev $after metric 200")
        }
    }

    private companion object {
        // Netlink events are near-instant; this is only a deadlock guard, not a settle poll.
        private const val REACT_TIMEOUT_MS = 5_000L
    }
}
