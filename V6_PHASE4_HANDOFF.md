# v6 Phase 4 — release-readiness handoff (WebTransport gaps + buffer 6.0)

Authoritative handoff for closing out **v6** on branch `redesign/major-api-v6`.
Written 2026-06-23 after a full re-investigation. Read this first in the fresh session.

---

## ⏩ SESSION 2 UPDATE (2026-06-23 PM) — read this first; supersedes §1–§3 where they conflict

**Working tree (uncommitted, NOT pushed):** clean of debug/experiment artifacts. Changes:
- `gradle/libs.versions.toml`: **buffer repinned `6.0.0-SNAPSHOT` → `5.13.2`.** That's the CI-built
  buffer #213 (`redesign/buffer-6.0` @ `812c098a`) — same reshaped tree, full target matrix incl. ALL
  Apple targets + the `buffer-codec` runtime the macosArm64-only SNAPSHOT lacked. Obtained by re-running
  buffer CI run `27841948930`, downloading the `maven-local-merged` artifact, and merging into `~/.m2`
  (the PR-branch `nextVersion`=5.13.2; the same tree publishes as 6.0.0 on merge to main). This unblocked
  the whole Apple/Android/ios/tvos/watchos matrix (the SNAPSHOT only had macosArm64). **mavenLocal() still
  in all 8 build files** — still needed (5.13.2 is mavenLocal-only). Step 6 (real Central 6.0.0) unchanged.
- **Step 1 (testcerts) DONE:** the untracked `socket-quic/` (base) certs were stale orphans (cert pipeline
  moved to `-quiche`/`-nw`); deleted.
- **Step 5 (allowPooling) DONE:** documented as intentional no-op in `WebTransportSupportHttp3.kt`.
- **Apple test added:** `socket-webtransport/src/appleTest/.../AppleWebTransportTest.kt` + `appleTest`→
  `:socket-testsuite` wiring + `generateWebTransportTestP12` Gradle task (NW server needs a p12,
  pass `testpass`) + `.gitignore` entry. ktlint-clean.
- **Android test added:** `socket-webtransport/src/androidInstrumentedTest/.../AndroidWebTransportTest.kt`
  + `androidInstrumentedTest` wiring + bundled `certs/cert.{crt,key}`. ktlint-clean. NOT yet run (needs the
  booted emulator — `Pixel_3a_API_33_arm64-v8a`; adb at `~/Library/Android/sdk/platform-tools`).
- **NW uni-stream fix (real, validated):** `socket-quic-nw` accept paths mislabeled peer **uni** streams
  as bidi (synthetic ids never set bit-1). NW *does* expose the real id (`nw_quic_get_stream_id`), so a new
  cinterop helper `nw_helper_quic_stream_real_id` reads it on a now-live flow; the client accept path now
  peeks-then-classifies via the shared `filterPhantomAndEnqueue` on a dedicated `streamAcceptScope`
  (cancelled in connection teardown). Proven by `socket-quic-nw/src/appleTest/.../AppleQuicUniStreamProbeTests.kt`
  (5 tests GREEN on macosArm64). **Keep these changes.**

**🔑 APPLE WEBTRANSPORT ROOT CAUSE — FULLY CHARACTERIZED (the headline result):**
> On Apple's Network.framework **raw QUIC connection-group API, extracting the datagram flow
> (`nw_connection_group_extract_connection` with `is_datagram=true`) makes NW deliver ALL inbound data —
> including inbound *stream* bytes — onto that datagram flow.** Proven: an inbound stream's bytes arrive on
> the client's `receiveDatagram()`. So the group's `new_connection_handler` stops delivering inbound
> streams entirely (bidi AND uni) the moment a datagram flow exists. Merely *advertising*
> `max_datagram_frame_size` is harmless — only the **flow extraction** breaks it (proven by an experiment
> that skipped extraction: inbound streams then worked with datagrams advertised).

Consequence: **WebTransport STREAMS work on Apple** (probes prove the full H3 stream pattern without
datagrams). **WebTransport DATAGRAMS + inbound streams cannot coexist** in NW's group model — a genuine
NW limitation, not our bug. The `WebTransportTestSuite` enables datagrams in its options (though none of
its 3 tests actually *use* datagrams — they only need streams), so the Apple DONE-bar is still RED purely
because of this. Existing Apple datagram tests pass because they're datagram-only (no inbound streams).

**DECISION PENDING (user was mid-deciding fix approach):**
- **(A) Make Apple H3/WebTransport NOT extract the datagram flow** (prioritize inbound streams, which are
  essential for H3) → the 3 DONE-bar stream tests pass; WebTransport datagrams become an Apple limitation
  (document + file issue, adjacent to #158). Smallest path to green. Open Q: how the backend decides to
  skip extraction (always-skip breaks the existing Apple datagram-only feature; likely needs an option or
  H3-layer signal "I need inbound streams, drop datagrams if they conflict").
- **(B) Deep NW research** for an API that delivers inbound streams alongside a datagram flow (e.g.
  `extract_connection_for_message`, or a non-group QUIC model). Uncertain it exists.
- Recommended: **A** for the v6 release, with the limitation documented; revisit B as a follow-up issue.

**Remaining v6 steps (unchanged from below):** run Android test on the emulator; Apple fix per decision;
**browser** (§3 fork — Karma headless Chrome + Chrome DevTools MCP); **buffer release** (§2/§6 — merge
#213 → Central 6.0.0, repin 5.13.2→6.0.0, drop 8× mavenLocal, full matrix green). Nothing pushed/committed
yet — branch still at origin `0a46bde`.

---

> **Mission:** make v6 *feature-complete and platform-consistent*, then ship a clean v6 release.
> The blocker is NOT missing code — the multiplexing engine is built and real on every platform.
> The blocker is (a) buffer 6.0.0 isn't on Central yet, and (b) WebTransport is only *validated* on
> 2 of ~15 declared targets.

---

## 1. Branch / sync state (do this first — it's a bus-factor risk)

- Branch `redesign/major-api-v6`, Mac HEAD `0a46bde`. **18 commits unpushed** — `origin/redesign/major-api-v6`
  is 18 behind. ALL of Phase 4 (W3C cert-pinning series, typed uni-streams, SHA-256 leaf-pin, #164
  forward-port) lives only locally. **Push + set upstream early.**
- `aliens` (Linux box, `ssh -o RemoteCommand=none aliens`, repo `~/git/socket`) is at `51684ff` — a
  strict prefix, ONE commit behind the Mac. Clean tree. Fast-forward it after pushing.
- `aliens` has `stash@{0}: aliens seam-swap-v2 draft backup pre typed-uni` on this branch — almost
  certainly superseded by `51684ff` (typed uni streams). `git stash show -p` before dropping.
- **Untracked on Mac, NOT on aliens** — triage before pushing (committed? generated-at-test? gitignored?):
  ```
  socket-quic/src/jvmTest/resources/certs/localhost.{crt,key}
  socket-quic/src/androidInstrumentedTest/resources/certs/localhost.{crt,key}
  socket-quic/testcerts/{cert.p12, localhost.crt, localhost.key, localhost.p12}
  ```
  The new W3C cert-constraint suites likely depend on these. Confirm load-bearing-ness.

---

## 2. buffer 6.0.0 — the release blocker (verified 2026-06-23)

socket builds against `buffer = "6.0.0-SNAPSHOT"` from `mavenLocal()`. The reshape (ByteSource/
ByteSink/Sender + Read/WritePolicy) socket needs is **NOT on buffer `main`** — it's in **open PR #213**
(`redesign/buffer-6.0`, repo `~/git/buffer`).

PR #213 verified state:
- `mergeable: MERGEABLE`, `mergeStateStatus: CLEAN`, **all 6 CI checks pass** (Apple, Linux/JVM/JS/
  WASM/Android, Android API 21+35, artifact validation, docs).
- Labeled **`major`** → merging publishes **6.0.0** to Central.
- Small reviewed diff (5 files): the 4 buffer-flow reshape files + a buffer-crypto build change that
  registers **both** linux targets (intentional — so the `linuxArm64` klib publishes; socket-quic-quiche's
  linuxArm64 sha256 seam-swap needs it). **Not a temp hack.**
- The "7 TEMP Apple-gate edits to revert first" from old memory notes is **STALE** — not present in
  #213, and the socket-side files flagged were false-positive comment matches. Ignore that note.

**Caveat (why "merge it" isn't the whole story):** the local SNAPSHOT socket is green against was
published **Jun 18**. buffer `main` has merged ~4 crypto/CI commits since (#209/#211/#210/#212). The
released `6.0.0 = main ⊕ #213` will therefore be a *superset* of what socket actually tested against.
Low risk (crypto perf/CI), but → **after merge, repin and re-run the full socket matrix; do not assume
the SNAPSHOT-green carries.**

### Release path
1. Merge buffer PR #213 → publishes Central `6.0.0`.
2. In socket: set `buffer = "6.0.0"` in `gradle/libs.versions.toml` (drop the `-SNAPSHOT`).
3. **Remove `mavenLocal()` from all 8 build files** (verified list):
   `build.gradle.kts` (root), `socket-quic`, `socket-quic-quiche`, `socket-quic-nw`,
   `socket-quic-default`, `socket-http3`, `socket-webtransport`, `socket-testsuite`.
   (Each has a `// TODO(v6-release): drop mavenLocal()` or equivalent.)
4. Keep the buffer-crypto Linux-collision fix intact: `expect/actual sha256Into`, buffer-crypto dep
   scoped to `commonJvmMain` only (linux→`ditchoom_sha256` cinterop). Do NOT widen buffer-crypto to
   linuxMain or the static-BoringSSL duplicate-symbol SIGSEGV returns.
5. Full matrix green against real 6.0.0 = the clean-release gate.

---

## 3. WebTransport multiplexing — code DONE, validation is the gap

### What is real (production, all platforms — confirmed by reading the code)
- **Stream-mux within a session**: `socket-http3/.../WebTransportMux.kt` — `openBidi`/`openUni`, peer
  stream demux onto per-session flows, datagrams (RFC 9297 Quarter Stream ID), capsule close/drain.
- **Session-mux over one held connection**: `socket-webtransport/.../WebTransportSupportHttp3.kt` —
  `WebTransportSupport.Multiplexed.connectMultiplexed()` → `MultiplexedWebTransport.openSession()`.
  Fork-2 held-scope lifecycle (detached `SupervisorJob` wrapping `withHttp3Connection`; `QuicScope`
  scope-only invariant preserved; single `connect()` = held conn with one session).
- **Server role**: `Http3ServerConnection.acceptWebTransport` + `WebTransportServerExchange`.
- **Browser** (js/wasmJs): wraps the native `WebTransport` object (`browserMain`).

### What is NOT validated — the actual gap
`WebTransportTestSuite` (in `socket-testsuite/src/commonMain`, **3 tests**: bidi round-trip,
two-sessions-over-one-connection, idempotent close) runs in only **two** places:
`socket-webtransport/src/{jvmTest, linuxX64Test}`. The build declares ~15 prod targets.

| Target | Prod code | Test subclass | Action |
|---|---|---|---|
| JVM | ✅ | ✅ `JvmWebTransportTest` | — |
| linuxX64 | ✅ | ✅ `LinuxWebTransportTest` | — |
| **Apple** (macos/ios/tvos/watchos, via socket-quic-nw) | ✅ | ❌ **none** | **add `AppleWebTransportTest`** — highest risk |
| **Android** | ✅ (shares http3Main) | ❌ none | add instrumented subclass |
| **Browser** (js/wasmJs) | ✅ | ❌ none | separate strategy — see fork below |
| linuxArm64 | ✅ | ❌ | add if a runner exists |

**Why Apple is the real blocker:** this project has a history of Apple/NW QUIC bugs that only surfaced
on hardware (`reset()`→FIN, keepalive silent no-op, the `-9808` saga). "Compiles on Apple" ≠ "multiplexed
WebTransport works on Apple." The `multiplexed_twoSessionsOverOneConnection_eachRoundTrip` test is the
documented DONE bar (suite docstring) — it must go green on Apple.

### Test architecture — DECIDED (don't re-litigate, but verify on Apple)
The logic is *already* common (abstract suite in `socket-testsuite/commonMain`). Per-platform subclasses
supply only glue that **cannot** be common: `testTlsConfig()` (cert paths), `openSingleSession`/
`openMultiplexed` (dial via the `http3Main` native config overload, unnameable from commonMain),
`wrapTestBody()` (skip hook). This mirrors the QUIC/h3 suites — correct, keep it.

**Hard constraint:** the suite stands up an in-process `withHttp3Server` (needs real QUIC), so it CANNOT
attach to `commonTest` (browser has no in-process QUIC server; `socket-testsuite` has no js/wasmJs
targets). It attaches per-platform via `dependsOn`/`implementation(project(":socket-testsuite"))` —
see `socket-webtransport/build.gradle.kts` ~line 160.

- **Apple subclass:** mechanical — mirror `JvmWebTransportTest`/`LinuxWebTransportTest`. `socket-testsuite`
  already declares all Apple targets. Resolve cert-path loading on Apple (bundle resources). Run on Mac.
- **Android subclass:** `androidInstrumentedTest`; on-device proof (matches the existing Android
  cert-pinning on-device tests).
- **Browser FORK — DECIDED (user, 2026-06-23): layered Karma + Chrome DevTools MCP.**
  Browser can't run the server-based suite in-process. The plan:
  - **Primary (automated coverage):** run the js/wasmJs WebTransport tests in **real headless Chrome
    via Karma** (`browser { testTask { useKarma { useChromeHeadless() } } }`) — this exercises the
    *actual Kotlin `browserMain` wrapper*, not hand-written JS. The browser connects to an
    **externally-launched `withHttp3Server`** (browser has no in-process QUIC server) presenting the
    **`pinned` fixture (EC P-256, 13-day)** — the browser's `serverCertificateHashes` accepts a
    self-signed cert ONLY if it's ECDSA P-256 + ≤14-day validity, which `pinned` already satisfies.
    WebTransport needs a secure context → serve the test page on `http://localhost` (localhost is
    secure). Wire the SHA-256 of `pinned`'s DER as the `serverCertificateHashes` value (the browser
    bridge for this already exists: js `asUint8Array` / wasmJs `toJsUint8Array`).
  - **Bring-up / debugging:** use the **Chrome DevTools MCP** (available in this env) to drive a real
    Chrome interactively — navigate to the page, `evaluate_script` a `WebTransport` client (open
    bidi/uni, round-trip, multiple sessions), read results — to prove the path works before/while
    wiring Karma.
  - **Fallback** if Karma proves too heavy: MCP-driven first-proof + document browser as
    validated-by-the-W3C-platform-object with a written gate rationale.
  - Note: MCP-driven Chrome is a local/manual proof (like Apple-on-HW), not a `review.yaml` CI gate;
    Karma-headless *can* be CI-able. Same external-server + P-256-cert constraint applies to both.

### Minor: `allowPooling` no-ops natively
`WebTransportOptions.allowPooling` is documented "not yet acted on" in `WebTransportSupportHttp3.kt`.
A live API surface that silently does nothing. Either implement transparent connection reuse or
document the no-op explicitly. Non-blocking but a consistency wart.

---

## 4. Non-blocking (defer past v6 unless trivially foldable)
Open issues, none release-blocking: **#158** (Apple datagram flake — matches the "fix flaky at root"
directive), #165 (Apple abrupt-close detection), #162 (K/N quiche auto-link), #166 (sealed error types),
#116 (compileSdk 37), #88, #85. `TODO.md`: Happy Eyeballs, IPv6/multi-address DNS, macOS harness CI,
`SniStrictHostsTest`. Dependabot PRs #171, #172.

---

## 5. Recommended order for the fresh session
1. **Push** the branch (back up the 18 commits) + triage the untracked testcerts. FF aliens.
2. **Apple `AppleWebTransportTest`** — write it, run on Mac (or aliens for linuxArm64). Closes the
   highest-risk gap and proves multiplex on Apple = the DONE bar.
3. **Android** instrumented subclass.
4. **Browser** — implement the decided plan (Karma headless Chrome vs externally-launched
   `withHttp3Server` + `pinned` P-256 cert; Chrome DevTools MCP for bring-up). See §3 browser fork.
5. **`allowPooling`** — implement or document.
6. Merge **buffer PR #213** → repin socket to `6.0.0` + drop 8× `mavenLocal()` → full matrix green.
7. Release.

Verify everything against the live tree — this handoff reflects state at `0a46bde` / buffer #213 head
`812c098a`; re-check before acting.
