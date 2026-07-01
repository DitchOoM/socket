@file:JvmName("JvmNetworkMonitorSelectorKt")
@file:Suppress("unused") // Loaded at runtime via multi-release JAR (META-INF/versions/21)

package com.ditchoom.socket

/**
 * JDK 21+ network-monitor selector (shadows the `jvmMain` version via the
 * multi-release JAR). Picks a reactive FFM routing-socket monitor by host OS:
 *
 * - **Linux**: [NetlinkNetworkMonitor] (`AF_NETLINK` route socket).
 * - **macOS**: [RouteNetworkMonitor] (`PF_ROUTE` route socket).
 * - **Windows / other**: [PollingNetworkMonitor] — no reactive routing socket is
 *   wired yet (a `NotifyAddrChange` seam can replace it later).
 */
internal fun defaultJvmNetworkMonitor(): NetworkMonitor {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("linux") -> NetlinkNetworkMonitor()
        os.contains("mac") || os.contains("darwin") -> RouteNetworkMonitor()
        else -> PollingNetworkMonitor()
    }
}
