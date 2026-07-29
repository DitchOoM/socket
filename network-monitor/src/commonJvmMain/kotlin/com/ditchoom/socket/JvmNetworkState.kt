package com.ditchoom.socket

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Resolves the current [NetworkState] for the JVM/Android monitors that have no framework-level
 * reachability API — [PollingNetworkMonitor] and the reactive `FfmRoutingSocketNetworkMonitor`.
 *
 * `public` rather than `internal` on purpose: the `jvm21Main` (FFM) compilation is a separate Kotlin
 * module and cannot see this source set's internals, so anything shared with it would otherwise have to
 * be **duplicated** — as `parseDefaultRouteInterface` already was, in three places. A single copy of the
 * route probe is worth one exported function, because the whole point of
 * RFC_NETWORK_REACHABILITY §1.1 is that these monitors were answering the routing question wrongly, and
 * two divergent copies of the fix would be the same bug again.
 *
 * The rungs it can reach are [ReachResolution.RouteOnly]: it resolves link and route, and never probes
 * internet reachability, so a [NetworkState.Routable] always carries [InternetAccess.Unobserved].
 *
 * - **no non-loopback interface is up** → [NetworkState.Offline]
 * - **an interface is up but no default route** → [NetworkState.LinkLocal]. This is the §1.1 fix: a
 *   container with only `docker0`, or a laptop associated to Wi-Fi without a DHCP lease, used to be
 *   reported plainly `AVAILABLE`.
 * - **a default route exists** → [NetworkState.Routable]
 * - **the lookup threw** → [NetworkState.Unknown] (never a silent "offline")
 */
fun resolveJvmNetworkState(): NetworkState =
    try {
        val anyUp =
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.any { !it.isLoopback && it.isUp } == true
        when {
            !anyUp -> NetworkState.Offline
            hasDefaultRoute() -> NetworkState.Routable(currentPrimaryNetworkId(), InternetAccess.Unobserved)
            else -> NetworkState.LinkLocal(currentPrimaryNetworkId())
        }
    } catch (_: Exception) {
        NetworkState.Unknown
    }

/**
 * Whether the kernel has a default route, asked portably and **without sending a single packet**.
 *
 * `connect()` on a UDP socket is a purely local operation: it performs the kernel's route lookup, records
 * the peer, and returns — no datagram leaves the host. So if the lookup succeeds the kernel hands back
 * the source address of the interface it *would* use, and if there is no route it fails with
 * `ENETUNREACH`. That is a genuine routing-table query available on Linux, macOS and Windows alike,
 * where `NetworkInterface` alone can say nothing about routes and `/proc/net/route` is Linux-only.
 *
 * Both families are probed because a v6-only host has no v4 default route and vice versa. The targets are
 * the reserved documentation prefixes (RFC 5737 `192.0.2.0/24`, RFC 3849 `2001:db8::/32`) as **numeric
 * literals**, so no DNS lookup happens and no real host is ever named.
 *
 * On Android this needs `android.permission.INTERNET` to open the socket at all; without it the probe
 * throws and is caught, which degrades this monitor to [NetworkState.LinkLocal] rather than crashing.
 */
private fun hasDefaultRoute(): Boolean = hasRouteTo(PROBE_V4) || hasRouteTo(PROBE_V6)

private fun hasRouteTo(literal: String): Boolean =
    try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(literal), PROBE_PORT))
            val local = socket.localAddress
            local != null && !local.isAnyLocalAddress && !local.isLoopbackAddress
        }
    } catch (_: Exception) {
        false
    }

/** RFC 5737 TEST-NET-1 — reserved for documentation, guaranteed never to be a real destination. */
private const val PROBE_V4 = "192.0.2.1"

/** RFC 3849 documentation prefix — the v6 twin of [PROBE_V4]. */
private const val PROBE_V6 = "2001:db8::1"

/** Discard port. Nothing is ever sent to it; only the route lookup matters. */
private const val PROBE_PORT = 9
