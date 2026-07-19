package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import java.net.NetworkInterface

/**
 * Best-effort primary-link identity for the desktop-JVM monitors ([PollingNetworkMonitor] and the
 * reactive [FfmRoutingSocketNetworkMonitor]), derived from the very same [NetworkInterface] scan they
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
 * "Primary" is the up, non-loopback, non-virtual interface with the lowest OS index — unambiguous in
 * the common single-active-interface case (laptop on Wi-Fi, or on Ethernet). Perfect default-route
 * selection on a multi-homed host would need the routing table; this is the cheap step the [NetworkId]
 * design already calls for ("Linux / JVM — interface kind + NetworkInterface.getIndex() → Link").
 * Returns [NetworkId.Unidentified] when nothing qualifies or the lookup throws.
 */
internal fun currentPrimaryNetworkId(): NetworkId =
    try {
        val primary =
            NetworkInterface
                .getNetworkInterfaces()
                ?.asSequence()
                ?.filter { !it.isLoopback && it.isUp && !it.isVirtual }
                ?.minByOrNull {
                    val i = it.index
                    if (i >= 0) i else Int.MAX_VALUE
                }
        if (primary == null) {
            NetworkId.Unidentified
        } else {
            val idx = primary.index
            val handle = if (idx >= 0) idx.toLong() else primary.name.hashCode().toLong()
            NetworkId.Link(NetworkKind.Other(primary.name), handle)
        }
    } catch (_: Exception) {
        NetworkId.Unidentified
    }
