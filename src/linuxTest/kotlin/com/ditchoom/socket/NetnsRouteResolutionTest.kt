@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.socket.linux.if_nametoindex
import com.ditchoom.socket.transport.NetworkId
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for [LinuxNetworkMonitor] route resolution against a **controlled network
 * namespace** — the real netlink + `/proc/net` route files + `/sys/class/net` paths that unit tests cannot
 * reach (on any normal host netlink is available, so [LinuxNetworkMonitor.primaryNetworkId] resolves
 * via netlink and the `/proc` fallback tier never executes, and the interface/route table is whatever
 * the host happens to have).
 *
 * Self-skips unless `NETMON_EXPECT_IFACE` is set, so a plain `:linuxX64Test` run (host network) is a
 * no-op. The `test-harness/netns` runner builds a namespace with a known interface + default route via
 * `unshare -rnm` (rootless — no `sudo`), then runs this test **inside** it:
 * ```
 * unshare -rnm sh -c 'mount -t sysfs sys /sys; <build iface+route>;
 *   exec env NETMON_EXPECT_IFACE=eth-test NETMON_EXPECT_KIND=Ethernet \
 *     test.kexe --ktest_filter="*NetnsRouteResolutionTest*"'
 * ```
 * so the monitor reads the namespace's real kernel state. Both the netlink path and the forced
 * `/proc`-fallback path are asserted, so each scenario proves both tiers pick the same real interface.
 */
class NetnsRouteResolutionTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

    @Test
    fun primaryNetworkIdResolvesTheControlledNamespaceDefaultRoute() {
        val iface = env("NETMON_EXPECT_IFACE") ?: return // not under the netns harness — skip
        val expectIdx = if_nametoindex(iface).toLong()
        assertTrue(expectIdx > 0L, "harness interface '$iface' must exist in this network namespace")

        // Full path: netlink RTM_GETROUTE (or, in the no-default scenario, netlink → None → the /proc +
        // getifaddrs-scan fallback). This is what a real consumer's primaryNetworkId() actually runs.
        assertResolvesTo(
            LinuxNetworkMonitor.primaryNetworkId(),
            iface,
            expectIdx,
            via = "primaryNetworkId (netlink first)",
        )

        // Fallback tier in isolation: forced /proc/net/{route,ipv6_route} + scan against THIS namespace's
        // real /proc + /sys — the branch a normal host never reaches because netlink always answers.
        assertResolvesTo(
            LinuxNetworkMonitor.primaryNetworkIdFromProcFallback(),
            iface,
            expectIdx,
            via = "primaryNetworkIdFromProcFallback (/proc + scan)",
        )
    }

    @Test
    fun enumerateNetworkInterfacesReportsTheControlledNamespaceInterfaces() {
        val iface = env("NETMON_EXPECT_IFACE") ?: return // not under the netns harness — skip
        val expectIdx = if_nametoindex(iface).toLong()

        // enumerateNetworkInterfaces() (the ICE/WebRTC host-candidate source) reads getifaddrs + the
        // same /sys classification — a real integration path its host-live test can only shape-check.
        // Here the namespace has a KNOWN interface set, so assert the exact fields.
        val interfaces = enumerateNetworkInterfaces()
        val names = interfaces.map { it.name }

        val loopback = interfaces.firstOrNull { it.isLoopback }
        assertTrue(loopback != null, "enumerate must expose the loopback interface; got $names")

        val nif = interfaces.firstOrNull { it.name == iface }
        assertTrue(nif != null, "enumerate must include the harness interface '$iface'; got $names")
        nif!!
        assertEquals(expectIdx, nif.index.value, "'$iface' index must match if_nametoindex")
        assertTrue(nif.isUp, "'$iface' is up in the namespace but enumerate reported it down")
        assertTrue(!nif.isLoopback, "'$iface' must not be flagged loopback")
        assertTrue(nif.addresses.isNotEmpty(), "'$iface' must carry at least one address, had ${nif.addresses}")
        env("NETMON_EXPECT_KIND")?.let { expectKind ->
            assertEquals(expectKind, nif.kind::class.simpleName, "enumerate: classified kind for '$iface'")
        }
    }

    private fun assertResolvesTo(
        id: NetworkId,
        iface: String,
        expectIdx: Long,
        via: String,
    ) {
        assertTrue(id is NetworkId.Link, "$via: expected a NetworkId.Link for '$iface', got $id")
        id as NetworkId.Link
        assertEquals(expectIdx, id.handle, "$via: handle must be '$iface's ifindex")
        env("NETMON_EXPECT_KIND")?.let { expectKind ->
            assertEquals(
                expectKind,
                id.kind::class.simpleName,
                "$via: classified link kind for '$iface' (from /sys/class/net)",
            )
        }
    }
}
