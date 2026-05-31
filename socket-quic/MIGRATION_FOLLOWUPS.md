# QUIC Connection Migration — Follow-ups & Coverage Gaps

Handoff doc for continuing QUIC Phase 3 (connection migration, rung 2) work.

## Status (as of 2026-05-31)

**Merged:** PR #63 → `main` (squash `203f8f7`), auto-released as **v3.1.8**.

Rung-2 connection migration (RFC 9000 §9 — single active path at a time;
MP-QUIC/rung-3 is blocked because quiche 0.28/0.29 have no multipath) is
implemented and **proven end-to-end on JVM/FFM**:

- **Client active migration** — `QuicScope.migrate(localHost, localPort)` opens a
  second socket, probes the new path, and on validation calls `connMigrate`.
  `pathState: StateFlow<PathInfo>` exposes phase. Multi-socket `QuicheDriver`
  routes egress by `sendInfo.from` (PathKey) and ingress by per-path `recv_info`.
- **Spare connection IDs** — quiche does *not* auto-issue CIDs. The driver calls
  `connNewScid` on establishment (both ends), bounded by `connScidsLeft`, plus
  `active_connection_id_limit` so the peer has migratable DCIDs.
- **Server-side path routing** (the thing that made validation actually pass):
  per-source `recv_info` cache (`recvInfoOverride` on `RecvPacket`) so quiche sees
  a migrated peer's real source as a new path; egress to `sendInfo.to` via
  `UdpChannel.send(dest)` + `NioUdpChannel` reconstruction (1-entry cache); and
  registration of spare server SCIDs in the DCID routing map so a migrating
  client's new DCID reaches the right driver.

**Proven by:** `QuicMigrationLoopbackTests.streamSurvivesActiveMigrationToLoopbackAlias`
(client 127.0.0.1 → `migrate("127.0.0.2")` → stream survives), green on
`build-linux`. Plus `SockAddrDecodeTest` for the PathKey round-trip.

> **Correction (PR #64, 2026-05-31):** PR #63 was proven on the **JNI** backend,
> not FFM. `:socket-quic:jvmTest` resolves `loadQuicheApi()` to the base
> `commonJvmMain` JNI loader on every JDK (the java21/FFM output is not on the
> Test runtime classpath), so the JDK 21 build-linux job ran JNI all along. FFM
> was in fact **non-functional on Linux** (see Gap 1/2 below for the full story);
> PR #64 fixed it and added real FFM + JDK-17/JNI coverage.

---

## ✅ Resolution summary (2026-05-31) — all gaps closed except Gap 5

Worked through the gaps in a series of small PRs. Current published release:
**`v3.2.1`** (the only production change in the batch was the Android atomicfu fix
in #67; #68/#69 are CI/test-only and merged with the `skip-release` label, so they
did not cut new versions).

| Gap | Status | PR | Notes |
|---|---|---|---|
| 1 — JNI migration runtime-tested | ✅ closed | #64 | premise was inverted; jvmTest already ran JNI. **Found & fixed:** FFM was non-functional on Linux (loaded an unloadable `libquiche.so` → silent JNI fallback) and FFM migration **SIGSEGV'd** (`sendInfoToAddr` read an inline sockaddr as a pointer). Deterministic loader (no silent fallback) + FFM/JDK-17 jvmTest matrix added. |
| 2 — JNI byte-order exercised | ✅ closed | #64 | server reconstruction runs on JNI in jvmTest; also on JDK-17. |
| 6 — server `recv_info` cache bounded | ✅ closed | #65 | LRU cap 256, **reference-counted** eviction (UNLIMITED command channel ⇒ free-on-evict was a UAF). |
| 4 — K/N connection migration | ✅ closed | #66 | `IoUringUdpChannelFactory` + `migrate()` + K/N server routing (per-source recv_info, `sendInfo.to` egress, SCID registration) + loopback test. |
| 3 — Android active migration | ✅ closed | #67 | real `migrate()` instrumented test. **Found & fixed:** `buffer-android` needs `kotlinx.atomicfu.AtomicFU` at runtime but doesn't declare it → all Android QUIC connect tests had been **silently skipping** in CI; added atomicfu to `androidMain`. Validated on a local x86_64 emulator. |
| 7 — Windows DLL resilience | ✅ closed | #68 | `build-windows` tolerates a missing cross-compiled DLL (green instead of cosmetic red); still runs tests when present. |
| 8 — passive (NAT-rebind) migration | ✅ closed | #69 | userspace rebinding UDP proxy (no root/netns/tc) proves the server keeps a stream alive through a source rebind with **no** client `migrate()`. |
| **5 — macOS QUIC validation** | ⏳ **open** | — | `:socket-quic:jvmTest` excluded on macOS (quiche-0.28 `quiche_conn_recv` panic) ⇒ FFM-on-macOS + iOS/macOS K/N migration untested. Being handled separately on Apple hardware. PR #64 raised its importance: FFM is now the deterministic macOS/Windows JDK 21+ backend and its `IS_BSD` sockaddr paths have never run. |

**New follow-ups surfaced this session (not original gaps):**
- **Make Windows blocking** — drop `build-windows`'s job-level `continue-on-error`
  once Windows `jvmTest` is green ≥2 runs (now observable after #68).
- **CI version-race hardening** — `validate-artifacts` in a PR's `review.yaml`
  fails spuriously when a release publishes mid-CI (it resolves a not-yet/never-
  published version). Releases themselves serialize via `concurrency: deploy`, so
  this is review-vs-release, not release-vs-release. Worth making `getNextVersion`
  race-proof. Mitigation in use: `skip-release` label for CI/test-only PRs.
- **atomicfu in the base `socket` module** — the same `buffer-android` atomicfu gap
  likely affects base-`socket` Android consumers; #67 only fixed socket-quic's
  `androidMain`. Verify + propagate.

---

## The coverage gaps (why "lots of blind spots" is correct)

socket-quic has **four** `QuicheApi` backends. The table below is **corrected as
of PR #64** — the original handoff had the FFM/JNI rows backwards (it assumed the
multi-release JAR shadowing that applies to *production consumers* also applied to
the `jvmTest` task; it does not, because jvmTest runs against exploded class dirs,
not the MR-JAR):

| Backend | Used by | Runs in CI | Migration exercised at runtime? |
|---|---|---|---|
| **JNI** (C shim) | **Android**, JVM JDK<21, **and `:socket-quic:jvmTest` on every JDK** | ✅ `:socket-quic:jvmTest` (build-linux, default + JDK-17 step) | ✅ **yes** — `QuicMigrationLoopbackTests` (client + in-repo server) since PR #63; JDK-17 step added in #64 |
| **FFM** (Panama) | production JVM consumers on **JDK 21+** (via MR-JAR) | ✅ `:socket-quic:jvmTest -PquicheJvmBackend=ffm` (build-linux, added in #64) | ✅ **yes, since PR #64** — was broken+unrun on Linux before (silent JNI fallback) |
| **cinterop** (K/N) | linux/macOS/iOS native | ✅ `linuxX64Test`, apple K/N | ❌ `migrate()` returns `Unsupported` (no K/N `UdpChannelFactory`) |
| **Stub** | unit tests | ✅ | n/a (fake) |

### Gap 1 — ~~JNI backend is never runtime-tested for migration~~  ✅ CLOSED (PR #64) — the premise was inverted
**Original (wrong) claim:** "CI's only JVM job runs JDK 21 → FFM, so the JNI shim's
migration paths never execute."

**What was actually true:** `:socket-quic:jvmTest` resolves `loadQuicheApi()` to the
base `commonJvmMain` **JNI** loader on *every* JDK — the java21/FFM compilation
output is not on the Test runtime classpath (the MR-JAR shadowing only applies to
packaged consumers, not the test task running off exploded class dirs). So the JDK
21 build-linux job had been exercising **JNI all along**: `QuicMigrationLoopbackTests`
already runtime-proved `connNewScid`, the sockaddr decode, and server routing on JNI
since PR #63. The genuinely-untested backend was **FFM** — and worse, FFM was
*non-functional* on Linux:

- The jvm21 loader targeted the pure `libquiche.so`, which is built against an
  *external* BoringSSL (`--features ffi`) and `dlopen`s with unresolved
  `SSL_*`/`CRYPTO_*` symbols (and often isn't even shipped). `FfmQuicheApi.create()`
  always threw → the loader **silently fell back to JNI** everywhere (incl. the
  quic-echo MR-JAR server).
- That fallback hid a **JVM-crashing FFM bug**: `FfmQuicheApi.sendInfoToAddr` read
  the *inline* `quiche_send_info.to` sockaddr_storage as a *pointer* (recv_info has
  pointer fields; send_info has inline storage), so `sockAddrFamily` dereferenced
  garbage → SIGSEGV on **every flush** (`decodePathKey` runs per-send).

**Fixed in PR #64:** (a) `sendInfoToAddr` returns `handle + SEND_INFO_TO_OFFSET`
(= `&info->to`, mirrors `sendInfoFromAddr`); (b) the loader loads the self-contained
`libquiche_jni.*` (re-exports the full quiche C API, dlopens cleanly) and is now
**deterministic — throws instead of silently degrading to JNI** on JDK 21+; (c)
build-linux runs `:socket-quic:jvmTest` **3×** — JNI/21, FFM/21
(`-PquicheJvmBackend=ffm`), JNI/17 (`-PjvmTestLauncher=17`) — all with
`QUIC_MIGRATION_REQUIRE_RUN=1` so a silent migration-test skip hard-fails. CI green:
the loopback test ran+passed on all three; FFM full suite 165/165 on Linux for the
first time.

### Gap 2 — ~~JNI sockaddr byte-order fix isn't exercised where it matters~~  ✅ CLOSED (PR #64)
PR #63 canonicalized JNI `sockAddrV4/V6Hi/V6Lo` to big-endian (to match
FFM/cinterop) so `PathKey` reconstructs unambiguously. The original note worried it
was untested because it assumed server reconstruction ran on FFM. In fact server
reconstruction (`PathKey.toInetSocketAddress()` on the egress path) runs on **JNI**
in `:socket-quic:jvmTest` (see Gap 1), so the JNI byte-order path *was* exercised by
the loopback test since PR #63 — and is now additionally exercised on a real JDK-17
JNI runtime by PR #64's matrix.

### Gap 3 — `AndroidQuicMigrationTests` is misnamed: it never migrates
The Android instrumented "migration" suite (`connectionSurvivesTemporaryNetworkLoss`,
`airplaneModeToggle`, etc.) tests **passive resilience** (block/unblock UDP,
latency) and has **zero `migrate()` calls**. So there is no active-migration
coverage on Android/JNI.
- **Fix:** add a real active-migration instrumented test. Android client
  `migrate()` against the docker echo server would exercise JNI `connNewScid` +
  client path-routing decode. Need to confirm a second local source is bindable
  on the emulator (loopback-alias `127.0.0.x`, or a second interface).
- **Effort:** medium. **Files:** `socket-quic/src/androidInstrumentedTest/...`.

### Gap 4 — No migration on Kotlin/Native (was deferred follow-up #2)
`migrate()` returns `MigrationResult.Unsupported` on K/N because there is no
native `UdpChannelFactory`. Establishment-time `connNewScid` via cinterop *is*
incidentally exercised by `linuxX64Test`, but the probe/validate/migrate path
and server routing are not.
- **Fix:** implement an io_uring-backed `UdpChannelFactory` for `linuxMain`
  (mirror `NioUdpChannelFactory`: open socket, bind new local, connect peer,
  build sockaddr). Then add a K/N loopback migration test → covers cinterop.
- **Effort:** medium-high. **Files:** `socket-quic/src/linuxMain/...`.

### Gap 5 — macOS doesn't run `:socket-quic:jvmTest`
Excluded on the macOS runner (the quiche-0.28 `quiche_conn_recv` panic item).
So FFM-on-macOS is unvalidated, and iOS/macOS K/N migration is untested (Gap 4).
- Tracked with the existing quiche-0.28 panic work; revisit when that lands.

### Gap 6 — Server per-source `recv_info` cache is unbounded
`JvmQuicServer.peerRecvInfos` caches one `recv_info` (+ sockaddrs) per distinct
datagram source, freed only at server close. A long-lived server facing many
sources / NAT churn grows native memory without bound — a mild DoS vector.
- **Fix:** bound it (LRU; evict + free oldest, e.g. cap 256). Single active path
  per connection means the working set is tiny; eviction of a stale source is safe.
- **Effort:** low. **Files:** `CommonJvmWithQuicServer.kt`.

### Gap 7 — Windows JVM Tests is fragile (and non-blocking)
The Windows `quiche_jni.dll` is **best-effort MinGW-cross-compiled inside
build-linux** (`|| true`, `if-no-files-found: ignore`). The quiche native caches
are keyed on `hashFiles(... quiche_jni.c)`, so **any edit to `quiche_jni.c`
cold-busts the cache** and the fragile cross-build may not emit the DLL →
`build-windows` dies at "Download artifact `quiche-windows-natives`". It's
currently `continue-on-error: true` (non-blocking) during Windows bring-up.
- **Fix options:** make `build-windows` tolerate a missing artifact (skip
  cleanly), or build the DLL natively on `windows-latest`, or static-link
  libgcc/stdc++ so the DLL reliably loads (the stated intended fix).
- **Effort:** low-medium. **Files:** `review.yaml`, `build-linux.yaml`.

### Gap 8 — No passive (NAT-rebind) migration test (was deferred follow-up #3)
Server-side routing now exists (`recvInfoFor` + `sendInfo.to`), so passive
rebind — where the server sees the source change with no client `migrate()` call —
should largely work, but nothing proves it. Note the server driver does **not**
drain path events (`migrationEnabled` is client-only), so the `PeerMigrated`
event is not surfaced to the app; it relies on quiche's internal frame handling
+ per-packet `sendInfo.to` routing.
- **Fix:** netns + veth + tc-netem CI harness that rebinds the client's source
  mid-stream and asserts the stream survives. If app-level server migration
  notifications are ever needed, enable `drainPathEvents` for servers.
- **Effort:** medium-high. **Files:** new CI job + test harness.

---

## Suggested order for the next session
- ~~Gaps 1, 2 (#64) · 6 (#65) · 4 (#66) · 3 (#67) · 7 (#68) · 8 (#69)~~ — ✅ all done
  (see the Resolution summary at the top).
1. **Gap 5** (macOS QUIC validation) — the only remaining original gap; needs Apple
   hardware. Re-enable `:socket-quic:jvmTest` on the macOS runner once the
   quiche-0.28 `quiche_conn_recv` panic is resolved; validate FFM-on-macOS (the
   `IS_BSD` sockaddr paths) and iOS/macOS K/N migration.
2. **New follow-ups** (see top): make Windows blocking (drop `continue-on-error`),
   harden the CI version-race, and propagate the atomicfu fix to base `socket`.

Design context: `socket-quic/MIGRATION_SLICE3.md`. Repo TODO: `TODO.md`.
