@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the one [NetworkKind] branch the rootless netns harness cannot reach: **Wi-Fi**.
 * [LinuxNetworkMonitor.classifyLinkKind] maps an interface to [NetworkKind.Wifi] when
 * `/sys/class/net/<iface>/phy80211` (or the legacy `wireless`) entry exists — which `dummy`/`tun`/`tuntap`
 * devices never have. A real (or simulated) 802.11 device is required, so the harness loads the
 * `mac80211_hwsim` kernel module (privileged) to get a `wlanN` device with a real `phy80211`, then runs
 * this against its live `/sys` view.
 *
 * There is intentionally **no JVM counterpart**: the desktop-JVM derivation ([currentPrimaryNetworkId])
 * reports [NetworkKind.Other] and never guesses Wi-Fi, so kind classification is a native-only concern.
 *
 * Self-skips unless `NETMON_WIFI_IFACE` is set, so a plain host `:linuxX64Test` run is a no-op. The
 * `test-harness/netns/run-wifi-classify.sh` runner sets it after `modprobe mac80211_hwsim`; when the
 * module is unavailable (e.g. the WSL2 kernel) that runner skips gracefully and this never executes.
 */
class NetnsWifiClassifyTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

    @Test
    fun classifiesTheSimulatedWirelessInterfaceAsWifi() {
        val iface = env("NETMON_WIFI_IFACE") ?: return // not under the wifi harness — skip

        // Direct classification: the phy80211 /sys entry the simulated wlan carries ⇒ Wifi.
        val kind = LinuxNetworkMonitor.classifyLinkKind(iface)
        assertEquals("Wifi", kind::class.simpleName, "classifyLinkKind('$iface') must be Wifi (has /sys phy80211)")

        // The same classification must flow through the enumerate path (ICE/WebRTC host candidates).
        val nif = enumerateNetworkInterfaces().firstOrNull { it.name == iface }
        assertTrue(nif != null, "enumerate must include the wireless interface '$iface'")
        assertEquals("Wifi", nif!!.kind::class.simpleName, "enumerate: classified kind for '$iface'")
    }
}
