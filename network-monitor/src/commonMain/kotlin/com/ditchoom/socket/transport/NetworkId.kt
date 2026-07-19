package com.ditchoom.socket.transport

/**
 * The kind of network link. The coarse identity every platform observes cheaply — and the axis that
 * decides UDP/QUIC-family fate: the Wi-Fi↔Cellular transition of RFC_TRANSPORT_FALLBACK §6 (a
 * UDP-blocked Wi-Fi vs. an open cellular path).
 *
 * Sealed rather than a flat enum so the two cases that *carry* information can hold it typed:
 * [Vpn] records the link(s) it tunnels over, and [Other] keeps the raw platform label for diagnostics.
 * The concrete links ([Wifi]/[Cellular]/[Ethernet]) stay data objects.
 */
sealed interface NetworkKind {
    data object Wifi : NetworkKind

    data object Cellular : NetworkKind

    data object Ethernet : NetworkKind

    /**
     * A VPN tunnel. [transports] is the set of underlying links it runs over, when the platform exposes
     * them (Android `NetworkCapabilities.underlyingNetworks`, Apple `nw_path` interface enumeration) —
     * **empty** when the platform doesn't tell us (the explicit "unknown underlying" state, never null).
     *
     * Captured because a tunnel's fate follows its underlying link: a VPN over cellular can still ride
     * an open UDP path when the local Wi-Fi blocks it, so `Vpn(over Wi-Fi)` and `Vpn(over cellular)`
     * are *different* networks for the per-network cache scope — and fall out as unequal for free.
     */
    data class Vpn(
        val transports: Set<NetworkKind> = emptySet(),
    ) : NetworkKind

    /**
     * A link kind the platform reported that has no typed mapping here. [raw] is the platform's own
     * label, retained purely as **diagnostic detail** — logged, surfaced, compared for equality, but
     * never branched on (mirrors `ConnectionFailureReason.Unknown(raw)`). Prefer adding a typed case
     * over relying on this.
     */
    data class Other(
        val raw: String,
    ) : NetworkKind
}

/**
 * Typed identity of the network path a connect happens over — the key for the per-network
 * [CapabilityCache] scope (RFC_TRANSPORT_FALLBACK §6/§12).
 *
 * Sealed and exhaustive, with **no bare strings and no nulls**: every state a platform can report is a
 * distinct type the cache `when`s over totally (the same discipline as `ConnectionFailureReason` /
 * `QuicError`). "The platform can't identify the network" is a first-class case ([Unidentified]), not
 * an absent value; the per-link discriminator is a numeric OS [Link.handle], never an interface-name
 * string. The only string in the whole model is [NetworkKind.Other.raw], and it is diagnostic-only.
 *
 * Populated by the platform `NetworkMonitor`:
 * - **Apple** — `nw_interface_get_type()` + `nw_interface_get_index()` → [Link]
 * - **Android** — `ConnectivityManager` transport type + `Network.getNetworkHandle()` → [Link]
 * - **Linux / JVM** — interface kind + `NetworkInterface.getIndex()` → [Link]
 * - **Web** — `navigator.connection.type` (no per-link handle in browsers) → [KindOnly]
 * - none / opted out → [Unidentified]
 */
sealed interface NetworkId {
    /**
     * No cheap network identity (or the caller opted out). The per-network cache scope is disabled:
     * no per-network entry is recorded or read, so QUIC-family "path blocks UDP" learning degrades to
     * per-attempt only (RFC §12). Explicit — never `null`.
     */
    data object Unidentified : NetworkId

    /**
     * Only the link [kind] is observable (browsers via `navigator.connection.type`). Two links of the
     * same kind are indistinguishable, so the per-network scope is coarse — but it still captures the
     * decisive Wi-Fi↔Cellular transition.
     */
    data class KindOnly(
        val kind: NetworkKind,
    ) : NetworkId

    /**
     * Link [kind] plus a stable numeric [handle] distinguishing two links of the same kind (Android
     * `networkHandle`, OS interface index). [handle] is opaque — compared only for equality, never
     * parsed.
     */
    data class Link(
        val kind: NetworkKind,
        val handle: Long,
    ) : NetworkId
}
