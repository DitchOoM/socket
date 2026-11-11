package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.withContext
import kotlin.jvm.JvmInline

/**
 * An OS network-interface index — the stable per-link handle the kernel assigns each interface. A
 * [value class][JvmInline] so it can't be silently swapped with an unrelated [Int]/[Long] (a route
 * metric, a port, a count) at a call site. Its [value] is the same number a
 * [NetworkId.Link.handle][com.ditchoom.socket.transport.NetworkId.Link.handle] carries, so a gathered
 * candidate can be tied back to the network the [NetworkMonitor] reports as primary.
 */
@JvmInline
value class InterfaceIndex(
    val value: Long,
)

/**
 * A point-in-time snapshot of one local network interface and its assigned IP addresses — the raw
 * material an ICE agent gathers host candidates from (RFC 8445 §5.1.1), and a standalone primitive for
 * a WebRTC / P2P layer built on this library.
 *
 * [kind] is best-effort: [NetworkKind.Other] on the raw-scan platforms (desktop JVM / Android / Node —
 * a bare interface scan cannot tell Wi-Fi from Ethernet), semantically classified on Linux native
 * (from `/sys/class/net`). [addresses] are numeric IPv4/IPv6 literals (never DNS), an IPv6 link-local
 * possibly carrying its `%zone` — the diagnostic-friendly form ICE host-candidate gathering consumes.
 */
data class NetworkInterfaceInfo(
    val name: String,
    val index: InterfaceIndex,
    val kind: NetworkKind,
    val addresses: List<String>,
    val isUp: Boolean,
    val isLoopback: Boolean,
)

/**
 * The host's network interfaces and their assigned IP addresses — a point-in-time snapshot. Contrast
 * the reactive [NetworkMonitor.state] (identity via [NetworkState.networkId]), which tracks only the
 * single *primary* link over time; this returns *every* interface at once.
 *
 * Intended for ICE host-candidate gathering and network diagnostics. Includes down and loopback
 * interfaces (filter with [NetworkInterfaceInfo.isUp] / [NetworkInterfaceInfo.isLoopback] as the use
 * case needs). Returns an empty list on platforms that cannot enumerate interfaces — the browser
 * (JS-in-a-page and Wasm) has no interface list by design — and on any lookup failure.
 *
 * Safe to call from any thread, including the Android main thread or a desktop UI thread: the scan
 * runs on an IO dispatcher, never on the caller's thread.
 */
suspend fun listNetworkInterfaces(): List<NetworkInterfaceInfo> = withContext(blockingWork) { platformNetworkInterfaces() }

/** The interface scan [listNetworkInterfaces] runs, on the calling thread. */
internal expect fun platformNetworkInterfaces(): List<NetworkInterfaceInfo>

/**
 * Enumerate the host's network interfaces on the calling thread. See [listNetworkInterfaces], which this
 * is the blocking predecessor of.
 */
@Deprecated(
    "Blocks the calling thread while it reads the interface table. Call the suspend " +
        "listNetworkInterfaces(), which does the read on an IO dispatcher.",
    ReplaceWith("listNetworkInterfaces()", "com.ditchoom.socket.listNetworkInterfaces"),
)
expect fun enumerateNetworkInterfaces(): List<NetworkInterfaceInfo>
