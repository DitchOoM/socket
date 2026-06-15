# v6 — Apple (macOS) validation checklist

Everything Apple in the v6 redesign (`redesign/major-api-v6`) has been written
**compile-faithful only** since Phase 1 — it has not been compiled or tested on
an Apple host since then. This file is the turnkey checklist for a Mac session to
catch up that debt. Run it top-to-bottom; each section is independent.

## Why this exists

The dev box is WSL2/Linux. Apple targets (macosX64/Arm64, ios*, tvos*, watchos*)
and the `:socket-quic-nw` Network.framework engine + its `NWQuicHelpers` cinterop
cannot compile or link off-mac. Linux validation only proves **configure +
ktlint**; the actual `compileKotlinMacos*` / `*Test` have never run.

## Setup (once, on the Mac)

1. Checkout: `git checkout redesign/major-api-v6`
2. Buffer dependency: socket pins `buffer = 6.0.0-SNAPSHOT` (mavenLocal). Build it
   on the Mac **from the `v5.6.0` tag + the Phase-0 byte-layer reshape**, NOT
   buffer `main` HEAD (main's #182 codec refactor regresses `Http3Frame`). The v6
   buffer work is on branch `redesign/v6-rel`. Publish:
   `./gradlew -Pversion=6.0.0-SNAPSHOT publishToMavenLocal -x prePublishCheck -x allTests`
   then in socket: `./gradlew --refresh-dependencies ...`
3. JDK 21 toolchain; Xcode + command-line tools for the K/N apple toolchain.

## Phase 1 — root `:socket` apple compile

```bash
./gradlew :socket:compileKotlinMacosArm64 :socket:compileKotlinIosSimulatorArm64
```
Expect: green. This is the byte-trichotomy / `TransportConfig` core compiled for
Apple for the first time since the redesign began.

## Phase 2b.3 — `:socket-quic-nw` (the big one)

This module was extracted on Linux; its Apple engine + cinterop + appleTest have
NEVER compiled. Highest-value check.

```bash
# 1. Engine (appleMain) compiles against the NWQuicHelpers cinterop:
./gradlew :socket-quic-nw:compileKotlinMacosArm64

# 2. Cross-module SPI resolution is clean on macOS:
#    On Linux, `:socket-quic` host-gates apple → no apple variants → -nw's
#    appleMain dep emits a NON-FATAL `e: KMP Dependencies Resolution Failure`.
#    On macOS `:socket-quic` DOES declare apple targets, so this MUST disappear.
#    Watch the configure output for any lingering `e:` resolution error.

# 3. The full moved appleTest suite + cert pipeline (auto-runs generateTestP12):
./gradlew :socket-quic-nw:macosArm64Test
```
Watch for:
- `appleMain` collapse: the 3 engine files now live in `src/appleMain/kotlin`
  (not the old per-target `appleNativeImpl` srcDir). Confirm the `NWQuicHelpers`
  cinterop types (`com.ditchoom.socket.quic.nwhelpers.*`) resolve in `appleMain`
  via cinterop commonization across all apple targets.
- `.def` linkerOpts (`-framework Network/Foundation/Security`) link without the
  `:socket`/BoringSSL dep (NW backend deliberately has none).
- appleTest needs `cert.p12` / `localhost.p12` — `generateTestP12` should run
  automatically via the `(macos|ios|tvos|watchos)\w*Test` dependsOn wiring.
- The `-9808` private-CA cert-acceptance gap (pre-existing, NOT a regression):
  CA-pinning server tests against the self-signed harness cert may still skip /
  fail on NW hardening — see `TestHelpers.shouldSkipQuicHarnessOnSimulator` and
  the issue #81 / #54 notes. Compare against pre-v6 behavior, don't chase it.

Optional booted-simulator coverage (needs a booted device UDID):
```bash
./gradlew :socket-quic-nw:iosSimulatorArm64Test -PiosSimulatorDevice=<udid>
```

## Phase 2b.4/2b.5 — `:socket-quic-default` + http3 (only once those phases land)

`:socket-quic-default` wires `expect val defaultQuicEngine` to `:socket-quic-nw`
on Apple; `:socket-http3` un-darkens. Until 2b.5 lands, `:socket-http3` does NOT
compile (its `withQuic*` callers are dark) — do not attempt http3 apple compile
before then.

```bash
./gradlew :socket-quic-default:compileKotlinMacosArm64   # apple→-nw actual resolves
./gradlew :socket-http3:macosArm64Test                   # after 2b.5
```

## Minor / cosmetic
- `WithQuicConnection.apple.kt` `connectQuicGroup` KDoc still links
  `[withQuicConnection]` — a dangling dokka ref (that wrapper now lives in the
  quiche/default module, not here). Harmless for ktlint/compile; reword if doing
  a dokka pass.

## When green
Update `project_v6_redesign.md` (drop "compile-faithful" for the validated
phases) and flip the per-phase notes from "Apple compile-faithful (no Mac)" to
"Apple validated on macOS @ <commit>".
