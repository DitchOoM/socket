package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId

/**
 * Everything a [NetworkMonitor] knows about the current network, as **one atomically-observable value**.
 *
 * This replaces the `NetworkAvailability` + `networkId` pair. Two `StateFlow`s could never be sampled
 * atomically, so nothing made them coherent: a consumer could observe `UNAVAILABLE` beside a live
 * `Link(Wifi, 441492361229)`, and the capability cache (keyed on identity) could disagree with backoff
 * (keyed on availability) about which network they were on. One value cannot tear
 * (RFC_NETWORK_REACHABILITY §1.2).
 *
 * The rungs are the **link → route → internet** ladder both NetworkManager (`NMState`
 * `CONNECTED_LOCAL`/`CONNECTED_SITE`/`CONNECTED_GLOBAL`) and Android (`NET_CAPABILITY_INTERNET` vs
 * `NET_CAPABILITY_VALIDATED`) independently converged on — not our invention. Which rungs a given
 * monitor can ever report is answerable **before subscribing**, via
 * [MonitorCapability.resolution][MonitorCapability.resolution]; there is no silent degradation.
 *
 * Ask the four questions in §5 ([canRouteOffLink], [supportsLinkLocal], [needsUserAction],
 * [isTransient]) rather than writing an exhaustive `when` — that is what they exist for.
 */
sealed interface NetworkState {
    /** Not yet determined — the monitor has just started and no observation has landed. */
    data object Unknown : NetworkState

    /** No usable link at all. */
    data object Offline : NetworkState

    /**
     * A link is up. The only states that carry identity, so identity needs no exhaustive `when` — a
     * consumer wanting the [NetworkId] reads [NetworkState.networkId], and one asking "is there a link
     * at all" writes `state is Up`.
     */
    sealed interface Up : NetworkState {
        /** Typed identity of this link — [NetworkId.Unidentified] when the platform cannot identify it. */
        val id: NetworkId
    }

    /**
     * A link is up but there is no default route: nothing is reachable off-link.
     *
     * Still genuinely useful, which is why it is a rung and not a failure — mDNS and multicast
     * (`UdpSocket.bindMulticast`) work here. This is the state Linux and JVM used to report as
     * `AVAILABLE` (a container with only `docker0`, or a laptop associated to Wi-Fi with no DHCP lease).
     */
    data class LinkLocal(
        override val id: NetworkId,
    ) : Up

    /**
     * A default route exists. Whether traffic actually reaches the internet is [internet] — and whether
     * this monitor can even tell is [MonitorCapability.resolution].
     */
    data class Routable(
        override val id: NetworkId,
        val internet: InternetAccess,
    ) : Up
}

/**
 * Whether traffic on a [routable][NetworkState.Routable] network actually reaches the internet.
 *
 * Split into [Unobserved] and [Observed] because "this monitor never probes reachability" is a property
 * of the **monitor**, constant for its lifetime, while the [Observed] verdicts change per emission.
 * Keeping them as peers made a whole family of states representable that no monitor can produce — a
 * `RouteAndInternet` monitor can never emit [Unobserved], and a `RouteOnly` monitor can emit nothing
 * else — so a consumer that had already checked [MonitorCapability.resolution] still had to write a
 * branch that was, for its actual monitor, either always-taken or dead. With the split, a consumer that
 * checked for [ReachResolution.RouteAndInternet] `when`s over [Observed]: exhaustive, no dead arm.
 */
sealed interface InternetAccess {
    /**
     * This monitor never observes internet reachability — Apple's `NWPath`, kernel-only Linux, JVM,
     * Node. **Terminal**: unlike [Observed.Pending] it does not resolve, ever. Which one you are
     * looking at is answerable before subscribing, via [MonitorCapability.resolution].
     */
    data object Unobserved : InternetAccess

    /**
     * A verdict from a monitor that actually probes. Every case prescribes exactly one response, which
     * is what makes [isTransient] / [needsUserAction] / [canRouteOffLink] total rather than judgement
     * calls.
     */
    sealed interface Observed : InternetAccess {
        /** The platform confirmed end-to-end reachability (Android `VALIDATED`, NetworkManager `FULL`). */
        data object Confirmed : Observed

        /**
         * A probe is in flight. **Transient by construction** — measured on a Realme RMX3933, real
         * Wi-Fi grants `INTERNET` ~0.7–1s before `VALIDATED`, on 3/3 reassociations. Behind a captive
         * portal that window never closes, and the platform then reports [Blocked] instead.
         */
        data object Pending : Observed

        /**
         * Routes exist and traffic flows, but the probe could not reach the full internet, and no portal
         * is intercepting — NetworkManager `LIMITED`, Android `NET_CAPABILITY_PARTIAL_CONNECTIVITY`
         * (API 28+).
         *
         * A peer of [Blocked] rather than a [BlockReason]: the probe's verdict is about reaching *the
         * probe endpoint*, not about reaching **your** destination, so a site-local or partially-routed
         * target may well be reachable. [canRouteOffLink] is therefore `true` here — which is only
         * consistent because [Blocked] means "traffic will not get through", uniformly.
         */
        data object Limited : Observed

        /** The platform determined traffic does not reach the internet, and why. */
        data class Blocked(
            val reason: BlockReason,
        ) : Observed
    }
}

/**
 * Why traffic on a routable network will not get through. Exactly two cases, because each maps
 * one-to-one onto a prescribed consumer response — a third reason with no prescribed response is what
 * [InternetAccess.Observed.Limited] was promoted out of this type to avoid.
 */
sealed interface BlockReason {
    /** A captive portal is intercepting. Requires **user action** ([needsUserAction]); retrying will not help. */
    data object CaptivePortal : BlockReason

    /**
     * Data is paused on an otherwise-up link (Android `!NET_CAPABILITY_NOT_SUSPENDED`) — a suspended
     * cellular link keeps `INTERNET` and passes nothing. **Transient** ([isTransient]): wait, do not
     * tear down. Chromium hit this and fixed it (crbug.com/1120144).
     */
    data object Suspended : BlockReason
}

/**
 * What a [NetworkMonitor] can observe **at all** — read once, at configuration time, never per emission.
 *
 * [InternetAccess.Unobserved] is only safe because a consumer can ask this before relying on a monitor,
 * the same pattern [MonitorMechanism] established for push-vs-poll, now completed. The two axes are
 * genuinely orthogonal — every combination is real — so this is a product, not a union.
 *
 * A consumer needing confirmed reachability checks [resolution] **once** and picks its own policy
 * otherwise, instead of discovering at runtime that [InternetAccess.Unobserved] is all it will ever get.
 */
data class MonitorCapability(
    /** Whether transitions are pushed by the platform or discovered by polling. */
    val mechanism: MonitorMechanism,
    /** Which rungs of the link → route → internet ladder this monitor can ever report. */
    val resolution: ReachResolution,
)

/**
 * The highest rung of the link → route → internet ladder a monitor can ever report.
 *
 * Each case is also a **pairing rule** constraining what that monitor may emit. The rules are enforced
 * where they can fail cheapest — [ScriptedNetworkMonitor] validates a whole timeline against its
 * declared capability at construction, so an incoherent fixture fails in `commonTest` under virtual
 * time, on every platform, with no device.
 */
sealed interface ReachResolution {
    /**
     * Reports the full ladder: every [NetworkState.Routable.internet] is an
     * [InternetAccess.Observed] — never [InternetAccess.Unobserved]. Android; Linux with NetworkManager.
     */
    data object RouteAndInternet : ReachResolution

    /**
     * Distinguishes [NetworkState.LinkLocal] from [NetworkState.Routable], but never probes
     * reachability: every [NetworkState.Routable.internet] is [InternetAccess.Unobserved]. Apple
     * (`NWPath` has no validation concept), kernel-only Linux, JVM.
     */
    data object RouteOnly : ReachResolution

    /**
     * Cannot see routes: reports [NetworkState.Offline] or [NetworkState.Routable] with
     * [InternetAccess.Unobserved], and **never** [NetworkState.LinkLocal]. Node
     * (`os.networkInterfaces()`), browser (`navigator.onLine`).
     *
     * It reports the optimistic rung because asserting [NetworkState.LinkLocal] *requires* route
     * visibility — "a link is up but there is no route off it" is a claim only a monitor that can see
     * routes is entitled to make. A monitor that merely knows an adapter exists must not downgrade a
     * working browser to link-local: browsers route off-link and cannot multicast at all, so
     * [NetworkState.LinkLocal] would be precisely the wrong rung for them.
     *
     * Still distinct from [Asserted]: this genuinely observes the link, and its online/offline
     * transitions are real.
     */
    data object LinkOnly : ReachResolution

    /**
     * Observes nothing; **asserts** a fixed state. [NetworkMonitor.AlwaysAvailable] is the only such
     * monitor: it reports `Routable(Unidentified, Unobserved)` forever without looking at the network.
     *
     * Distinct from [LinkOnly], which genuinely observes the link and would be a lie here, and worth
     * keeping distinct from [MonitorMechanism.Static]: a `Static` monitor *could* be a correct constant
     * (an appliance with one soldered Ethernet port), whereas `Asserted` says the value was never
     * measured. A consumer gating on reachability should refuse to trust this.
     */
    data object Asserted : ReachResolution
}

/**
 * Whether a monitor declaring this [ReachResolution] could actually have produced [state] — the pairing
 * rules of [ReachResolution] as a checkable function, so they are enforced rather than documented.
 *
 * [NetworkState.Unknown] and [NetworkState.Offline] are permitted by every resolution: not having
 * observed yet, and having observed no link, are reachable from any rung. [ReachResolution.Asserted]
 * permits any single state — what makes it an assertion is that it never changes
 * ([MonitorMechanism.Static]), not which value it names.
 *
 * Used by [ScriptedNetworkMonitor] to reject an incoherent timeline at construction, and by each
 * platform monitor's own tests to prove its emissions match what it declares.
 */
fun ReachResolution.permits(state: NetworkState): Boolean =
    when (this) {
        ReachResolution.Asserted -> true
        ReachResolution.RouteAndInternet ->
            when (state) {
                NetworkState.Unknown, NetworkState.Offline, is NetworkState.LinkLocal -> true
                is NetworkState.Routable -> state.internet is InternetAccess.Observed
            }
        ReachResolution.RouteOnly ->
            when (state) {
                NetworkState.Unknown, NetworkState.Offline, is NetworkState.LinkLocal -> true
                is NetworkState.Routable -> state.internet == InternetAccess.Unobserved
            }
        ReachResolution.LinkOnly ->
            when (state) {
                NetworkState.Unknown, NetworkState.Offline -> true
                // Asserting LinkLocal *requires* route visibility — you can only say "a link is up but
                // there is no route" if you can see routes. A LinkOnly monitor cannot, so it reports the
                // optimistic rung instead (see the KDoc on ReachResolution.LinkOnly).
                is NetworkState.LinkLocal -> false
                is NetworkState.Routable -> state.internet == InternetAccess.Unobserved
            }
    }

/**
 * Typed identity of the current network, as a **total** function — no `when`, no nullable.
 * [NetworkId.Unidentified] already means "cannot identify", so the states with no link reuse it rather
 * than introducing an absent value.
 */
val NetworkState.networkId: NetworkId
    get() =
        when (this) {
            is NetworkState.Up -> id
            NetworkState.Unknown, NetworkState.Offline -> NetworkId.Unidentified
        }

// --- §5 composable predicates -------------------------------------------------------------------
// Consumers should not need an exhaustive `when` for the common questions. These four are total and
// mutually consistent; each is derived from the shape above rather than enumerated, so a new rung
// cannot silently pick up a wrong answer.

/**
 * Worth attempting an off-link connection.
 *
 * True for every [NetworkState.Routable] whose [InternetAccess] is not
 * [Blocked][InternetAccess.Observed.Blocked] — so [Confirmed][InternetAccess.Observed.Confirmed],
 * [Pending][InternetAccess.Observed.Pending], [Limited][InternetAccess.Observed.Limited] and
 * [Unobserved][InternetAccess.Unobserved] all attempt, and `Blocked` uniformly does not.
 *
 * [Pending][InternetAccess.Observed.Pending] being optimistic is a deliberate judgement call, now
 * visible in the type rather than hidden behind a boolean: attempting during the ~0.7–1s validation
 * window is right, because the alternative stalls every connection by ~1s on every reassociation.
 * [isTransient] separately tells a consumer to *wait* rather than tear down, which is the behaviour
 * that window actually needs.
 */
val NetworkState.canRouteOffLink: Boolean
    get() = this is NetworkState.Routable && internet !is InternetAccess.Observed.Blocked

/**
 * mDNS / multicast are viable — true for [NetworkState.LinkLocal] as well as every
 * [NetworkState.Routable], i.e. exactly "there is a link up".
 */
val NetworkState.supportsLinkLocal: Boolean
    get() = this is NetworkState.Up

/** A human must intervene (a captive portal is intercepting). Retrying is futile; surface it. */
val NetworkState.needsUserAction: Boolean
    get() = this is NetworkState.Routable && internet == InternetAccess.Observed.Blocked(BlockReason.CaptivePortal)

/**
 * Expected to resolve on its own, so **wait** rather than react: the
 * [Pending][InternetAccess.Observed.Pending] validation window, a
 * [Suspended][BlockReason.Suspended] link, or [NetworkState.Unknown] before the first observation lands
 * (which distinguishes "do not know yet" from [NetworkState.Offline]'s "no network, act now" — Apple's
 * `NWPathMonitor` and the polling JVM monitor are both briefly `Unknown` after construction).
 *
 * This is the predicate that pays for the whole RFC: today a validation window, a suspended cellular
 * link and a genuine network change are indistinguishable, so QUIC auto-migration and transport
 * fallback react to all three identically. They should tear down and re-migrate for only one of them.
 */
val NetworkState.isTransient: Boolean
    get() =
        when (this) {
            NetworkState.Unknown -> true
            NetworkState.Offline, is NetworkState.LinkLocal -> false
            is NetworkState.Routable ->
                internet == InternetAccess.Observed.Pending ||
                    internet == InternetAccess.Observed.Blocked(BlockReason.Suspended)
        }
