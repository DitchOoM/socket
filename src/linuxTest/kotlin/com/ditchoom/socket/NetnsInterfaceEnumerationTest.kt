@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.if_nametoindex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `:socket` half of the netns harness: [enumerateNetworkInterfaces] — the ICE/WebRTC
 * host-candidate source — read against a **controlled network namespace** with a known interface set,
 * so the exact fields can be asserted rather than shape-checked as the host-live
 * `NetworkInterfaceEnumerationTest` must.
 *
 * The monitor half of the same namespace (netlink route resolution, `/proc` fallback, Wi-Fi link-kind
 * classification) is `NetnsRouteResolutionTest` / `NetnsWifiClassifyTest` in `:network-monitor`: issue
 * #269 moved `LinuxNetworkMonitor` there and left `enumerateNetworkInterfaces` here, and `expect`/
 * `actual` cannot span modules, so the assertions split with the code. `test-harness/netns` runs both
 * modules' `.kexe` inside the same namespace, which is why both classes gate on the same env vars.
 *
 * Self-skips unless `NETMON_EXPECT_IFACE` (route scenarios) or `NETMON_WIFI_IFACE` (the privileged
 * `mac80211_hwsim` scenario) is set, so a plain `:linuxX64Test` run is a no-op.
 */
class NetnsInterfaceEnumerationTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

    @Test
    fun enumerateNetworkInterfacesReportsTheControlledNamespaceInterfaces() {
        val iface = env("NETMON_EXPECT_IFACE") ?: return // not under the netns harness — skip
        val expectIdx = if_nametoindex(iface).toLong()

        // enumerateNetworkInterfaces() reads getifaddrs + the same /sys classification the monitor uses
        // (LinuxNetworkMonitor.classifyLinkKind, public across the module boundary for exactly this) — a
        // real integration path its host-live test can only shape-check. Here the namespace has a KNOWN
        // interface set, so assert the exact fields.
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

    @Test
    fun enumerateClassifiesTheSimulatedWirelessInterfaceAsWifi() {
        val iface = env("NETMON_WIFI_IFACE") ?: return // not under the wifi harness — skip

        // `:network-monitor`'s NetnsWifiClassifyTest asserts classifyLinkKind('$iface') == Wifi directly;
        // this asserts the same classification survives the enumerate path, which is what an ICE agent
        // actually reads.
        val nif = enumerateNetworkInterfaces().firstOrNull { it.name == iface }
        assertTrue(nif != null, "enumerate must include the wireless interface '$iface'")
        assertEquals("Wifi", nif!!.kind::class.simpleName, "enumerate: classified kind for '$iface'")
    }
}
