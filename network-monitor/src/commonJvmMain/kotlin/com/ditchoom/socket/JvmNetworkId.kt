package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.io.File
import java.net.NetworkInterface

/**
 * Best-effort primary-link identity for the desktop-JVM monitors ([PollingNetworkMonitor] and the
 * reactive `FfmRoutingSocketNetworkMonitor`), derived from the same [NetworkInterface] scan they
 * already run for availability — so it reuses the hard part (change detection) and only keeps the
 * interface identity the availability check was throwing away.
 *
 * Mirrors the shape Apple's `appleNetworkId` produces (primary interface → [NetworkId.Link]): the OS
 * interface index is the stable per-link [NetworkId.Link.handle] that the capability-cache scope and
 * QUIC auto-migration key on. The name is kept only as a diagnostic [NetworkKind.Other] label — a raw
 * interface scan cannot reliably tell Wi-Fi from Ethernet (macOS `en0` is Wi-Fi), and the index alone
 * is what drives migration on a link change, so we deliberately do not guess the kind. Apple keeps its
 * semantic kind because `NWPathMonitor` reports the link *type* directly; the raw-scan monitors can't.
 *
 * **Primary selection matches the native `LinuxNetworkMonitor`:** on Linux we prefer the *default-route*
 * interface (`/proc/net/route`, mirrored by [parseDefaultRouteInterface]) — the link that actually
 * carries new connections, so a host with bridges/containers up (`docker0`, `br-*`) still keys on the
 * real uplink instead of a lower-indexed virtual device. Elsewhere, or when the route table is
 * unavailable, "primary" falls back to the up, non-loopback, non-virtual interface with the lowest OS
 * index — unambiguous in the common single-active-interface case (laptop on Wi-Fi, or on Ethernet).
 * Returns [NetworkId.Unidentified] when nothing qualifies or the lookup throws.
 */
internal fun currentPrimaryNetworkId(): NetworkId =
    try {
        val primary =
            routeAwarePrimaryInterface()
                ?: firstUpNonLoopbackLowestIndex()
        if (primary == null) NetworkId.Unidentified else linkFor(primary)
    } catch (_: Exception) {
        NetworkId.Unidentified
    }

/** The default-route interface (Linux only), resolved to an up, non-loopback [NetworkInterface], or null. */
private fun routeAwarePrimaryInterface(): NetworkInterface? {
    if (!IS_LINUX) return null
    val name = defaultRouteInterfaceName() ?: return null
    return runCatching { NetworkInterface.getByName(name) }
        .getOrNull()
        ?.takeIf { !it.isLoopback && it.isUp }
}

/** Up, non-loopback, non-virtual interface with the lowest OS index — the portable fallback. */
private fun firstUpNonLoopbackLowestIndex(): NetworkInterface? =
    NetworkInterface
        .getNetworkInterfaces()
        ?.asSequence()
        ?.filter { !it.isLoopback && it.isUp && !it.isVirtual }
        ?.minByOrNull {
            val i = it.index
            if (i >= 0) i else Int.MAX_VALUE
        }

private fun linkFor(nif: NetworkInterface): NetworkId {
    val idx = nif.index
    val handle = if (idx >= 0) idx.toLong() else nif.name.hashCode().toLong()
    return NetworkId.Link(NetworkKind.Other(nif.name), handle)
}

/** Reads `/proc/net/route` and returns its default-route interface name, or null if unreadable/absent. */
private fun defaultRouteInterfaceName(): String? =
    runCatching {
        val f = File("/proc/net/route")
        if (f.canRead()) parseDefaultRouteInterface(f.readText()) else null
    }.getOrNull()

/**
 * Parse the default-route interface from `/proc/net/route` text (pure — unit-tested; a byte-for-byte
 * mirror of `LinuxNetworkMonitor.parseDefaultRouteInterface`, duplicated because the `jvm21Main`
 * companion cannot see this `commonJvmMain` internal): the row whose Destination is `00000000`
 * (0.0.0.0/0) with RTF_UP set, choosing the lowest metric when several exist. Columns:
 * `Iface Destination Gateway Flags RefCnt Use Metric Mask ...`.
 */
internal fun parseDefaultRouteInterface(routeTable: String): String? =
    routeTable
        .lineSequence()
        .drop(1) // header row
        .mapNotNull { line ->
            val cols = line.trim().split(ROUTE_WHITESPACE)
            if (cols.size < 8) return@mapNotNull null
            val flags = cols[3].toIntOrNull(16) ?: 0
            if (cols[1] != "00000000" || (flags and RTF_UP_FLAG) == 0) return@mapNotNull null
            cols[0] to (cols[6].toIntOrNull() ?: Int.MAX_VALUE)
        }.minByOrNull { it.second }
        ?.first

private val IS_LINUX: Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("linux")
private val ROUTE_WHITESPACE = Regex("""\s+""")
private const val RTF_UP_FLAG = 0x0001
