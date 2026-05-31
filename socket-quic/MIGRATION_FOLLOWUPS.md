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
`build-linux` (JVM/FFM). Plus `SockAddrDecodeTest` for the PathKey round-trip.

---

## The coverage gaps (why "lots of blind spots" is correct)

socket-quic has **four** `QuicheApi` backends and CI exercises them very unevenly:

| Backend | Used by | Runs in CI | Migration exercised at runtime? |
|---|---|---|---|
| **FFM** (Panama) | JVM on **JDK 21+** | ✅ `:socket-quic:jvmTest` (build-linux) | ✅ **yes** — the whole feature (client + in-repo server) |
| **JNI** (C shim) | **Android**, JVM JDK<21 | shim *built*; Android emulator runs it | ❌ **no** — Android tests are client-only vs an external echo server and never call `migrate()` |
| **cinterop** (K/N) | linux/macOS/iOS native | ✅ `linuxX64Test`, apple K/N | ❌ `migrate()` returns `Unsupported` (no K/N `UdpChannelFactory`) |
| **Stub** | unit tests | ✅ | n/a (fake) |

### Gap 1 — JNI backend is never runtime-tested for migration  *(highest leverage)*
CI's only JVM job runs on **JDK 21 → FFM**. The JNI shim *compiles* (signature
breaks are caught), but its migration paths never *execute*. A logic/byte-order
bug in `quiche_jni.c` would not be caught by any running test.
- **Fix:** add a **JDK-17 matrix entry** that runs `:socket-quic:jvmTest`
  (incl. `QuicMigrationLoopbackTests`) — same tests, JNI backend. This alone
  runtime-proves `connNewScid`, the sockaddr decode, and server routing on JNI.
- **Effort:** low (CI matrix). **Files:** `.github/workflows/build-linux.yaml`
  or `review.yaml`. Needs the JNI shim staged for the JDK-17 run.

### Gap 2 — JNI sockaddr byte-order fix isn't exercised where it matters
PR #63 canonicalized JNI `sockAddrV4/V6Hi/V6Lo` to big-endian (to match
FFM/cinterop) so `PathKey` reconstructs unambiguously. But the *only* consumer of
the canonical value is `PathKey.toInetSocketAddress()` on the **server egress**
path, which is JVM-only and runs on **FFM** (where decode was already correct).
Client-side path lookup only needs *distinctness*, not absolute value, so it
works on any backend regardless. Net: the JNI byte-order fix is defensive and
**untested at runtime**. → Closed by Gap 1's JDK-17 run (it would exercise JNI
server reconstruction via the loopback test).

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
1. **Gap 1** (JDK-17/JNI test run) — cheapest, retroactively validates all the
   JNI work already merged. Do this first.
2. **Gap 6** (bound the recv_info cache) — quick hardening of merged code.
3. **Gap 7** (Windows artifact resilience) — stop the cosmetic red.
4. **Gap 4** (K/N `UdpChannelFactory`) then **Gap 8** (passive netem) — the
   bigger feature-coverage items.
5. **Gap 3** (real Android active-migration test) alongside Gap 1.

Design context: `socket-quic/MIGRATION_SLICE3.md`. Repo TODO: `TODO.md`.
