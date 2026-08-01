package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
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
 * - **an interface is up and both route probes report [RouteProbeOutcome.NoRoute]** →
 *   [NetworkState.LinkLocal]. This is the §1.1 fix: a container with only `docker0`, or a laptop
 *   associated to Wi-Fi without a DHCP lease, used to be reported plainly `AVAILABLE`.
 * - **either probe reports [RouteProbeOutcome.Routed]** → [NetworkState.Routable]
 * - **a probe was [RouteProbeOutcome.Indeterminate] and neither found a route** →
 *   [NetworkState.Routable] with [InternetAccess.Unobserved]. A monitor that could not see routes is
 *   not entitled to claim [NetworkState.LinkLocal] (§9.2 — the same optimistic-when-blind rule
 *   [ReachResolution.LinkOnly] documents), so a sandboxed-but-routable host keeps the rung `main`
 *   reported (`AVAILABLE`) instead of silently losing [canRouteOffLink] in a major release.
 * - **the interface scan threw** → [NetworkState.Unknown] (never a silent "offline")
 */
fun resolveJvmNetworkState(): NetworkState =
    try {
        val anyUp =
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                ?.any { !it.isLoopback && it.isUp } == true
        // Probes and identity lookup are skipped when no link is up — classifyRouteProbes decides
        // Offline before looking at either, so the placeholders below are never read.
        classifyRouteProbes(
            anyInterfaceUp = anyUp,
            v4 = if (anyUp) probeRoute(PROBE_V4) else RouteProbeOutcome.Indeterminate,
            v6 = if (anyUp) probeRoute(PROBE_V6) else RouteProbeOutcome.Indeterminate,
            id = if (anyUp) currentPrimaryNetworkId() else NetworkId.Unidentified,
        )
    } catch (_: Exception) {
        NetworkState.Unknown
    }

/**
 * Outcome of a single address-family route probe — three cases, not a boolean, because two very
 * different failures used to collapse into `false`: "the kernel looked and found no route" and "the
 * probe never ran". Only the first entitles this monitor to claim [NetworkState.LinkLocal]
 * (RFC_NETWORK_REACHABILITY §9.2 — that claim requires route visibility), and conflating them silently
 * downgraded sandboxed-but-routable hosts to `canRouteOffLink == false`. Same discipline as
 * `QuicError`: model the failure modes exhaustively instead of collapsing them into a boolean.
 */
internal enum class RouteProbeOutcome {
    /** The kernel's route lookup succeeded and bound a real (non-wildcard, non-loopback) source address. */
    Routed,

    /** The route lookup ran and found no route off-link (`ENETUNREACH`, or a still-wildcard source). */
    NoRoute,

    /**
     * The probe could not run at all — socket creation/bind denied by a `SecurityManager`, a sandbox
     * blocking UDP, or Android missing `android.permission.INTERNET`. Says **nothing** about routes.
     */
    Indeterminate,
}

/**
 * Combines the interface scan with the v4 + v6 probe outcomes into a [NetworkState]. Pure, so the whole
 * 2×3×3 matrix is unit-tested without I/O (`RouteProbeClassificationTest`).
 *
 * Any [Routed][RouteProbeOutcome.Routed] wins: one family with a default route is a routable host. Both
 * [NoRoute][RouteProbeOutcome.NoRoute] is the only combination that **earns** [NetworkState.LinkLocal] —
 * the kernel was actually asked, once per family, and said no both times. With an
 * [Indeterminate][RouteProbeOutcome.Indeterminate] and no `Routed`, the monitor could not see routes and
 * so may not assert "link up but no route off it" (§9.2, [ReachResolution.LinkOnly]'s rule); it reports
 * `Routable(id, Unobserved)` instead — legal under the declared [ReachResolution.RouteOnly], see
 * [ReachResolution.permits].
 */
internal fun classifyRouteProbes(
    anyInterfaceUp: Boolean,
    v4: RouteProbeOutcome,
    v6: RouteProbeOutcome,
    id: NetworkId,
): NetworkState =
    when {
        !anyInterfaceUp -> NetworkState.Offline
        v4 == RouteProbeOutcome.Routed || v6 == RouteProbeOutcome.Routed ->
            NetworkState.Routable(id, InternetAccess.Unobserved)
        v4 == RouteProbeOutcome.NoRoute && v6 == RouteProbeOutcome.NoRoute -> NetworkState.LinkLocal(id)
        else -> NetworkState.Routable(id, InternetAccess.Unobserved)
    }

/**
 * Whether the kernel has a route to [literal], asked portably and **without sending a single packet**.
 *
 * `connect()` on a UDP socket is a purely local operation: it performs the kernel's route lookup, records
 * the peer, and returns — no datagram leaves the host. So if the lookup succeeds the kernel hands back
 * the source address of the interface it *would* use, and if there is no route it fails with
 * `ENETUNREACH`. That is a genuine routing-table query available on Linux, macOS and Windows alike,
 * where `NetworkInterface` alone can say nothing about routes and `/proc/net/route` is Linux-only.
 *
 * The targets are the reserved documentation prefixes (RFC 5737 `192.0.2.0/24`, RFC 3849 `2001:db8::/32`)
 * as **numeric literals**, so no DNS lookup happens and no real host is ever named. Failures are
 * classified, not collapsed — see [RouteProbeOutcome].
 */
private fun probeRoute(literal: String): RouteProbeOutcome {
    val socket =
        try {
            DatagramSocket()
        } catch (_: Exception) {
            // Creation/bind never reached the route lookup, so its failure — SecurityException included;
            // Android surfaces a missing android.permission.INTERNET here — says nothing about routes.
            return RouteProbeOutcome.Indeterminate
        }
    return socket.use {
        try {
            it.connect(InetSocketAddress(InetAddress.getByName(literal), PROBE_PORT))
            val local = it.localAddress
            // The isAnyLocalAddress check is LOAD-BEARING, not belt-and-braces: under the legacy
            // PlainDatagramSocketImpl (Android libcore; JVM with -Djdk.net.usePlainDatagramSocketImpl)
            // connectInternal swallows the SocketException and marks the socket ST_CONNECTED_NO_IMPL
            // instead of throwing, so an unroutable target returns from connect() normally and only the
            // still-wildcard local address reveals that the kernel never found a route.
            if (local != null && !local.isAnyLocalAddress && !local.isLoopbackAddress) {
                RouteProbeOutcome.Routed
            } else {
                RouteProbeOutcome.NoRoute
            }
        } catch (_: SecurityException) {
            // checkConnect denied the probe before the kernel was asked — indistinguishable from a
            // socket that never opened, so the same verdict: the probe could not run.
            RouteProbeOutcome.Indeterminate
        } catch (_: Exception) {
            // connect() itself threw: the kernel's route lookup ran and failed (ENETUNREACH).
            RouteProbeOutcome.NoRoute
        }
    }
}

/** RFC 5737 TEST-NET-1 — reserved for documentation, guaranteed never to be a real destination. */
private const val PROBE_V4 = "192.0.2.1"

/** RFC 3849 documentation prefix — the v6 twin of [PROBE_V4]. */
private const val PROBE_V6 = "2001:db8::1"

/** Discard port. Nothing is ever sent to it; only the route lookup matters. */
private const val PROBE_PORT = 9
