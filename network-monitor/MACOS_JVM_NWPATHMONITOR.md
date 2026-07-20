# Deferred: macOS-JVM NWPathMonitor backend (scoping notes)

Status: **not started — scoping only.** Own follow-up PR, not part of the Apple K/N
live/reactive validation work.

## What exists today

| Target | Backend | Reactive? | `networkId` kind |
|---|---|---|---|
| Apple K/N (`:socket`) | `AppleNetworkMonitor` → `NWPathMonitor` | yes | **typed**: `Wifi`/`Cellular`/`Ethernet`/`Vpn`/`Other` |
| JVM-on-macOS, JDK 21+ | `RouteNetworkMonitor` → `PF_ROUTE` route socket (FFM) | **yes** | `Other(ifName)` only |
| JVM-on-macOS, JDK 8–20 | `PollingNetworkMonitor` (interface poll) | no | `Other(ifName)` only |

Key point: **JVM-on-macOS is NOT missing reactivity** — `RouteNetworkMonitor` (jvm21,
`FfmRoutingSocketNetworkMonitor.kt`) already wakes on `PF_ROUTE` routing messages and
re-emits. What it lacks is what a route socket structurally can't cheaply give: the
**typed link kind**. Like the Linux/JVM raw scan, it deliberately reports
`NetworkKind.Other(name)` and never guesses Wi-Fi vs. Ethernet vs. Cellular vs. VPN.

So the value of a dylib NWPathMonitor backend is **typed-identity parity with the K/N
Apple monitor**, not reactivity. That is exactly why the native Apple side uses
`NWPathMonitor` (interface *type*) rather than parsing routes.

## Proposed shape

1. **Native shim = the C we already have.** `nw_helpers.h` /`NWHelpers.def` already expose
   `nw_helper_create_path_monitor` / `_set_update_handler` / `_start` / `_cancel` with the
   exact `(status, ifType, ifIndex, ifName, usesTypes)` callback the K/N monitor consumes.
   Build those four functions (plus a thin C→JVM callback trampoline) into a standalone
   **universal `.dylib`** (`lipo` arm64 + x86_64), independent of the cinterop klib.

2. **Loading.**
   - JDK 21+: FFM (`SymbolLookup` + `Linker`) — a downcall to start, an **upcall stub** for
     the path-update callback, marshalled onto a JVM thread that writes the StateFlows.
     Same MR-JAR half as `RouteNetworkMonitor`.
   - JDK < 20: JNI (`System.loadLibrary`) if we still care about the base half; otherwise
     leave JDK < 21 on `PollingNetworkMonitor`.

3. **Selector wiring.** In `defaultJvmNetworkMonitor()` (jvm21), swap the macOS branch from
   `RouteNetworkMonitor()` to the dylib-backed monitor **when the dylib loads**, falling back
   to `RouteNetworkMonitor()` (still reactive, `Other`-kind) when it doesn't — so a
   load/sign failure degrades to today's behavior, never worse.

4. **Mapping reuse.** The C callback fields are identical to the K/N path, so the JVM side
   can reuse the exact `appleNetworkId(...)` mapping logic (port it to JVM, or keep one
   source of truth) → same `NetworkId.Link` typing.

## Real cost is packaging, not Kotlin

- **Ship a native `.dylib` inside a published JVM jar** (`META-INF/native/...`), extract to a
  temp dir at first load, `System.load` the extracted path. Cross-arch via one universal
  binary.
- **Code-signing / notarization**: an unsigned dylib loaded into a hardened-runtime JVM
  process (or one with library-validation) can be killed. Needs a signing story for the
  published artifact.
- **Build wiring**: a new native compile step in the `:network-monitor` (or `:socket`) build
  producing the universal dylib, plus the MR-JAR packaging. This is the bulk of the work.

## Testing story (mirrors this PR)

- **Liveness / first-emit** on real macOS JVM = CI-feasible and hermetic (the analog of
  `AppleNetworkMonitorLiveTests`): load the dylib, assert a first callback resolves a
  **typed** `Link` (this is the differentiator vs. `RouteNetworkMonitor`'s `Other`).
- **Reactive re-emit** = same manual/scripted-driver, CI-skipped constraint as
  `AppleNetworkMonitorReactiveTest` (no netns on macOS).

## Recommendation

Defer. `RouteNetworkMonitor` already delivers the load-bearing property for QUIC migration
and the capability cache — **path-change reactivity + a stable per-link handle** (identity,
which is what those consumers key on). The dylib only upgrades the *kind label* from `Other`
to typed, at the cost of a signed native artifact in the JVM jar. Revisit if/when a consumer
needs authoritative Wi-Fi↔Cellular typing on the JVM (not just the K/N/Android paths).
