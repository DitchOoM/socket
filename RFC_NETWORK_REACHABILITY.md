# RFC — Network state: one ladder, one flow, no impossible states

**Status:** Accepted, implementing on `feat/network-state-ladder`. **Breaking by design** — this replaces
`NetworkAvailability` rather than deriving from it. No compatibility shim, no compat view.
**Builds on:** [`RFC_TRANSPORT_FALLBACK.md`](./RFC_TRANSPORT_FALLBACK.md) (`NetworkMonitor`/`networkId`) and
[`RFC_UNIFIED_NETWORK_TEST_HARNESS.md`](./RFC_UNIFIED_NETWORK_TEST_HARNESS.md) §7 (the
recorder→fixture→replay loop this extends).
**Motivated by:** the real-device Android validation of 2026-07-29 (PR #271), which found the contract
under-specified once it was measured against hardware.

> **Amended during implementation.** §3, §3.2, §5 and §7 differ from the originally-proposed shape, and
> §8's open questions are now resolved. The changes remove three families of representable-but-impossible
> state and fix one self-contradiction in the original §3.2. Each is called out in
> **[§9 Amendments](#9-amendments-and-why)** with the reason, so this document describes what ships rather
> than what was first drafted.

## 1. The problems

### 1.1 One boolean, three questions

`NetworkAvailability.AVAILABLE` is documented as *"At least one usable network path exists."* **"Usable"
is never defined**, and the five platform implementations each picked a different bar:

| Platform | What `AVAILABLE` actually meant |
|---|---|
| Android | the default network carries `NET_CAPABILITY_INTERNET` — **not** `NET_CAPABILITY_VALIDATED` |
| Apple | `nw_path_status == satisfied` |
| Linux | **any** non-loopback interface is `IFF_UP` — says nothing about routing |
| JVM | **any** non-loopback interface is up |
| JS (Node) | a non-loopback interface exists |
| JS (browser) | `navigator.onLine` — an adapter exists |

Three concrete defects follow:

1. **Linux/JVM report `AVAILABLE` with no default route.** A container with only `docker0`, or a laptop
   associated to Wi-Fi without a DHCP lease, is reported online. `LinuxNetworkMonitor` *already* resolves
   the default-route interface for `networkId` (netlink `RTM_GETROUTE` via `queryDefaultRoute`) —
   `availability` simply ignores what the file already knows.
2. **Android reports `AVAILABLE` before validation.** Measured on a Realme RMX3933: real Wi-Fi grants
   `INTERNET` ~0.7–1s before `VALIDATED`, on 3/3 reassociations. Behind a captive portal that window
   never closes.
3. **Android ignores `NET_CAPABILITY_NOT_SUSPENDED`.** A suspended cellular link keeps `INTERNET` and
   passes no data. Chromium hit this and fixed it (crbug.com/1120144); we have not.

### 1.2 Two flows, torn reads

`availability` and `networkId` were **separate `StateFlow`s**. Nothing made them coherent: a consumer
could observe `UNAVAILABLE` beside a live `Link(Wifi, 441492361229)`, or the reverse. The capability cache
keys on `networkId` while backoff keyed on `availability`, so they could disagree about what network they
were on.

This is not hypothetical. `NetworkMonitorRecorder.observe()` launched one collector per flow, and the
2026-07-29 device capture emitted them out of order:

```
v1 40948500 NET_ID    Link:Wifi:458672230413
v1 40845500 NET_AVAIL AVAILABLE                 <- earlier timestamp, later in the stream
```

Two flows cannot be sampled atomically. **One value can.**

## 2. The ladder is not our invention

NetworkManager's `NMState` already draws the distinction the platforms disagree about:

| `NMState` | Meaning |
|---|---|
| `CONNECTED_LOCAL` (50) | local connectivity, **no default route to the internet** |
| `CONNECTED_SITE` (60) | **a default route exists**, but the connectivity check did not succeed |
| `CONNECTED_GLOBAL` (70) | global internet connectivity |

and `NMConnectivityState` splits the failure: `NONE` / `PORTAL` / `LIMITED` / `FULL`. Android draws the
same lines with capability bits (`INTERNET`, `VALIDATED`, `CAPTIVE_PORTAL`, `NOT_SUSPENDED`). Two
independent, mature stacks converged on **link → route → internet**, with a *reason* attached to failure.

## 3. The types

One sealed value carrying everything, so identity cannot exist without a network and a network cannot
exist without an identity slot.

```kotlin
/** Everything the monitor knows about the current network, as one atomically-observable value. */
sealed interface NetworkState {
    /** Not yet determined — the monitor has just started and no observation has landed. */
    data object Unknown : NetworkState

    /** No usable link at all. */
    data object Offline : NetworkState

    /**
     * A link is up. The only states that carry identity, so identity needs no exhaustive `when` and
     * "is there a link at all" is one check.
     */
    sealed interface Up : NetworkState {
        val id: NetworkId
    }

    /**
     * A link is up but there is no default route: nothing is reachable off-link.
     * Still genuinely useful — mDNS and multicast (`UdpSocket.bindMulticast`) work here.
     */
    data class LinkLocal(override val id: NetworkId) : Up

    /** A default route exists. Whether traffic reaches the internet is [internet]. */
    data class Routable(override val id: NetworkId, val internet: InternetAccess) : Up
}

/** Whether traffic on a routable network actually reaches the internet. */
sealed interface InternetAccess {
    /**
     * This monitor never observes internet reachability — Apple's `NWPath`, kernel-only Linux, JVM,
     * Node. **Terminal**: unlike [Observed.Pending] it does not resolve, ever. Which one you are looking
     * at is answerable *before* subscribing, via [MonitorCapability.resolution].
     */
    data object Unobserved : InternetAccess

    /**
     * A verdict from a monitor that actually probes. Split from [Unobserved] because "never probes" is a
     * property of the *monitor*, constant for its lifetime, while these change per emission — see §9.1.
     */
    sealed interface Observed : InternetAccess {
        /** The platform confirmed end-to-end reachability (Android `VALIDATED`, NetworkManager `FULL`). */
        data object Confirmed : Observed

        /** A probe is in flight. Transient by construction — the ~0.7–1s Android window. */
        data object Pending : Observed

        /**
         * Routes exist and traffic flows, but the probe could not reach the full internet and no portal
         * is intercepting (NetworkManager `LIMITED`). A peer of [Blocked], not a [BlockReason]: the
         * verdict is about reaching *the probe endpoint*, not **your** destination — see §9.3.
         */
        data object Limited : Observed

        /** The platform determined traffic does not reach the internet, and why. */
        data class Blocked(val reason: BlockReason) : Observed
    }
}

/**
 * Why traffic will not get through. Exactly two cases, because each maps one-to-one onto a prescribed
 * consumer response — a reason with no prescribed response is what `Limited` was promoted out of.
 */
sealed interface BlockReason {
    /** A captive portal is intercepting. Requires **user action**; retrying will not help. */
    data object CaptivePortal : BlockReason

    /** Data is paused on an otherwise-up link (Android `!NOT_SUSPENDED`). **Transient** — wait. */
    data object Suspended : BlockReason
}
```

Identity is readable without a `when`, as a **total** function — `NetworkId.Unidentified` already exists
for "cannot identify", so no nullable appears:

```kotlin
val NetworkState.networkId: NetworkId get() = when (this) {
    is NetworkState.Up -> id
    NetworkState.Unknown, NetworkState.Offline -> NetworkId.Unidentified
}
```

### 3.1 The monitor

```kotlin
interface NetworkMonitor {
    /** The single source of truth. One value, always coherent. */
    val state: StateFlow<NetworkState>

    /** What this monitor can observe at all — read once, at configuration time. */
    val capability: MonitorCapability

    fun close()
}
```

`availability` and `networkId` as separate flows are **gone**. There is one flow.

A consumer watching for a **path change** must key on identity, not on the whole value — the state also
changes when reachability firms up, so the naive reading now has a new failure mode (§9.4):

```kotlin
/** Identity-keyed, deduplicated, connect-time baseline dropped. Defined once, in :network-monitor. */
fun NetworkMonitor.pathChanges(): Flow<NetworkId> =
    state.map { it.networkId }.distinctUntilChanged().drop(1)
```

### 3.2 Capability — the clean fallback, declared up front

`InternetAccess.Unobserved` is only safe because a consumer can ask what a monitor is *capable* of before
relying on it — the same pattern `MonitorMechanism` already established for push-vs-poll, now completed.
The two axes are genuinely orthogonal (every combination is real), so this is a product, not a union:

```kotlin
data class MonitorCapability(
    /** Whether transitions are pushed by the platform or discovered by polling. */
    val mechanism: MonitorMechanism,
    /** Which rungs of §2's ladder this monitor can ever report. */
    val resolution: ReachResolution,
)

sealed interface ReachResolution {
    /** Every `Routable.internet` is [InternetAccess.Observed] — never `Unobserved`. Android; Linux+NM. */
    data object RouteAndInternet : ReachResolution

    /** Distinguishes [LinkLocal] from [Routable]; internet is always `Unobserved`. Apple, kernel Linux, JVM. */
    data object RouteOnly : ReachResolution

    /**
     * Cannot see routes: reports [Offline] or `Routable(_, Unobserved)`, and **never** [LinkLocal].
     * Node, browser. Asserting `LinkLocal` requires route visibility — see §9.2.
     */
    data object LinkOnly : ReachResolution

    /** Observes nothing; asserts a fixed state. `NetworkMonitor.AlwaysAvailable` only. */
    data object Asserted : ReachResolution
}
```

A consumer needing confirmed reachability checks `capability.resolution` **once**, and picks its own
policy otherwise — instead of discovering at runtime that `Unobserved` is all it will ever get. **No
silent degradation.**

Each case is also a **pairing rule** constraining what that monitor may emit, exposed as a function so
the rules are enforced rather than documented:

```kotlin
fun ReachResolution.permits(state: NetworkState): Boolean
```

| `resolution` | permits |
|---|---|
| `RouteAndInternet` | `Unknown`, `Offline`, `LinkLocal`, `Routable(_, Observed)` |
| `RouteOnly` | `Unknown`, `Offline`, `LinkLocal`, `Routable(_, Unobserved)` |
| `LinkOnly` | `Unknown`, `Offline`, `Routable(_, Unobserved)` — **not** `LinkLocal` |
| `Asserted` | anything (one constant value; it never measured) |

`Static` ⇒ no transitions at all. `ScriptedNetworkMonitor` validates a whole timeline against its
declared capability at construction, so an incoherent fixture fails in `commonTest`, under virtual time,
on every platform, with no device.

This also forces `NetworkMonitor.AlwaysAvailable` to stop lying: it becomes
`Routable(Unidentified, Unobserved)` with `MonitorCapability(Static, Asserted)` — it **declares that it
never looked**, so a consumer gating on reachability correctly refuses to trust it, while one that merely
wants to opt out of monitoring still gets `canRouteOffLink == true` and proceeds.

## 4. Platform mapping

| Platform | `resolution` | Source |
|---|---|---|
| Android | `RouteAndInternet` | default-network callback; `INTERNET`/`VALIDATED`/`CAPTIVE_PORTAL`/`NOT_SUSPENDED` |
| Linux + NetworkManager | `RouteAndInternet` | netlink default route + `NMConnectivityState` over D-Bus — **deferred, see §8.2** |
| Linux (kernel only) | `RouteOnly` | `queryDefaultRoute` — **already implemented**, just unused by `availability` |
| Apple | `RouteOnly` | `nw_path_status`; `NWPath` has no validation concept |
| JVM | `RouteOnly` | `NetworkInterface` scan + a packet-free UDP-`connect` route probe |
| JS Node | `LinkOnly` | `os.networkInterfaces()` |
| JS browser | `LinkOnly` | `navigator.onLine` |

Android mapping in full. Order matters where the bits overlap, and it is ordered by **what the consumer
must do**: the two states that say "do not attempt" are decided before the two that say "attempt". A
portal-intercepted network can also be `VALIDATED` on some builds, and a suspended link keeps `INTERNET`,
so reading `VALIDATED` first would report both as `Confirmed` — the §1.1 bug.

| Capabilities on the default network | `NetworkState` |
|---|---|
| no default network | `Offline` |
| default network without `INTERNET` | `LinkLocal(id)` |
| `CAPTIVE_PORTAL` | `Routable(id, Blocked(CaptivePortal))` |
| **not** `NOT_SUSPENDED` (API 28+) | `Routable(id, Blocked(Suspended))` |
| `INTERNET` + `VALIDATED` | `Routable(id, Confirmed)` |
| `INTERNET`, none of the above | `Routable(id, Pending)` |

`NOT_SUSPENDED` is API 28+, and its **absence** is the suspended signal — so below 28 it must default to
`true`, or every pre-28 device reports a permanently suspended link.

Android does **not** produce `Limited`: its analogue of NetworkManager's `LIMITED` is
`NET_CAPABILITY_PARTIAL_CONNECTIVITY`, which is `@SystemApi` and absent from the public SDK at any
`compileSdk`. See §9.3.

## 5. Composable predicates

Consumers should not need an exhaustive `when` for the common questions. All four are total, derived from
the shape rather than enumerated, and asserted as an exhaustive table in `NetworkStateTests`:

```kotlin
/** Worth attempting an off-link connection. Everything routable that is not Blocked. */
val NetworkState.canRouteOffLink: Boolean
    get() = this is NetworkState.Routable && internet !is InternetAccess.Observed.Blocked

/** mDNS / multicast are viable — exactly "there is a link up". */
val NetworkState.supportsLinkLocal: Boolean get() = this is NetworkState.Up

/** A human must intervene (captive portal). Retrying is futile; surface it. */
val NetworkState.needsUserAction: Boolean

/** Expected to resolve on its own: Unknown, Pending, or Blocked(Suspended). */
val NetworkState.isTransient: Boolean
```

| state | `canRouteOffLink` | `supportsLinkLocal` | `needsUserAction` | `isTransient` |
|---|---|---|---|---|
| `Unknown` | ✗ | ✗ | ✗ | **✓** |
| `Offline` | ✗ | ✗ | ✗ | ✗ |
| `LinkLocal(id)` | ✗ | ✓ | ✗ | ✗ |
| `Routable(id, Confirmed)` | ✓ | ✓ | ✗ | ✗ |
| `Routable(id, Pending)` | **✓** | ✓ | ✗ | ✓ |
| `Routable(id, Limited)` | ✓ | ✓ | ✗ | ✗ |
| `Routable(id, Blocked(CaptivePortal))` | ✗ | ✓ | ✓ | ✗ |
| `Routable(id, Blocked(Suspended))` | ✗ | ✓ | ✗ | ✓ |
| `Routable(id, Unobserved)` | ✓ | ✓ | ✗ | ✗ |

`isTransient` is the one that pays for this RFC: today a validation window or a suspended cellular link
is indistinguishable from a genuine network change, so auto-migration reacts to all three identically.
Note the two transient rungs sit on the *same* `networkId` as the state before them, so an
identity-keyed consumer (§3.1) ignores them for free.

`Unknown` is transient so a consumer can distinguish "do not know yet, wait" from `Offline`'s "no
network, act now" — Apple's `NWPathMonitor` and the polling JVM monitor are both briefly `Unknown` after
construction, while Android and Linux seed synchronously and never are.

## 6. What this deliberately breaks

No compatibility shim. Consumers move from two flows to one:

```kotlin
// before
if (monitor.availability.value == AVAILABLE) connect()
val key = monitor.networkId.value
monitor.networkId.drop(1).collect { onPathChange() }

// after
if (monitor.state.value.canRouteOffLink) connect()
val key = monitor.state.value.networkId
monitor.pathChanges().collect { onPathChange() }
```

Behaviour changes that are the §1.1 bug fixes:

- **Linux/JVM stop claiming online with no default route** (now `LinkLocal`).
- **Captive portals and suspended links stop reporting online** (now `Blocked`).
- **`AlwaysAvailable` now declares itself unreliable** via `MonitorCapability(Static, Asserted)`.
- **`minSdk` rises 21 → 23** in `:network-monitor` and root `:socket` (§8.1).

The one judgement call left explicit rather than silent: `Routable(_, Pending)` has
`canRouteOffLink == true`. Attempting during the validation window is right — the alternative stalls
every connection by ~1s on every reassociation — but it is a deliberate choice, not an accident, and it
is now *visible in the type* rather than hidden behind a boolean. It also falls out as the same arm as
`Unobserved` and `Limited`, so it is not a special case in the implementation either.

## 7. Harness — same loop, and simpler than today

The record→fixture→replay loop from RFC_UNIFIED_NETWORK_TEST_HARNESS §7 gets **simpler**: two event
types collapse into one, which removes the §1.2 interleaving defect at the source.

```kotlin
// :socket-testkit — replaces BOTH TraceEvent.NetAvail and TraceEvent.Net
data class Net(override val at: Duration, val state: NetworkState) : TraceEvent

// ...plus the monitor's capability, emitted once, so replay reconstructs a *validating* fake
data class NetCapability(override val at: Duration, val capability: MonitorCapability) : TraceEvent
```

`TraceEvent.atNanos: Long` became **`at: Duration`** across all nine variants (§9.5). The wire form is
unchanged — whole nanoseconds — so every committed fixture still parses.

`v1` line form — space-separated fields, colon-separated sub-fields. `parse(e.toString()) == e` enforced
as for every variant:

```
v1 0 NET_CAP PlatformSignalled RouteAndInternet
v1 13649972336 NET Routable Link:Wifi:462967197709 Confirmed
v1 17206441000 NET Routable Link:Wifi:462967197709 Pending
v1 35429576167 NET Routable Link:Wifi:462967197709 Blocked:CaptivePortal
v1 46677846544 NET LinkLocal Link:Wifi:467262165005
v1 46680825967 NET Offline
```

- `NetworkMonitorRecorder.observe()` collects **one** flow — no concurrent collectors, so the stream is
  monotonic by construction rather than by discipline.
- `NetworkMonitorScript.Transition(at, state)` replaces the `Availability`/`Network` pair. One kind of
  transition now exists, so it is a flat data class, not a single-case sealed hierarchy.
- `ScriptedNetworkMonitor` replays it and reports the script's `MonitorCapability` — having **validated
  every scripted state against it** at construction.
- A trace with no `NET_CAP` line (a QUIC-only capture, or one predating it) replays under
  `weakestCapabilityFor(states)`: the least capable resolution that permits the whole timeline, so replay
  still validates rather than trusting the fixture author.
- `AndroidNetworkMonitorTraceCapture` records it, so a device flap yields a full state timeline.

### 7.1 The payoff: the states we cannot reproduce become testable

The Android validation could **not** reproduce a captive portal on the capture handset — Realme overrides
the connectivity-probe URLs, and both `settings put global captive_portal_*` and `cmd device_config put
connectivity captive_portal_*`, including blackholed URLs, still validated. Cellular `SUSPENDED` is
likewise unreachable with no SIM, and `Limited` has no producer at all until §8.2 lands.

Those are the states that matter most, and a scripted fixture reaches all of them:

```kotlin
val portalThenLogin = networkMonitorScript(
    capability = MonitorCapability(PlatformSignalled, RouteAndInternet),
    initialState = NetworkState.Unknown,
) {
    after(0.seconds)        { state(Routable(wifi, Pending)) }
    after(800.milliseconds) { state(Routable(wifi, Blocked(CaptivePortal))) }
    after(30.seconds)       { state(Routable(wifi, Confirmed)) }   // user logs in
}
```

Consumer behaviour — does auto-migration thrash? does fallback surface the portal? does backoff respect
`isTransient`? — becomes a deterministic `commonTest` on every platform, with no device and no
captive-portal hardware. Hardware capture stays the source of truth for what a platform *actually emits*;
the script covers what it *can* emit, and `permits()` keeps the two honest about each other.

## 8. Open questions — resolved

1. **Raise the Android `minSdk` to 23.** ✅ **Done.** `network-monitor` declared `minSdk = 21` while
   calling `ConnectivityManager.getActiveNetwork()` (API 23) unguarded, so constructing the monitor on API
   21/22 threw `NoSuchMethodError`. The obvious fix — a `SDK_INT < M` branch onto the deprecated
   `activeNetworkInfo` — is **untestable with any infrastructure this repo has**: Robolectric 4.16.1
   rejects `@Config(sdk = 21)` and `sdk = 22` with *"API level N is not available"*, and the emulator
   lanes run 29 and 35. `networkHandle` also needs 23, so below it `Routable(id, …)` could never carry a
   real identity. Raising `minSdk` to 23 deletes the crash and the dead branch together — and root
   `:socket` follows, since `:network-monitor` is an `api` dependency and a lower floor is a
   manifest-merger failure.
2. **NetworkManager as an optional Linux tier.** ⏸ **Deferred to a follow-up.** The §1.1 Linux defect is
   fixed by wiring the already-implemented `queryDefaultRoute` into `state`, which reaches `RouteOnly`;
   NetworkManager only adds the top rung. It is also an unbounded-cost dependency — no D-Bus cinterop
   exists, so it means either a new cinterop in `:socket` (the commonizer hazard that already bit
   `LinuxSockets`) or hand-rolling the D-Bus wire protocol over `AF_UNIX`, plus a
   container/minimal-distro absence path. Crucially, **deferring is not a breaking decision**: because
   `resolution` is resolved at construction and read as a value, upgrading Linux from `RouteOnly` to
   `RouteAndInternet` later is source-compatible. Until then nothing produces
   `InternetAccess.Observed.Limited` outside a fixture.
3. **Apple `requiresConnection`** (`nw_path_status == 3`). ✅ **Done — it is `Routable`, the same rung as
   `satisfied`.** The proposed `Routable(_, Blocked(…))` was doubly wrong. It is *unrepresentable*:
   `permits()` forbids any `InternetAccess.Observed` verdict from a `RouteOnly` monitor, and
   Network.framework has no validation concept to produce one with. And it is *backwards*: the C constant
   is `nw_path_status_satisfiable`, defined in `Network.framework/path.h` as **"the path does not
   currently have a usable route, but a connection attempt will trigger network attachment"** — Swift's
   `requiresConnection` alias, which reads as "unusable", is what misled this entry.

   Captured live on a Mac whose Tailscale VPN declares `OnDemandEnabled` + `NEOnDemandRuleConnect`
   (the configuration this status exists for), from the same `nw_path_monitor` `AppleNetworkMonitor`
   maps:

   ```
   status=1(satisfied)   ifType=1 ifIndex=15 ifName=en0
   status=3(satisfiable) ifType=1 ifIndex=15 ifName=en0   <- online throughout
   status=1(satisfied)   ifType=1 ifIndex=15 ifName=en0
   ```

   Identical `NetworkId` on all three, on a host with a working default route and reachable internet:
   what is unattached is the **tunnel**, not the route. Folding it in with `unsatisfied` therefore
   reported `LinkLocal` — "mDNS only, nothing routes off-link" — about a fully online machine, dropping
   `canRouteOffLink` to `false` for ~0.3–1.5 s on **every** on-demand VPN transition.

   The mapping is also self-defeating in a way `Pending` is not: if "a connection attempt triggers
   attachment", then a consumer that declines to attempt is precisely what prevents the attachment, and
   nothing else resolves the state. So `satisfiable` with an interface is `Routable(id, Unobserved)` —
   the same optimistic call already made for a blind JVM route probe ("must not fail closed") and for
   `ReachResolution.LinkOnly`. With no interface there is nothing to be optimistic about, and it stays
   `Offline`.

   A *new rung* was considered and rejected on the evidence: it would have asserted "no route yet", which
   the capture shows is false, and it would answer all four §5 predicates identically to
   `Routable(id, Unobserved)` — a rung consumers must branch on with no behaviour behind the branch.
4. **Does `LinkLocal` need the multicast scope?** ✅ **No — and adding it would be the wrong seam.**
   `supportsLinkLocal` is a **gate**, not a selector: it answers *whether* link-local traffic is worth
   attempting, never *which* interface to send it on.

   The two are different questions with different cardinality. Identity is one per host — *the* primary
   link, which is all `NetworkId` claims to be — while a multi-homed host joins a group on *every*
   eligible interface. Selection already has its own complete seam in `:socket-udp`,
   `MulticastInterface { Default | ByName | ByIndex }`, reached per-`joinGroup` via
   `MulticastMembership`; and the one real multi-homed mDNS consumer, `webrtc-ice`, does not consult
   `NetworkMonitor` at all — it owns an all-interfaces enumeration seam of its own.

   Worse, the bridge someone would reach for is **unsound**. `NetworkId.Link.handle` is documented
   opaque, and it means it: an OS interface index on Linux (`rtnl` `oif`) and Apple
   (`nw_interface_get_index`), but a `Network.networkHandle` on Android — a 64-bit token unrelated to
   `if_nametoindex` — and `name.hashCode()` on the JVM whenever `NetworkInterface.getIndex()` is
   unavailable. `MulticastInterface.ByIndex(state.networkId.handle.toInt())` compiles on all five
   platforms and silently joins the wrong interface, or none, on two of them. Both KDocs now say so at
   the point of temptation.

   So a multi-homed consumer enumerates interfaces and joins each; the monitor tells it *when*, not
   *where*. If a future consumer genuinely needs "which links are up" as a list, that is a **new
   enumeration API** — plural by construction — not scope bolted onto a singular identity.

## 9. Amendments, and why

Each of these came out of implementing §3 and finding a state the types allowed but no monitor could
produce — or, in §9.2, one the types *required* and no monitor should produce.

### 9.1 `Unobserved` split out of the verdicts (`InternetAccess.Observed`)

`Unobserved` is a property of the **monitor**, constant for its lifetime; `Confirmed`/`Pending`/`Limited`/
`Blocked` change per emission. As peers they created an impossible cross-product with `MonitorCapability`:
a `RouteAndInternet` monitor can never emit `Unobserved`, a `RouteOnly` monitor can emit nothing else. So
a consumer that had *already* checked `capability.resolution` still had to write a branch that was, for
its actual monitor, either always-taken or dead. With the split, that consumer `when`s over `Observed` —
three cases, exhaustive, no dead arm — and `permits()` makes the pairing checkable.

### 9.2 `LinkOnly` must never report `LinkLocal`

The original §3.2 defined `LinkOnly` as reporting "only `Offline`/`LinkLocal`". That is wrong, and it was
the one outright contradiction in the draft: **asserting `LinkLocal` requires route visibility.** "A link
is up but there is no route off it" is a claim only a monitor that can see routes is entitled to make. A
browser knows `navigator.onLine` and nothing more — and browsers route off-link and cannot multicast at
all, so `LinkLocal` is precisely the wrong rung for them. Under the draft, an online browser would have
reported `canRouteOffLink == false` and refused to connect. `LinkOnly` reports `Routable(_, Unobserved)`.

The same contradiction appeared in `AlwaysAvailable`, which the draft gave `(Static, LinkOnly)` *and*
`Routable` — mutually exclusive under either reading. Hence `ReachResolution.Asserted`: it reaches no rung
of the ladder, it asserts one. Kept distinct from `MonitorMechanism.Static` because a `Static` monitor
could be a correct constant (an appliance with one soldered Ethernet port), whereas `Asserted` says the
value was never measured.

### 9.3 `Limited` promoted out of `BlockReason` — and currently has no producer

As a `BlockReason`, `Limited` had **no prescribed consumer response**: not transient, no user action, and
ambiguous for `canRouteOffLink`. As a peer of `Blocked` it makes `Blocked` mean "traffic will not get
through" *uniformly*, so `canRouteOffLink` is `internet !is Blocked` with no per-reason judgement call,
and the two remaining `BlockReason`s map one-to-one onto the two responses. That is the whole reason the
§5 table has no ambiguous cells.

**It ships with no producer.** The promotion was initially justified partly by Android's
`NET_CAPABILITY_PARTIAL_CONNECTIVITY`; that constant is `@SystemApi` and not in the public SDK at any
`compileSdk`, so a library cannot read it without reflecting on a hidden field. With §8.2 deferred, the
only source of `Limited` is a scripted fixture. It is kept anyway, on this RFC's own §7.1 logic: states we
cannot reproduce on hardware are exactly the ones worth modelling and scripting, and the deferred
NetworkManager tier needs somewhere to land.

### 9.4 `pathChanges()` — collapsing to one flow created a new way to be wrong

With two flows, "the network changed" was `networkId.drop(1)`. With one, the whole value changes on
*reachability* transitions too, so the naive `state.drop(1)` sees Android's ~1s `Pending` → `Confirmed`
window on a single Wi-Fi network as a **migration** and tears down a working path. The `distinctUntilChanged`
on identity is what prevents it, and it is defined once in `:network-monitor` rather than in each of
`ReconnectingConnection`, `AutoMigrationWiring` and `../webrtc` — one of which would have got it wrong.

### 9.5 `TraceEvent.atNanos: Long` → `at: Duration`

Not an `Instant`: the field is an **offset** from the recorder's own origin, and virtual time has no
epoch. Under `runTest` the clock is `testScheduler.currentTime` — millis since scheduler start — so an
`Instant` would mean inventing an epoch and baking it into every committed fixture, which would then
churn on every re-record and stop being golden-comparable. `Duration` is what the recorder's clock seam
(`() -> Duration`) already returns, what `NetworkMonitorScript.Transition.at` already is, and what
`TracePathStats.rtt` in the same file already uses. The wire form (`inWholeNanoseconds`) is unchanged.

### 9.6 `NetworkState.Up` — identity without a `when`

Both rungs that carry a link carry a `NetworkId`. Hoisting it into a `sealed interface Up` makes
`supportsLinkLocal` literally `this is Up`, makes `networkId` a two-arm projection, and gives consumers
a single check for "there is a link" instead of enumerating rungs that will grow.
