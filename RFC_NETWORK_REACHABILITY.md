# RFC — Network state: one ladder, one flow, no impossible states

**Status:** Proposed. No code written yet. **Breaking by design** — this replaces `NetworkAvailability`
rather than deriving from it.
**Builds on:** [`RFC_TRANSPORT_FALLBACK.md`](./RFC_TRANSPORT_FALLBACK.md) (`NetworkMonitor`/`networkId`) and
[`RFC_UNIFIED_NETWORK_TEST_HARNESS.md`](./RFC_UNIFIED_NETWORK_TEST_HARNESS.md) §7 (the
recorder→fixture→replay loop this extends).
**Motivated by:** the real-device Android validation of 2026-07-29 (PR #271), which found the contract
under-specified once it was measured against hardware.

## 1. The problems

### 1.1 One boolean, three questions

`NetworkAvailability.AVAILABLE` is documented as *"At least one usable network path exists."* **"Usable"
is never defined**, and the five platform implementations each picked a different bar:

| Platform | What `AVAILABLE` actually means today |
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
   the default-route interface for `networkId` (netlink `RTM_GETROUTE` via `queryDefaultRouteOif`) —
   `availability` simply ignores what the file already knows.
2. **Android reports `AVAILABLE` before validation.** Measured on a Realme RMX3933: real Wi-Fi grants
   `INTERNET` ~0.7–1s before `VALIDATED`, on 3/3 reassociations. Behind a captive portal that window
   never closes.
3. **Android ignores `NET_CAPABILITY_NOT_SUSPENDED`.** A suspended cellular link keeps `INTERNET` and
   passes no data. Chromium hit this and fixed it (crbug.com/1120144); we have not.

### 1.2 Two flows, torn reads

`availability` and `networkId` are **separate `StateFlow`s**. Nothing makes them coherent: a consumer can
observe `UNAVAILABLE` beside a live `Link(Wifi, 441492361229)`, or the reverse. The capability cache keys
on `networkId` while backoff keys on `availability`, so they can disagree about what network they are on.

This is not hypothetical. `NetworkMonitorRecorder.observe()` launches one collector per flow, and the
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
     * A link is up but there is no default route: nothing is reachable off-link.
     * Still genuinely useful — mDNS and multicast (`UdpSocket.bindMulticast`) work here.
     */
    data class LinkLocal(val id: NetworkId) : NetworkState

    /** A default route exists. Whether traffic reaches the internet is [internet]. */
    data class Routable(val id: NetworkId, val internet: InternetAccess) : NetworkState
}

/** Whether traffic on a routable network actually reaches the internet. */
sealed interface InternetAccess {
    /** The platform confirmed end-to-end reachability (Android `VALIDATED`, NetworkManager `FULL`). */
    data object Confirmed : InternetAccess

    /** A probe is in flight. Transient by construction — the ~0.7–1s Android window. */
    data object Pending : InternetAccess

    /** The platform determined traffic does not reach the internet, and why. */
    data class Blocked(val reason: BlockReason) : InternetAccess

    /**
     * This monitor never observes internet reachability — Apple's `NWPath`, kernel-only Linux, Node.
     * Distinct from [Pending]: [Pending] resolves, this does not. Which one you are looking at is
     * answerable *before* subscribing, via [MonitorCapability.resolution].
     */
    data object Unobserved : InternetAccess
}

/** Why a routable network cannot reach the internet. Each case implies a different response. */
sealed interface BlockReason {
    /** A captive portal is intercepting. Requires **user action**; retrying will not help. */
    data object CaptivePortal : BlockReason

    /** Connected, cannot reach the full internet, no portal detected (NetworkManager `LIMITED`). */
    data object Limited : BlockReason

    /** Data is paused on an otherwise-up link (Android `!NOT_SUSPENDED`). **Transient** — wait. */
    data object Suspended : BlockReason
}
```

Identity is still readable without a `when`, as a **total** function — `NetworkId.Unidentified` already
exists for "cannot identify", so no nullable appears:

```kotlin
val NetworkState.networkId: NetworkId get() = when (this) {
    NetworkState.Unknown, NetworkState.Offline -> NetworkId.Unidentified
    is NetworkState.LinkLocal -> id
    is NetworkState.Routable -> id
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

### 3.2 Capability — the clean fallback, declared up front

`InternetAccess.Unobserved` is only safe because a consumer can ask what a monitor is *capable* of before
relying on it — the same pattern `MonitorMechanism` already established for push-vs-poll, now completed.
The two axes are genuinely orthogonal (every combination is real), so this is a product, not a union:

```kotlin
data class MonitorCapability(
    /** Whether transitions are pushed by the platform or discovered by polling. */
    val mechanism: MonitorMechanism,
    /** The highest rung of §2's ladder this monitor can ever report. */
    val resolution: ReachResolution,
)

sealed interface ReachResolution {
    /** Reports the full ladder incl. [InternetAccess.Confirmed]/[Blocked] — Android, Linux+NetworkManager. */
    data object RouteAndInternet : ReachResolution
    /** Distinguishes [LinkLocal] from [Routable]; internet is always [Unobserved] — Apple, Linux kernel, JVM. */
    data object RouteOnly : ReachResolution
    /** Cannot see routes; reports only [Offline]/[LinkLocal] — Node, browser. */
    data object LinkOnly : ReachResolution
}
```

A consumer needing confirmed reachability checks `capability.resolution` **once**, and picks its own
policy otherwise — instead of discovering at runtime that `Unobserved` is all it will ever get. **No
silent degradation.**

This also forces `NetworkMonitor.AlwaysAvailable` to stop lying: it becomes
`Routable(Unidentified, Unobserved)` with `MonitorCapability(Static, LinkOnly)` — a consumer gating on
capability now correctly refuses to trust it, which today it cannot detect.

## 4. Platform mapping

| Platform | `resolution` | Source |
|---|---|---|
| Android | `RouteAndInternet` | default-network callback; `INTERNET`/`VALIDATED`/`CAPTIVE_PORTAL`/`NOT_SUSPENDED` |
| Linux + NetworkManager | `RouteAndInternet` | netlink default route + `NMConnectivityState` over D-Bus |
| Linux (kernel only) | `RouteOnly` | `queryDefaultRouteOif` — **already implemented**, just unused by `availability` |
| Apple | `RouteOnly` | `nw_path_status`; `NWPath` has no validation concept |
| JVM | `RouteOnly` | routing socket / `NetworkInterface` |
| JS Node | `LinkOnly` | `os.networkInterfaces()` |
| JS browser | `LinkOnly` | `navigator.onLine` |

Android mapping in full:

| Capabilities on the default network | `NetworkState` |
|---|---|
| no default network | `Offline` |
| `INTERNET` + `VALIDATED` + `NOT_SUSPENDED` | `Routable(id, Confirmed)` |
| `INTERNET`, no `VALIDATED`, no `CAPTIVE_PORTAL` | `Routable(id, Pending)` |
| `CAPTIVE_PORTAL` | `Routable(id, Blocked(CaptivePortal))` |
| `INTERNET`, `VALIDATED`, **not** `NOT_SUSPENDED` | `Routable(id, Blocked(Suspended))` |
| default network without `INTERNET` | `LinkLocal(id)` |

## 5. Composable predicates

Consumers should not need an exhaustive `when` for the common questions:

```kotlin
/** Worth attempting an off-link connection. Pending/Unobserved are optimistic on purpose. */
val NetworkState.canRouteOffLink: Boolean

/** mDNS / multicast are viable — true for LinkLocal as well as Routable. */
val NetworkState.supportsLinkLocal: Boolean

/** A human must intervene (captive portal). Retrying is futile; surface it. */
val NetworkState.needsUserAction: Boolean

/**
 * Expected to resolve on its own: [InternetAccess.Pending] or [BlockReason.Suspended].
 * QUIC auto-migration and transport fallback should **wait**, not tear down and re-migrate.
 */
val NetworkState.isTransient: Boolean
```

`isTransient` is the one that pays for this RFC: today a validation window or a suspended cellular link
is indistinguishable from a genuine network change, so auto-migration reacts to all three identically.

## 6. What this deliberately breaks

No compatibility shim. Consumers move from two flows to one:

```kotlin
// before
if (monitor.availability.value == AVAILABLE) connect()
val key = monitor.networkId.value

// after
if (monitor.state.value.canRouteOffLink) connect()
val key = monitor.state.value.networkId
```

Behaviour changes that are the §1.1 bug fixes:

- **Linux/JVM stop claiming online with no default route** (now `LinkLocal`).
- **Captive portals and suspended links stop reporting online** (now `Blocked`).
- **`AlwaysAvailable` now declares itself unreliable** via `MonitorCapability`.

The one judgement call left explicit rather than silent: `Routable(_, Pending)` has
`canRouteOffLink == true`. Attempting during the validation window is right — the alternative stalls
every connection by ~1s on every reassociation — but it is a deliberate choice, not an accident, and it
is now *visible in the type* rather than hidden behind a boolean.

## 7. Harness — same loop, and simpler than today

The record→fixture→replay loop from RFC_UNIFIED_NETWORK_TEST_HARNESS §7 gets **simpler**: two event
types collapse into one, which removes the §1.2 interleaving defect at the source.

```kotlin
// :socket-testkit — replaces BOTH TraceEvent.NetAvail and TraceEvent.Net
data class Net(override val atNanos: Long, val state: NetworkState) : TraceEvent
```

`v1` line form — space-separated fields, colon-separated sub-fields, exactly as today's
`NET_ID Link:Wifi:441492361229`. `parse(e.toString()) == e` enforced as for every variant:

```
v1 13649972336 NET Routable  Link:Wifi:462967197709  Confirmed
v1 17206441000 NET Routable  Link:Wifi:462967197709  Pending
v1 35429576167 NET Routable  Link:Wifi:462967197709  Blocked:CaptivePortal
v1 46677846544 NET LinkLocal Link:Wifi:467262165005
v1 46680825967 NET Offline
```

- `NetworkMonitorRecorder.observe()` collects **one** flow — no concurrent collectors, so the stream is
  monotonic by construction.
- `NetworkMonitorScript.Transition.State(at, state)` replaces the `Availability`/`Network` pair.
- `ScriptedNetworkMonitor` replays it and reports a scripted `MonitorCapability`.
- `AndroidNetworkMonitorTraceCapture` records it, so a device flap yields a full state timeline.

### 7.1 The payoff: the states we cannot reproduce become testable

The Android validation could **not** reproduce a captive portal on the capture handset — Realme overrides
the connectivity-probe URLs, and both `settings put global captive_portal_*` and `cmd device_config put
connectivity captive_portal_*`, including blackholed URLs, still validated. Cellular `SUSPENDED` is
likewise unreachable with no SIM.

Those are the states that matter most, and a scripted fixture reaches all of them:

```kotlin
val portalThenLogin = networkMonitorScript(initial = NetworkState.Unknown) {
    after(0.seconds)        { state(Routable(wifi, Pending)) }
    after(800.milliseconds) { state(Routable(wifi, Blocked(CaptivePortal))) }
    after(30.seconds)       { state(Routable(wifi, Confirmed)) }   // user logs in
}
```

Consumer behaviour — does auto-migration thrash? does fallback surface the portal? does backoff respect
`isTransient`? — becomes a deterministic `commonTest` on every platform, with no device and no
captive-portal hardware. Hardware capture stays the source of truth for what a platform *actually emits*;
the script covers what it *can* emit.

## 8. Open questions

1. **Raise the Android `minSdk` to 23.** `network-monitor` declares `minSdk = 21`, but the monitor calls
   `ConnectivityManager.getActiveNetwork()` (API 23) unguarded, so constructing it on API 21/22 throws
   `NoSuchMethodError` today. The obvious fix — a `SDK_INT < M` branch onto the deprecated
   `activeNetworkInfo` — is **untestable with any infrastructure this repo has**: Robolectric 4.16.1
   rejects `@Config(sdk = 21)` and `sdk = 22` with *"API level N is not available"*, and the emulator
   lanes run 29 and 35. `networkHandle` also needs 23, so below it `NetworkId` already degrades to
   `KindOnly` and this RFC's `Routable(id, …)` could never carry a real identity. Writing an untestable
   branch to support a configuration that cannot produce a useful `NetworkState` is the wrong trade;
   raising `minSdk` to 23 deletes the crash and the dead branch together.
2. **NetworkManager as an optional Linux tier** — D-Bus is absent in containers and minimal distros, so
   `resolution` must be resolved at construction (probe for NM, else `RouteOnly`), not assumed.
3. **Apple `requiresConnection`** (`nw_path_status == 3`) currently maps to `UNAVAILABLE`; it may be
   closer to `Routable(_, Blocked(...))` — needs a device check on the paired iPhone.
4. **Does `LinkLocal` need the multicast scope?** mDNS works per-interface; `NetworkId` identifies the
   primary link only. A multi-homed host may need more, which would be a follow-up, not this RFC.
