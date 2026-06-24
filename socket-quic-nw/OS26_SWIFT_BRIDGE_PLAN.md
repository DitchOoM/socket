# Apple OS-26 Swift Network bridge — scoping (issue #173)

**Status:** scoped; capability spike PROVEN (2026-06-24, macOS 26.5.1). **Sequencing step 1 (build
plumbing) DONE + generalized to all 11 Apple targets (2026-06-24)** — see "Step 1 status" below.

## Step 2 status — real @objc bridge, datagram slice GREEN (macosArm64)

The trivial shim is replaced by the REAL `@objc` bridge over the OS-26 `NetworkConnection<QUIC>` Swift
API. **Client `connect` + server `listen` + datagram round-trip validated end-to-end through K/N**
(`NWQuic26BridgeDatagramTest.clientServerDatagramRoundTrip`, macosArm64; full `:socket-quic-nw:macosArm64Test`
52/52 green; all 11 targets compile + `linkDebugTest` clean). The `@objc` surface (settled with the user
— see below) is the full sketch in "Target API surface": `connect`/`listen` on `NWQuic26Bridge`;
`NWQuic26Conn` with `onStateChanged`/`sendDatagram`/`onDatagram`/`maxDatagramSize`/`openStream`/
`onInboundStream`/`close` + pin-diagnostic getters; `NWQuic26Stream` (`send`/`receive`/`reset`); `NWQuic26Listener`.

DESIGN FORKS settled with the user (all the recommended option): (A) inbound datagrams/streams use
**push handlers backed by Swift serving Tasks**, Kotlin re-wraps into Channels; caller-initiated ops use
one-shot completion blocks. (B) the shim **imports the p12 itself** (`SecPKCS12Import` +
`kSecImportToMemoryOnly`) — no `sec_identity_t` marshaled across `@objc`. (C) cert pinning passes the
expected SHA-256 hashes in and **compares in Swift** (CryptoKit) inside `QUIC.tls.certificateValidator`,
exposing typed failure via `pinFailureReason`/`pinComputedHashHex`/`pinMatchedLeafDer`.

Hard-won facts (DO NOT relearn):
- **`.waiting(POSIX 50 / ENETDOWN)` is TRANSIENT** mid-handshake on loopback and RECOVERS to `.ready`.
  Only `.ready`/`.failed`/`.cancelled` are terminal for `connect`; treating `.waiting` as a failure kills
  the connect before it readies. (This cost the most time — compounded by the build bug below.)
- **The new `NetworkConnection`/`NetworkListener`/`QUIC.Stream` have NO `cancel()`** — teardown is via
  ARC: stamp `connection.applicationError` / `stream.streamApplicationErrorCode`, drop references; for the
  server, returning the `listener.run { }` closure is what ends the accepted connection (the shim parks it
  on a `CheckedContinuation` until Kotlin `close()`s).
- **swiftc → static archive code changes did NOT relink the K/N test binary** (the archive enters the link
  only via a `force_load` linkerOpts string Gradle can't see, and the cinterop klib captures only the
  generated header). FIXED: the link task now tracks the archive as an explicit input + `dependsOn` the
  swiftc task. Symptom if it regresses: a Swift impl edit silently runs a stale `test.kexe`.
- **`libswift_Concurrency.dylib`** (async/await) is referenced via `@rpath` (unlike the absolute-install-name
  Core/Foundation), so the binaries need `-Wl,-rpath,/usr/lib/swift` (OS-resident in the dyld shared cache
  on OS 26) or the test aborts at launch ("Library not loaded"). `-framework CryptoKit` added to the `.def`.
- K/N imports `_Nonnull` ObjC block params as **nullable**; Swift readonly `@objc` props export as **methods**
  (`pinFailureReason()`).

NEXT = step 3 (streams + ids: `openStream`/`onInboundStream` + send/receive/half-close/reset, validated by
a macosArm64Test stream loopback) — the shim code is already wired; it needs a test. Then step 4 (wire the
server into the Kotlin `QuicServer`/`AppleQuicSwiftConnection` path) and step 5 (OS gating + fallback +
un-`@Ignore` `AppleHttp3LoopbackTest.webTransport_datagramRoundTrip` + Apple `WebTransportTestSuite`).

## Step 1 status — build plumbing DONE (all Apple targets)

The swiftc → static archive → generated `-Swift.h` → cinterop → Kotlin/Native call chain is wired and
GREEN. Files: `src/nativeInterop/swift/NWQuic26Bridge.swift` (a TRIVIAL `@objc` shim — `ping(Int32)` +
`greeting()→NSString`, NOT the real bridge yet), `src/nativeInterop/cinterop/NWQuic26.def`, the
`registerNWQuic26SwiftTask` + `configureNWQuic26SwiftBridge` plumbing in `build.gradle.kts`, and the
runnable proof `src/macosArm64Test/.../NWQuic26BridgeSpikeTest.kt`. Validation: `macosArm64Test` RUNS
the bridge (scalar + NSString marshaling green); `linkDebugTest<Target>` LINKS clean for all other 10
targets (macosX64, ios{Arm64,SimulatorArm64,X64}, tvos{Arm64,SimulatorArm64,X64},
watchos{Arm64,SimulatorArm64,X64}). ktlint clean. Nothing committed yet.

Resolved unknowns (carry forward — DO NOT relearn):
- **swiftc → static lib:** `swiftc -emit-library -static -emit-module -module-name NWQuic26
  -emit-objc-header -emit-objc-header-path <h> -target <triple> -sdk <sdkPath> -O -o libNWQuic26.a`.
- **Per-target triples + SDK (all verified to compile the shim with the right arch):**
  macosArm64=`arm64-apple-macos26.0`/macosx · macosX64=`x86_64-apple-macos26.0`/macosx ·
  iosArm64=`arm64-apple-ios26.0`/iphoneos · iosSimulatorArm64=`arm64-apple-ios26.0-simulator`/iphonesimulator ·
  iosX64=`x86_64-apple-ios26.0-simulator`/iphonesimulator · tvosArm64=`arm64-apple-tvos26.0`/appletvos ·
  tvosSimulatorArm64=`arm64-apple-tvos26.0-simulator`/appletvsimulator · tvosX64=`x86_64-apple-tvos26.0-simulator`/appletvsimulator ·
  **watchosArm64=`arm64_32-apple-watchos26.0`/watchos** (K/N watchosArm64 is arm64_32, ILP32) ·
  watchosSimulatorArm64=`arm64-apple-watchos26.0-simulator`/watchsimulator · watchosX64=`x86_64-apple-watchos26.0-simulator`/watchsimulator.
  All 11 OS-26 SDKs present on this Mac (26.5).
- **Swift runtime linking (the big risk) — SOLVED with no embedded/static-stdlib work:** the archive's
  embedded `LC_LINKER_OPTION` load commands request `-lswiftCore`/`-lswiftFoundation`; ld resolves them
  against the SDK `.tbd` stubs via a single `-L<sdkPath>/usr/lib/swift`. Swift is ABI-stable and ships
  in every target OS, so nothing is bundled and no rpath is needed (system path) — identical for macОS /
  device / simulator. `-Wl,-force_load,<archive>` keeps the `@objc` class from being dead-stripped.
- **`@objc` header → cinterop:** the generated `-Swift.h`'s C++ includes are `__cplusplus`-guarded and
  Foundation is `__OBJC__`-guarded, so ObjC-mode cinterop (`language = Objective-C`) takes the clean
  path with no header massaging.
- **Gradle ↔ swiftc:** plain `ProcessBuilder` task gating the cinterop via
  `tasks.named(interopProcessingTaskName){ dependsOn(swiftTask) }`, mirroring quiche's cargo→`Quiche.def`.

NOTE for step 2: K/N's default deployment target is below 26.0, so the link currently emits a benign
"object built for newer OS" warning; bump the K/N binary min-OS to 26.0 (and OS-gate at runtime) when
the shim starts using the real OS-26 API.

**Status (original spike):** capability PROVEN (2026-06-24, macOS 26.5.1).

## Why

On Apple's legacy `nw_connection_group` (the current `socket-quic-nw` backend), QUIC **datagrams and
inbound (peer-initiated) streams cannot coexist**: extracting the `is_datagram` flow makes NW route all
inbound data — including inbound stream bytes — onto the datagram flow, killing inbound-stream delivery
(`apple-nw-datagram-breaks-inbound-streams`, GitHub #173). That blocks WebTransport **datagrams** on
Apple (WT streams already work). Two confirmed dead ends: group-level datagrams (the group message API
doesn't carry QUIC DATAGRAM frames — spike), and any demux of the conflated datagram flow.

The macOS/iOS **26** Swift Network API (`NetworkConnection<Network.QUIC>`) models `inboundStreams { }`
and `datagrams` as first-class members of the **same** connection. A pure-Swift loopback on macOS 26.5.1
proved datagrams + a server→client inbound stream coexist on one connection. This plan bridges that API
into Kotlin/Native so `socket-quic-nw` can use it on OS 26+, with the legacy group backend as the
pre-26 fallback. (Pre-26 datagram support, if needed, is a separate effort: quiche-on-Apple — "option 3".)

## Constraint: why a Swift shim is required

K/N has **Objective-C** interop, not Swift interop. The new API is Swift-only — generics
(`NetworkConnection<QUIC>`), `async`/`await`, `AsyncSequence`, value types, result builders — none of
which project to Obj-C, so cinterop can't see them. We must write a thin **Swift shim** that wraps the
API behind an `@objc`/`NSObject` surface (callbacks instead of async, NSData instead of `Data`), compile
it, and cinterop the generated `-Swift.h`. This is a THIRD interop surface alongside `nw_helpers.h`
(TCP/TLS) and `nw_quic_helpers.h` (legacy QUIC); see the drift note at the end.

## Target API surface (the `@objc` shim boundary)

Goal: satisfy the existing `QuicConnection` / `QuicScope` / `QuicByteStream` / `QuicServer` contracts
(commonMain) so `AppleQuicGroupConnection` gets a sibling `AppleQuicSwiftConnection` and the public API
is unchanged. The shim hands Kotlin opaque handles + completion/stream callbacks. Sketch (Obj-C-visible):

```
@objc final class NWQuic26Bridge : NSObject {
  // client
  @objc func connect(host:port:alpn:idleMs:maxDatagram:flowControl:
                     verifyCallback: (certDer)->Bool,           // maps serverCertificateHashes / pinning
                     onReady: (errCode, desc)->Void)            // async .ready → callback
  // server
  @objc func listen(identity: sec_identity_t, alpn:..., onConnection: (NWQuic26Conn)->Void, onReady:(port)->Void)
}
@objc final class NWQuic26Conn : NSObject {
  @objc func openStream(uni: Bool, completion: (streamHandle, streamId, err)->Void)
  @objc func onInboundStream(_ handler: (streamHandle, streamId, uni, initiatorServer)->Void)  // inboundStreams { }
  @objc func sendDatagram(_ data: NSData, completion:(err)->Void)
  @objc func onDatagram(_ handler: (NSData)->Void)                                              // datagrams.receive loop
  @objc var usableDatagramSize: Int
  @objc func close(appErrorCode:)
  @objc func onStateChanged(_ handler:(state, errCode, desc)->Void)
}
@objc final class NWQuic26Stream : NSObject {
  @objc func send(_ data: NSData, endOfStream: Bool, completion:(err)->Void)
  @objc func receive(maxBytes:, completion:(NSData?, isEnd, resetCode, err)->Void)
  @objc func reset(appErrorCode:)
  @objc var streamId: UInt64
}
```

The shim owns the Swift structured-concurrency Tasks (one serving Task per `inboundStreams`/`datagrams`
loop) and marshals each event to the registered C callback. Kotlin re-wraps these as the existing
`Channel<QuicByteStream>` / datagram `Channel<ReadBuffer>` / `suspendCancellableCoroutine` patterns —
same shape `AppleQuicGroupConnection` already uses, so most Kotlin teardown/lifecycle logic is reusable.

## Build plumbing (the fiddly part)

K/N's Gradle plugin does NOT compile Swift. Need, per Apple target (macosArm64, macosX64, iosArm64,
iosSimulatorArm64, iosX64, +tvos/watchos if kept):
1. A Gradle task running `swiftc -emit-library -emit-objc-header` (or build a small SwiftPM/xcframework)
   producing `libnwquic26.a` + `nwquic26-Swift.h` for the right `-target` + min-OS.
2. A cinterop `.def` (e.g. `NWQuic26.def`) pointing `headers = nwquic26-Swift.h`, with
   `compilerOpts`/`linkerOpts` for the Swift runtime + `-framework Network -framework Security`.
3. Wire the swiftc task as a dependency of the cinterop/compile task (mirror how quiche's cargo build
   feeds `Quiche.def` in `socket-quic-quiche/build.gradle.kts` — same "build native dep → cinterop it"
   shape, that's the precedent to copy).
CI macOS runners have the Swift toolchain; gate the whole module path on OS so non-Apple CI is unaffected.

## Bridging specifics / gotchas (from the spike — DO NOT relearn these)

- **idleTimeout is MILLISECONDS** (`.idleTimeout(30)` = 30 ms, silently idle-kills mid-stream). Use ms.
- New API's `initialMax*Streams` / stream-data default low/0 → a peer-opened stream is **starved**.
  Set generous credits (spike used `initialMax{Bidirectional,Unidirectional}Streams(128)`, data 1<<20),
  mirroring legacy `NW_QUIC_MAX_STREAMS=1024`.
- `inboundStreams` must be **actively serving before** the peer opens a stream; calling it (or
  `datagrams`) drives the handshake — a passive `onStateUpdate` ready-wait on the server conn STALLS
  establishment. The shim must start the serving Tasks immediately.
- Await `.ready` before some client ops (`inboundStreams` throws POSIX 57 if not ready).
- `SecPKCS12Import` MUST pass `kSecImportToMemoryOnly` or it imports the key to the login keychain and
  prompts for the macOS password every run. (Production `nw_helper_quic_identity_from_p12` already does
  this — REUSE it; don't re-import in Swift.)
- **No ByteArray** (CLAUDE.md): keep bytes as NSData across the boundary → `NSDataBuffer` zero-copy on
  the Kotlin side, exactly as the legacy path does.
- Cert pinning: map `serverCertificateHashes` onto `QUIC.tls.certificateValidator { meta, trust in ... }`
  (hash the leaf DER there) — the Swift analog of the existing verify_block; reuse the W3C constraint
  checks already in Kotlin.
- Stream id / directionality come for FREE here (`stream.streamID`, `.directionality`, `.initiator`) —
  no more synthetic-id / phantom-stream / real-id dance the legacy path needed.

## OS gating & fallback

`socket-quic-nw` engine picks the backend at connect/bind: macOS 26 / iOS 26+ → Swift path
(`AppleQuicSwiftConnection`); below → existing `connectQuicGroup` / `buildAppleQuicServer`. Datagrams
remain `PreferStreams`-degraded only on the legacy (<26) path. Same `QuicOptions`, no public API change.

## Validation

Reference harness: `socket-quic-nw/os26-bridge-reference.swift` (untracked) — the proven pure-Swift
loopback. Build/run: `swiftc -target arm64-apple-macos26.0 -o /tmp/ref os26-bridge-reference.swift &&
/tmp/ref socket-quic-nw/testcerts/localhost.p12`. NB: occasionally fails with POSIX 22 (EINVAL) on rapid
back-to-back runs (listener/port reuse) — re-run; it's a harness flake, not an API limitation.

1. Port `os26-bridge-reference.swift` into a Kotlin `macosArm64Test` (loopback: datagrams +
   server→client inbound stream coexist).
2. Un-`@Ignore` `AppleHttp3LoopbackTest.webTransport_datagramRoundTrip`.
3. Run the Apple `WebTransportTestSuite` (the suite that's red today purely for datagrams) on macOS 26.
4. Keep the legacy-path probes (`AppleQuicUniStreamProbeTests`) green to prove no regression <26.

## Risks / unknowns

- Gradle ↔ swiftc integration (no first-class support; custom task + correct min-OS/target triples).
- `@objc`-exportability: the generics-heavy API must be fully wrapped behind concrete `@objc` classes —
  verify each wrapper compiles to Obj-C (the spike used the API directly in Swift; the `@objc` lowering
  is new surface).
- Swift runtime linking into the K/N binary (static vs dynamic; embedded Swift considerations).
- Per-target swiftinterface exists (arm64e/arm64 both present in SDK) — confirm arm64 device + sim builds.
- tvOS/watchOS availability of the new API (annotated 26.0 across platforms in the swiftinterface — OK).
- Threading: NW delivers on its queues; the shim's callbacks resume K/N continuations — apply the same
  serial-queue / foreign-thread discipline the legacy helpers document (issue #112).

## Effort sequencing

1. Spike the **build plumbing** alone: trivial `@objc` Swift fn → static lib → cinterop → call from a
   macosArm64 test. (De-risks the unknowns above before any real shim code.)
2. Client connect + datagrams round-trip (smallest end-to-end slice).
3. openStream / inboundStream + send/receive (+ ids, half-close, reset).
4. Server (`NetworkListener`) path + identity reuse.
5. OS gating + fallback wiring; cert pinning; validation suite.

## Relationship to option 3

This covers OS **26+** only. If datagrams are required on pre-26 Apple, that needs quiche-on-Apple
(bring-our-own-QUIC via cargo + cinterop, mirroring Linux) — a separate, larger effort. Decide based on
the minimum OS the product must support.
