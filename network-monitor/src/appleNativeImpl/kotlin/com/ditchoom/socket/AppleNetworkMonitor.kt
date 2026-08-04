@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket

import com.ditchoom.networkmonitor.nw.nm_create_path_monitor
import com.ditchoom.networkmonitor.nw.nm_path_monitor_cancel
import com.ditchoom.networkmonitor.nw.nm_path_monitor_set_update_handler
import com.ditchoom.networkmonitor.nw.nm_path_monitor_start
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apple [NetworkMonitor] backed by `NWPathMonitor` from Network.framework.
 *
 * Event-driven: the OS calls back immediately on any network path change
 * (wifi ↔ cellular, VPN connect/disconnect, airplane mode, etc.). The same callback carries both halves
 * of one [NetworkState] — `nw_path_status_t` for the rung and the path's primary-interface identity
 * (`nw_interface_get_type` + `nw_interface_get_index`) for [NetworkState.Up.id] — so the value published
 * here can never tear (RFC_NETWORK_REACHABILITY §1.2).
 *
 * Its capability is [ReachResolution.RouteOnly]: `NWPath` answers "is this path usable", which is a
 * routing question, and Network.framework has **no validation concept at all** — no captive-portal bit,
 * no probe. So every [NetworkState.Routable] it emits carries [InternetAccess.Unobserved], and a consumer
 * that needs confirmed reachability learns that from [capability] before subscribing rather than by
 * watching an `internet` field that will never move.
 */
class AppleNetworkMonitor : NetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    /**
     * `NWPathMonitor` invokes its update handler on every path change — no interval anywhere — and
     * resolves route-vs-no-route without ever probing the internet.
     */
    override val capability: MonitorCapability =
        MonitorCapability(MonitorMechanism.PlatformSignalled, ReachResolution.RouteOnly)

    private val monitor = nm_create_path_monitor()

    init {
        nm_path_monitor_set_update_handler(monitor) { status, interfaceType, interfaceIndex, interfaceName, usesTypes ->
            // Decode the C enum ONCE, here at the boundary, so nothing downstream branches on a raw Int.
            _state.value =
                appleNetworkState(nwPathStatus(status), interfaceType, interfaceIndex, interfaceName, usesTypes)
        }
        nm_path_monitor_start(monitor)
    }

    override fun close() {
        nm_path_monitor_cancel(monitor)
    }
}

/**
 * `nw_path_status_t` as a sealed value. The C enum crosses the cinterop boundary as a bare [Int], and
 * decoding it exactly once — in the update handler — is what keeps the magic numbers from spreading:
 * [appleNetworkState] then `when`s over a closed set with no `else` arm to get wrong.
 *
 * [Unrecognized] exists because the enum is Apple's to extend, and a future case must not silently land
 * on whatever arm happens to be last. It keeps the [raw] value so a trace says which one appeared.
 */
internal sealed interface NwPathStatus {
    /** 0 — the path object is not valid. */
    data object Invalid : NwPathStatus

    /** 1 — the path can be used to send and receive data. */
    data object Satisfied : NwPathStatus

    /** 2 — `nw_path_status_unsatisfied`: no usable route (interface down, or system policy). */
    data object Unsatisfied : NwPathStatus

    /**
     * 3 — `nw_path_status_satisfiable`: *"The path does not currently have a usable route, but a
     * connection attempt will trigger network attachment"* (`Network.framework/path.h`).
     *
     * Named for the **C** constant, not for Swift's `NWPath.Status.requiresConnection` alias, which
     * reads as "unusable" and is what led RFC §8.3 to fold this in with [Unsatisfied]. Observed
     * behaviour says otherwise — see [appleNetworkState].
     */
    data object Satisfiable : NwPathStatus

    /** A status this build of the library does not know, carried verbatim for diagnostics. */
    data class Unrecognized(
        val raw: Int,
    ) : NwPathStatus
}

/** Decode a raw `nw_path_status_t` from the cinterop callback. The only place these numbers appear. */
internal fun nwPathStatus(raw: Int): NwPathStatus =
    when (raw) {
        0 -> NwPathStatus.Invalid
        1 -> NwPathStatus.Satisfied
        2 -> NwPathStatus.Unsatisfied
        3 -> NwPathStatus.Satisfiable
        else -> NwPathStatus.Unrecognized(raw)
    }

/**
 * Pure mapper from the `nw_path` callback fields to a [NetworkState] — the Apple rung table of
 * RFC_NETWORK_REACHABILITY §4, unit-testable without Network.framework.
 *
 * | [NwPathStatus] | primary interface | Result |
 * |---|---|---|
 * | [Satisfied][NwPathStatus.Satisfied] | any | `Routable(id, Unobserved)` |
 * | [Satisfiable][NwPathStatus.Satisfiable] | present | `Routable(id, Unobserved)` |
 * | [Satisfiable][NwPathStatus.Satisfiable] | none | [NetworkState.Offline] |
 * | [Unsatisfied][NwPathStatus.Unsatisfied] | present | [NetworkState.LinkLocal] |
 * | [Unsatisfied][NwPathStatus.Unsatisfied] | none | [NetworkState.Offline] |
 * | [Invalid][NwPathStatus.Invalid] / [Unrecognized][NwPathStatus.Unrecognized] | any | [NetworkState.Unknown] |
 *
 * `satisfied` *is* the routing answer — "this path can be used to send and receive data" — so it maps to
 * [NetworkState.Routable] and never higher: Network.framework exposes no validation, so [InternetAccess]
 * is always [InternetAccess.Unobserved] and [capability][NetworkMonitor.capability] says so up front.
 *
 * The [NetworkState.LinkLocal] row is why this is [ReachResolution.RouteOnly] rather than
 * [ReachResolution.LinkOnly]: an unsatisfied path that still enumerates an interface is exactly "a link
 * is up, nothing routes off it" — Wi-Fi associated without a DHCP lease — where mDNS and multicast still
 * work. `nw_path_enumerate_interfaces` normally yields nothing on an unsatisfied path, so [NetworkState.Offline]
 * stays the common case; this rung is reached only when the OS does report a link.
 *
 * [Satisfiable][NwPathStatus.Satisfiable] is [NetworkState.Routable] — the **same rung as
 * [Satisfied][NwPathStatus.Satisfied]** — because that is what it was measured to be (RFC §8.3, now
 * closed). An on-demand VPN (`NEOnDemandRuleConnect`) puts the path here whenever the tunnel is not
 * attached, and a capture on a Mac with Tailscale on-demand shows the primary interface is a **fully
 * routed Wi-Fi link with working internet** — `en0`, byte-identical [NetworkId] to the `satisfied`
 * emissions either side of it. What is unattached is the tunnel, not the route. Folding it in with
 * [Unsatisfied][NwPathStatus.Unsatisfied] therefore reported [NetworkState.LinkLocal] — "mDNS only, no
 * route off-link" — on a machine that was online the whole time, dropping
 * [canRouteOffLink] to `false` for the duration of every on-demand transition.
 *
 * It is also self-defeating: the header defines this status as *"a connection attempt will trigger
 * network attachment"*, so a consumer that declines to attempt is exactly what prevents the attachment,
 * and — unlike [InternetAccess.Observed.Pending] — nothing resolves it on its own. That makes the
 * optimistic rung the same call already made for a blind JVM route probe and for
 * [ReachResolution.LinkOnly]. With no interface at all there is nothing to be optimistic *about*, so
 * that stays [NetworkState.Offline].
 *
 * An [Unrecognized][NwPathStatus.Unrecognized] status is [NetworkState.Unknown] rather than any rung: we
 * do not know what it means, and `Unknown` is exactly "do not know yet" (and [isTransient], so a consumer
 * waits rather than tearing down).
 */
internal fun appleNetworkState(
    status: NwPathStatus,
    interfaceType: Int,
    interfaceIndex: UInt,
    interfaceName: String?,
    usesTypes: Int,
): NetworkState {
    val id = appleNetworkId(interfaceType, interfaceIndex, interfaceName, usesTypes)
    return when (status) {
        NwPathStatus.Satisfied -> NetworkState.Routable(id, InternetAccess.Unobserved)
        NwPathStatus.Satisfiable ->
            if (interfaceIndex == 0u) NetworkState.Offline else NetworkState.Routable(id, InternetAccess.Unobserved)
        NwPathStatus.Unsatisfied ->
            if (interfaceIndex == 0u) NetworkState.Offline else NetworkState.LinkLocal(id)
        NwPathStatus.Invalid, is NwPathStatus.Unrecognized -> NetworkState.Unknown
    }
}

/**
 * Pure mapper from the `nw_path` callback's interface fields to a typed [NetworkId] (unit-tested without
 * Network.framework). A path with no interface (`interfaceIndex == 0`) is [NetworkId.Unidentified];
 * otherwise the primary interface becomes a [NetworkId.Link] keyed on the OS interface index.
 *
 * Deliberately independent of `nw_path_status`: which rung of the ladder a path sits on is
 * [appleNetworkState]'s question, and an unsatisfied path that still names an interface has a perfectly
 * good identity — [NetworkState.LinkLocal] needs one.
 *
 * `nw_interface_type_other` (0) is where VPN tunnels surface — Network.framework has no explicit
 * VPN interface type, so a tunnel-style BSD name (`utun*`/`ipsec*`/`ppp*`/`tun*`/`tap*`) is mapped
 * to [NetworkKind.Vpn] carrying the underlying links from the path-wide uses-interface-type bits
 * ([usesTypes]: 1=wifi, 2=cellular, 4=wired) — `Vpn(over Wi-Fi)` and `Vpn(over cellular)` are
 * different networks for the cache scope. Any other unmapped type keeps its raw name as the
 * diagnostic-only [NetworkKind.Other].
 */
internal fun appleNetworkId(
    interfaceType: Int,
    interfaceIndex: UInt,
    interfaceName: String?,
    usesTypes: Int,
): NetworkId {
    if (interfaceIndex == 0u) return NetworkId.Unidentified
    val kind =
        when (interfaceType) {
            1 -> NetworkKind.Wifi
            2 -> NetworkKind.Cellular
            3 -> NetworkKind.Ethernet
            0 -> {
                val name = interfaceName.orEmpty()
                if (VPN_NAME_PREFIXES.any { name.startsWith(it) }) {
                    NetworkKind.Vpn(
                        buildSet {
                            if (usesTypes and 1 != 0) add(NetworkKind.Wifi)
                            if (usesTypes and 2 != 0) add(NetworkKind.Cellular)
                            if (usesTypes and 4 != 0) add(NetworkKind.Ethernet)
                        },
                    )
                } else {
                    NetworkKind.Other(name.ifEmpty { "other" })
                }
            }
            else -> NetworkKind.Other(interfaceName ?: "type-$interfaceType")
        }
    return NetworkId.Link(kind, interfaceIndex.toLong())
}

private val VPN_NAME_PREFIXES = listOf("utun", "ipsec", "ppp", "tun", "tap")

/** Creates an Apple [NetworkMonitor] backed by `NWPathMonitor`. */
fun NetworkMonitor.Companion.apple(): NetworkMonitor = AppleNetworkMonitor()
