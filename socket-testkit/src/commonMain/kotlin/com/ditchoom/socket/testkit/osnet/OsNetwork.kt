package com.ditchoom.socket.testkit.osnet

import com.ditchoom.socket.NetworkInterfaceInfo
import com.ditchoom.socket.NetworkState

/**
 * What the **operating system** says about the device's network, beside the
 * [NetworkState][com.ditchoom.socket.NetworkState] ladder.
 *
 * The ladder answers *how far traffic reaches* and is deliberately narrow: it carries one rung, one
 * identity, and nothing about the radio or the addresses underneath. A walk recording only the ladder
 * therefore renders every one of "airplane mode", "no SIM", "registered on a roaming carrier with no
 * data bearer" and "in a basement" as the single value `Offline`, and cannot say whether the device
 * had IPv6 at all. These are the facts that tell those apart.
 *
 * Every field is either the platform's answer or its own typed absence — never a blank, a zero or a
 * guess. `NotReported` means *this platform will not say*, which is itself a finding: iOS exposes no
 * registration, roaming or data state at all, so an iPhone's record says so rather than implying the
 * radio was idle.
 */
public data class OsNetworkFacts(
    /** The ladder rung and identity the [NetworkMonitor][com.ditchoom.socket.NetworkMonitor] published. */
    val state: NetworkState,
    /** The cellular radio, as far as the platform will say. */
    val cellular: CellularStatus,
    /** Every link the OS has up, and the addresses it carries. */
    val links: List<OsLink>,
) {
    /** How far IPv4 reaches on the widest-reaching link that has any — [AddressScope.Absent] when none does. */
    val v4: AddressScope get() = links.maxOfOrNull { it.v4 } ?: AddressScope.Absent

    /** How far IPv6 reaches on the widest-reaching link that has any — the answer to "does this device have IPv6". */
    val v6: AddressScope get() = links.maxOfOrNull { it.v6 } ?: AddressScope.Absent

    /**
     * The one line these facts are recorded as, in both the probe log (`OS-NET <line>`) and the
     * replay trace (`OS_NET <line>`) — one renderer, so the two records can never disagree.
     *
     * Every field is a space-free `key=value`, so an analyzer reads them by name rather than by
     * position and a field added later cannot shift an older one. [v4] and [v6] are derived from
     * [links] and are carried anyway: they are the summary a reader scans for, and a decoder
     * recomputes them rather than trusting them.
     */
    val line: String get() = encodeOsNetworkFacts(this)
}

/**
 * How far a family's addresses reach on one link.
 *
 * Ordered widest-last, so the scope of a device with several links is `max` over them. An enum
 * rather than a sealed hierarchy because no case carries anything: the reach *is* the whole value,
 * and the ordering is the operation every consumer wants.
 */
public enum class AddressScope {
    /** The link carries no address of this family. */
    Absent,

    /** Only a link-local address — `169.254.0.0/16`, or `fe80::/10`. Nothing routes off the link. */
    LinkLocal,

    /** A private / unique-local address — RFC 1918, RFC 6598 CGNAT, or `fc00::/7`. Routes, behind a translator. */
    SiteLocal,

    /** A globally-routable address. */
    Global,
}

/**
 * One link the OS has up, and the numeric addresses assigned to it.
 *
 * Built from [com.ditchoom.socket.enumerateNetworkInterfaces] rather than from the monitor's
 * [NetworkId][com.ditchoom.socket.transport.NetworkId], because a `NetworkId.Link.handle` is **not**
 * an interface index on every platform (it is a `Network.networkHandle` on Android), so there is no
 * portable way to ask "which of these is the default link". Recording them all answers the question
 * the walk actually asks — what address families this device has anywhere — without that unsound step.
 *
 * [addresses] are numeric literals with any `%zone` suffix stripped: the zone names the interface,
 * which [name] already does.
 */
public data class OsLink(
    val name: String,
    val addresses: List<String>,
) {
    /** How far IPv4 reaches on this link. */
    val v4: AddressScope get() = addresses.filter { !it.contains(':') }.maxOfOrNull(::ipv4Scope) ?: AddressScope.Absent

    /** How far IPv6 reaches on this link. */
    val v6: AddressScope get() = addresses.filter { it.contains(':') }.maxOfOrNull(::ipv6Scope) ?: AddressScope.Absent
}

/**
 * The links of [interfaces] that are up and not loopback, as [OsLink]s.
 *
 * Down and loopback interfaces are dropped here rather than recorded and filtered later: a link that
 * is down carries no traffic, and `lo` carries the same two addresses on every device in the world.
 */
public fun osLinksFrom(interfaces: List<NetworkInterfaceInfo>): List<OsLink> =
    interfaces
        .filter { it.isUp && !it.isLoopback }
        .map { info -> OsLink(info.name, info.addresses.map { it.substringBefore('%') }) }

/**
 * What the platform says about the cellular radio.
 *
 * Two cases, because "this device has no cellular radio to describe" and "it has one and here is what
 * the platform would say about it" are different answers, and only the second one makes an
 * all-[NotReported] reading meaningful.
 */
public sealed interface CellularStatus {
    /** No cellular radio, or a platform that exposes nothing about one at all (desktop JVM, Node, a Wi-Fi-only tablet). */
    public data object NotReported : CellularStatus

    /** The platform answered. Each field carries its answer or its own typed absence. */
    public data class Reported(
        val sim: SimState,
        val registration: CellularRegistration,
        val data: CellularData,
        val roaming: CellularRoaming,
        val bearer: CellularBearer,
    ) : CellularStatus
}

/** Whether there is a usable subscription in the device. */
public enum class SimState {
    /** The platform will not say (no permission, or no such API). */
    NotReported,

    /** No SIM present. */
    Absent,

    /** A SIM is present but locked (PIN, PUK, network lock). */
    Locked,

    /** A SIM is present and usable. */
    Ready,
}

/** Whether the radio is registered on a network — Android's `ServiceState.getState()`. */
public enum class CellularRegistration {
    NotReported,

    /** Registered, normal service. */
    InService,

    /** Searching, or denied: no normal service. */
    OutOfService,

    /** Registered for emergency calls only. */
    EmergencyOnly,

    /** The radio is off — airplane mode, or powered down. */
    PowerOff,
}

/**
 * Whether a data bearer is actually up — Android's `TelephonyManager.getDataState()`.
 *
 * Distinct from [CellularRegistration] on purpose: the walk this record exists for was lost to a
 * phone that was `InService` on LTE and `Disconnected` for data, because data roaming was off. With
 * only the ladder, that state is indistinguishable from airplane mode.
 */
public enum class CellularData {
    NotReported,
    Disconnected,
    Connecting,
    Connected,

    /** Attached, but the bearer is paused (a voice call on a single-radio device). */
    Suspended,
}

/** Whether the serving network is the subscription's home network. */
public enum class CellularRoaming {
    NotReported,
    Home,
    Roaming,
}

/**
 * The radio access technology carrying data.
 *
 * Sealed rather than an enum because the platforms name far more technologies than are worth
 * branching on: the ones a walk reads are typed, and anything else keeps the platform's own label as
 * diagnostic detail — the same stance [NetworkKind.Other][com.ditchoom.socket.transport.NetworkKind.Other]
 * takes, and for the same reason.
 */
public sealed interface CellularBearer {
    /** The platform will not say (Android without `READ_PHONE_STATE`). */
    public data object NotReported : CellularBearer

    /** The platform answered that there is no data bearer. */
    public data object None : CellularBearer

    public data object Gsm : CellularBearer

    public data object Umts : CellularBearer

    public data object Cdma : CellularBearer

    public data object Lte : CellularBearer

    public data object Nr : CellularBearer

    /** A technology with no typed case here; [raw] is the platform's own label, diagnostic only. */
    public data class Other(
        val raw: String,
    ) : CellularBearer
}

/** Everything the OS will say beyond the ladder — what an [OsNetworkSource] reads. */
public data class OsNetworkReading(
    val cellular: CellularStatus,
    val links: List<OsLink>,
)

/**
 * How an [OsNetworkSource] learns that its reading changed.
 *
 * Read once, at configuration time, and written into the run's `OS-NET-SOURCE` line — the same
 * read-once-declare-the-ceiling idiom [MonitorCapability][com.ditchoom.socket.MonitorCapability] uses
 * for the ladder. It is what tells a later reader whether the absence of an `OS-NET` line for ten
 * minutes means "nothing changed" or "nothing was looked at".
 */
public sealed interface CellularSignal {
    /** The platform pushes a callback when the radio's state changes, so a change is recorded when it happens. */
    public data object Signalled : CellularSignal

    /** The radio can only be read on demand: a change is recorded at the next sample. */
    public data object Sampled : CellularSignal

    /** There is no cellular radio to signal about. */
    public data object NotReported : CellularSignal
}

/**
 * Reads the OS facts the ladder does not model — the one platform-specific seam of this whole record.
 *
 * Deliberately a pure reader with no lifecycle: reactivity belongs to whoever owns the loop (a walk
 * probe already has a monitor subscription and a heartbeat), and a reader with no callbacks is one
 * that a test can substitute with a literal. That is what makes every rung and transition below
 * assertable on any platform with no device.
 *
 * Never throws: a platform that refuses a value returns that value's typed absence.
 */
public interface OsNetworkSource {
    /** How this source learns of a change — declared once, never per reading. */
    public val cellularSignal: CellularSignal

    /** The facts as they stand right now. */
    public fun read(): OsNetworkReading

    public companion object {
        /**
         * A source that adds nothing to the ladder: no cellular radio, no links.
         *
         * The honest reading for a platform with no implementation, and the baseline a test starts
         * from — not an empty stand-in for facts that exist and were not read.
         */
        public val None: OsNetworkSource =
            object : OsNetworkSource {
                override val cellularSignal: CellularSignal = CellularSignal.NotReported

                override fun read(): OsNetworkReading = OsNetworkReading(CellularStatus.NotReported, emptyList())
            }
    }
}

// --- address classification ------------------------------------------------------------------
// Textual, on the numeric literals the platform reported. There is no parsing to a byte form and no
// allocation of one: the scope of an address is decided by its leading fields, which is exactly what
// the registries define it by.

private fun ipv4Scope(address: String): AddressScope {
    val octets = address.split('.')
    if (octets.size != 4) return AddressScope.Absent
    val first = octets[0].toIntOrNull() ?: return AddressScope.Absent
    val second = octets[1].toIntOrNull() ?: return AddressScope.Absent
    return when {
        first == 169 && second == 254 -> AddressScope.LinkLocal
        first == 10 -> AddressScope.SiteLocal
        first == 192 && second == 168 -> AddressScope.SiteLocal
        first == 172 && second in 16..31 -> AddressScope.SiteLocal
        // RFC 6598 carrier-grade NAT: a phone on cellular very often has one of these and no global
        // address at all, so calling it global would misreport the commonest walk configuration.
        first == 100 && second in 64..127 -> AddressScope.SiteLocal
        first == 127 -> AddressScope.Absent
        else -> AddressScope.Global
    }
}

private fun ipv6Scope(address: String): AddressScope {
    val head = address.lowercase()
    return when {
        head.startsWith("fe8") || head.startsWith("fe9") || head.startsWith("fea") || head.startsWith("feb") ->
            AddressScope.LinkLocal
        head.startsWith("fc") || head.startsWith("fd") -> AddressScope.SiteLocal
        head == "::1" -> AddressScope.Absent
        else -> AddressScope.Global
    }
}
