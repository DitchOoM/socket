# v6 Phase 4 — release-readiness handoff (WebTransport gaps + buffer 6.0)

Authoritative handoff for closing out **v6** on branch `redesign/major-api-v6`.
Written 2026-06-23 after a full re-investigation. Read this first in the fresh session.

---

## ⏩⏩⏩⏩ SESSION 5 UPDATE (2026-06-23 night) — read FIRST; this is the live cross-impl-interop work

**Branch `redesign/major-api-v6`. SESSION 5 is COMMITTED + PUSHED** as two commits on top of SESSION 4:
- `0cd87a2` feat(v6/quic): capture typed QUIC close reason (peer/local error, IdleTimeout)
- `c134814` test(v6/phase4): cross-implementation interop harness (quiche <-> Network.framework)

(SESSION 4's `314fb94`+`ab0add1` were also pushed in this push.) The bug below is NOT yet fixed — it's the
next-session target. Files that landed in these two commits:
```
 M socket-quic/.../QuicError.kt              # + QuicError.IdleTimeout, + fun describe()
 M socket-quic/.../QuicCloseException.kt      # message auto-appends [describe()] when error != NoError
 M socket-quic/.../(commonTest) QuicErrorTests.kt   # +3 tests (idleTimeout, describe, msg-append)
 M socket-quic-quiche/.../QuicheApi.kt        # + connPeerError()/connLocalError() (default null)
 M socket-quic-quiche/.../QuicheDriver.kt     # transitionToClosed → resolveCloseError() (peer/local/timeout)
 M socket-quic-quiche/.../FfmQuicheApi.kt     # bind quiche_conn_{peer,local}_error (readConnError)
 M socket-quic-quiche/.../CinteropQuicheApi.kt# same for linux (UNVERIFIED on Mac — needs aliens/CI)
 M socket-quic-quiche/.../(commonTest) StubQuicheApi.kt     # + peerError/localError/timedOut vars
 M socket-quic-quiche/.../(commonTest) ReactiveDriverTests.kt# +3 driver tests (peer/local/timeout capture)
 M socket-webtransport/.../(jvmTest) BrowserInteropServer.kt # + wt.interop.cert (cert|pinned), datagrams= line
?? socket-webtransport/scripts/cross-impl-interop.sh                 # orchestrator {nw-server|quiche-server}
?? socket-webtransport/.../(appleTest) NwInteropServer.kt / NwInteropClient.kt / NwInteropIo.kt
?? socket-webtransport/.../(jvmTest) QuicheInteropClient.kt
```
All JVM + macosArm64 compiles GREEN; `QuicErrorTests` + `ReactiveDriverTests` GREEN (6 new tests). ktlint clean.

**WHAT THIS SESSION DID** (the NEXT-PHASE plan's cell #1 = cross-IMPLEMENTATION interop, quiche ↔ Network.framework over localhost, two processes):

1. **Built the cross-impl harness** (decisions made with user: gated tests + script; runtime file/env config;
   defer iOS; separate CI workflow — CI workflow NOT yet written). `cross-impl-interop.sh nw-server` runs an
   NW K/N server (`macosArm64Test`, gated by `WT_INTEROP_SERVER` env) ↔ quiche JVM client
   (`QuicheInteropClient`, gated `-Dwt.interop.client`). `quiche-server` runs the reverse (reuses
   `BrowserInteropServer` with `-Dwt.interop.cert=cert`) ↔ NW K/N client (`NwInteropClient`, env
   `WT_INTEROP_CLIENT`). Server `--no-daemon` (env staleness + blocking), config via
   `build/wt-interop/cross-impl-config.properties` (its existence = readiness). DONE-bar shape: 2 sessions over
   1 held conn, each bidi round-trips + a uni. Cross-impl is STREAMS-ONLY (NW datagram limit, issue #173).

2. **RESULT: 3 of 4 cross-impl confirmations pass.** ✅ quiche-server ↔ NW-client (real round-trip,
   `WT_CLIENT_OK`). ✅ both loopbacks (existing). ❌ **NW-server ↔ quiche-client** — see THE BUG below.

3. **Typed close-error reporting (a user ask, DONE + validated).** Was: `QuicheDriver.transitionToClosed()`
   recorded `Closed(null)` always → every `QuicCloseException` said `NoError`. Now: bound
   `quiche_conn_peer_error`/`quiche_conn_local_error` (FFM + cinterop; JNI/Android keep the interface default
   `null`) → `resolveCloseError()` maps the wire code to the **sealed `QuicError`** (peer pref, then local,
   then `IdleTimeout` if `connIsTimedOut`). Added typed **`QuicError.IdleTimeout`** (code -1; idle timeout was
   masquerading as a clean `NoError` close). `QuicCloseException` now appends `[error.describe()]` to its
   message. User directive honored: **errors stay the exhaustive sealed `QuicError`, never strings** (the only
   string is the advisory RFC 9000 §19.19 reason phrase, which we deliberately drop). The interop failure now
   reports `connection closed [IdleTimeout (0x-1)]`.

### 🔴 THE BUG — NW-server ↔ quiche-client QUIC handshake DEADLOCK (next session's target)
> **The user's stance (correct): Apple/NW must interoperate with quiche. This is a real bug to FIX, not document.**

**Symptom:** a quiche JVM client dialing an NW K/N server idle-times-out at exactly 10s (its `idleTimeout`);
the NW server's `onWebTransport` never fires (no `WT_SESSION_ACCEPTED`). The QUIC handshake never completes.

**Root cause (proven via `log stream --predicate 'subsystem == "com.apple.network"'` on the NW server):**
The NW server **receives** the client's Initial on `lo0`, starts QUIC `[C1]`, runs the **full** TLS server
handshake (ServerHello 90B, EncryptedExt 117B, **Certificate 766B**, CertVerify 264B, Finished 36B — all
produced by boringssl), then hits:
```
quic_conn_can_send_frames [C1] client address not validated. can't send more data.
quic_crypto_send [C1] unable to send CRYPTO frames
```
This is the **RFC 9000 §8.1 anti-amplification limit** (server may send ≤ 3× bytes received from an
unvalidated client). **The client is NOT at fault — measured (temp trace in flushOutgoing): it sends two
correctly-padded 1200-byte Initials** then small ACKs. 3×1200 = 3600B budget should dwarf the ~1.3KB server
flight, yet the server blocks after only emitting ServerHello. So **the NW connection-group server is
crediting only ~ServerHello-worth (~a few hundred B), not the full received 1200B Initial(s)**, toward its
amplification budget. The client ACKs the ServerHello (Initial level) but never receives the server's
Handshake packets (Cert/Finished), so it can't produce its own Finished, never escalates to Handshake level,
and idle-times-out. **Deadlock, NW-server-side.** Note a CID handoff happens right before the block: the
listener `[L1]` accepts, then `[C1]` adopts a NEW 20-byte SCID — suspect the received-byte credit is lost / not
carried across that `nw_listener`→connection (connection-group) handoff, so budget ≈ 0.

**Why it matters / why it's NW-group-specific:** our NW server is built on the raw `nw_connection_group` QUIC
API (needed for WebTransport multi-stream/datagram), not a plain `NWListener`. NW↔NW loopback works because
NW's own client uses a path/CID flow that doesn't trip this; a quiche client's standard handshake does.

**FRESH-SESSION PLAN (fix NW server ↔ quiche client):**
1. **Reproduce + watch NW internals** (≈90s/run): set `JAVA_HOME` (see below), `export PATH="$HOME/.cargo/bin:$PATH"`,
   then `./socket-webtransport/scripts/cross-impl-interop.sh nw-server`. In parallel capture NW logs:
   `log stream --level debug --predicate 'subsystem == "com.apple.network"' > /tmp/nwlog.txt` and
   `grep "test.kexe" /tmp/nwlog.txt | grep -iE "quic|boringssl|amplif|validate|address"`. The K/N server's
   stdout (`WT_SERVER_*`) lands in `socket-webtransport/build/test-results/macosArm64Test/*.xml`; the client
   exception in `.../jvmTest/*QuicheInteropClient*.xml`.
2. **Cheap confirmation experiment:** swap the NW server cert for a TINY leaf (e.g. small EC, no chain) so the
   server flight fits within even a small budget. If the handshake then completes, the amplification-credit
   accounting is *definitively* the cause and the fix is to make NW credit received bytes (or avoid the
   group-handoff credit loss).
3. **NW-server-side fixes to try** (in `socket-quic-nw/.../WithQuicServer.apple.kt` + `nw_quic_helpers.h`):
   - How the `nw_connection_group` / listener is configured for inbound — is there an address-validation or
     amplification-related parameter? Does NW expect us to drive a **Retry** for validation?
   - Whether the listener→connection (CID change) handoff drops the received-byte credit; can we keep the
     connection on the original path/CID, or pre-validate.
   - Compare against a **plain NWListener** (non-group) QUIC server as a control — does *that* interoperate
     with quiche? If yes, the bug is the group API; weigh group-vs-listener for the server role.
   - Transport params (recall #112: `initial_max_streams=1024`, `max_udp_payload=1350`) — check none of these,
     or a missing `max_idle_timeout`/validation knob, induce the credit shortfall.
4. **Client-side mitigation (fallback, less ideal):** quiche could break the deadlock by sending an
   ack-eliciting **Handshake-level** packet on PTO (validating the address) once it has handshake keys from
   ServerHello — but the right fix is NW-server-side. Don't lead with this.

**Reusable knowledge:** `log stream` on the NW process (`test.kexe`) is the single best diagnostic — it shows
boringssl + libquic internals directly. The typed-close-error work means the client exception now names the
failure mode (`IdleTimeout`) instead of a bare "connection closed".

**Also still open from the plan (after the bug):** cell #2 NW-server ↔ Chrome (will likely hit the SAME
deadlock — Chrome is a quiche-class external client), separate CI workflow (decided: dedicated macOS
`cross-impl-interop.yaml`, gated). iOS deferred (covered-by-same-NW-code). Then back to the main v6 release
blockers (buffer 6.0 → Central, repin, drop 8× mavenLocal — §2/§6 below).

---

## ⏩⏩⏩ SESSION 4 UPDATE (2026-06-23 latest) — read FIRST; supersedes SESSION 3 where they conflict

**HEADLINE: the WebTransport VALIDATION GAP is CLOSED on all remaining targets — Apple, Android, AND browser are GREEN.** All changes are **uncommitted** on `redesign/major-api-v6` (branch was clean at `67ba524` before this session).

**1. Apple H3 coverage parity (the SESSION-3 next-step) — DONE.** Wired `socket-http3`'s `appleTest` + `AppleHttp3LoopbackTest : Http3LoopbackTestSuite()` + a `generateHttp3TestP12` Gradle task (mirrors `socket-webtransport`'s p12 task). `:socket-http3:macosArm64Test` = **28 tests, 1 skipped, 0 failures.**
- It surfaced a real bug (as predicted): 4 tests awaiting `peerSettings()` timed out. **Root cause = test fidelity, not a product bug:** `Http3LoopbackTestSuite` dialed `withQuicServer` DIRECTLY with raw options (default `PreferDatagrams`), so on Apple the server extracted a datagram flow → killed server-initiated control-stream (SETTINGS) delivery. **Fix:** apply `.forHttp3()` (forces `PreferStreams`) to the suite's server+client `QuicOptions` — exactly what production `withHttp3Server`/`withHttp3Connection` always do. No-op on quiche; JVM stayed 28/28 incl. the datagram test.
- The lone WT-datagram test is `@Ignore`-overridden on the Apple subclass (genuine NW datagram/inbound-stream limitation). Required making `Http3LoopbackTestSuite.webTransport_datagramRoundTrip` `open`.

**2. Android (SESSION-2 test, step 2) — DONE.** `AndroidWebTransportTest` = **3/3 on `Pixel_3a_API_33_arm64-v8a`** incl. the multiplexed DONE-bar. Two ENV fixes (not code): (a) rustup's cargo (has the android Rust std) must precede Homebrew's on PATH for the quiche-android cross-compile; (b) deleted STALE orphan `socket-quic/src/androidMain/jniLibs/*/libquiche_jni.so` (quiche 0.28.0, Apr-22, untracked/gitignored, predates the quiche-backend split) — they collided with `socket-quic-quiche`'s in `mergeDebugAndroidTestNativeLibs`.

**3. Browser (step 3 fork) — DONE, BOTH halves.** (i) **MCP-driven proof:** real Chrome via Chrome DevTools MCP → W3C `WebTransport` + `serverCertificateHashes` (the `pinned` EC P-256 leaf) against our quiche `withHttp3Server`: bidi over two sessions + uni + datagram all round-trip; the server log cross-confirms (`path=/a`,`/b`; `WT_UNI_RECEIVED text=uni-payload`). (ii) **Automated Karma:** `:socket-webtransport:jsBrowserTest` = **2/2 GREEN in ChromeHeadless 149**, driving the production `browserMain` wrapper.
- New, all **gated behind `-PwtBrowserInterop`** so default builds/CI never require Chrome (verified: without the flag the gated source isn't compiled and `generateBrowserInteropConfig` isn't registered): `socket-webtransport/src/jvmTest/.../BrowserInteropServer.kt` (gated `-Dwt.interop.server=true` harness — ephemeral port, pinned cert, echoes, writes `build/wt-interop/config.properties`), `src/jsBrowserInterop/.../BrowserWebTransportInteropTest.kt`, `scripts/browser-interop.sh` (orchestrates server↔jsBrowserTest), and build wiring (`useChromeHeadless`, generated `BrowserInteropConfig.kt`, jsTest srcDir, ktlint exclude of the generated file).
- **CAVEAT:** the `pinned` fixture is valid ~13 days (expires ~2026-07-06). Re-run `:socket-quic-nw:generatePinnedW3cCerts` before future browser runs. Chrome at `/Applications/Google Chrome.app/...` (`CHROME_BIN`). JDK 21 + `CHROME_BIN` + rustup not needed for the JS half.

**STILL OPEN (next session):**
- **File the tracking issue** for the Apple WebTransport-datagram limitation (NW can't deliver datagrams + inbound streams together; documented in `WebTransportSession` KDoc + the Apple `@Ignore`).
- **buffer 6.0.0 → Central** (§2/§6) + repin `5.13.2`→`6.0.0` + drop the 8× `mavenLocal()` — the only remaining v6-release blocker.
- Cross-platform/cross-implementation interop matrix (quiche↔NW↔Chrome) is a possible follow-up — all current suites are SAME-platform loopback; the `BrowserInteropServer` external-server pattern is the template for cross-impl pairs.

---

## ⏭ NEXT-PHASE PLAN (cross-implementation interop + Android/iOS server-in-CI) — for a FRESH session

**Why:** every current suite is SAME-platform loopback (server+client, one process, one impl). Under ~15 targets there are only **3 independent QUIC/H3 impls**: **quiche** (JVM/Android/Linux), **Network.framework** (all Apple), **Chrome** (browser, client-only). Cross-IMPL interop is what guards against divergences like the Apple NW stream-id bugs. Also: explicitly prove **Android + iOS can serve** (incl. to a browser, where feasible).

**Server-capable = JVM, Android, Linux, Apple(macOS/iOS/tvOS/watchOS via NWListener). Browser = client-only.** Matrix (✦ = valuable cross-impl, ✅ = covered by loopback):

| server ↓ \ client → | quiche | Network.framework | Chrome |
|---|---|---|---|
| quiche | ✅ loopback | ✦ #1 | ✦ DONE (JVM↔ChromeHeadless) |
| Network.framework | ✦ #1 | ✅ Apple loopback | ✦ #2 |
| Chrome | — (no server) | — | — |

**Cells to build, by ROI/cost (all localhost on the dev Mac / a macOS CI runner — no 2nd device):**
1. **NW-server ↔ quiche-client AND quiche-server ↔ NW-client** *(highest value, CI-feasible on macOS runner).* Two processes over localhost: a macOS K/N NW server + a JVM quiche client, and vice versa. Directly guards the two-native-backend protocol boundary.
2. **NW-server ↔ Chrome** *(cheap, novel; CI-feasible on macOS runner w/ Chrome).* Reuse the browser harness but run the interop server on **macOS (NW)** instead of JVM (quiche). NW server must present the pinned EC P-256 leaf (same `serverCertificateHashes` constraint).
3. **iOS-sim as server (NW)** *(needs infra first).* iOS-sim shares the host loopback, so iOS-sim NW server ↔ macOS quiche client (or ↔ Chrome) is plausible — BUT iOS-sim QUIC-server tests currently self-skip (`shouldSkipQuicHarnessOnSimulator`) because certs are cwd-relative and unreachable in the sim sandbox; needs certs bundled into the sim app + the never-implemented `standalone.set(false)`/device wiring (see [[v6-phase4-webtransport]] "iOS-SIM CAN'T RUN" note). Effortful; CI-feasible on macOS runners after.

**Server-capability proofs the user explicitly wants:**
- **Android server:** already proven on-device (AndroidWebTransportTest multiplexed = real `withHttp3Server`). Browser↔Android-server is **NOT CI-automatable** — `adb forward` is TCP-only and emulator user-mode net can't host→guest UDP (QUIC); only a **real device on the same LAN** can do browser↔Android. Cover Android-server-for-native-client (have it) + document the browser cell as real-device-manual.
- **iOS server:** functionally fine (same NW code as macOS, proven). The automated proof = cell #3 (do the sim cert-bundling) OR accept macOS-NW as the authoritative Apple-server check + document iOS as covered-by-same-code.

**Design template (generalize the browser harness):** a reusable external-server runner parameterized by backend (the existing `BrowserInteropServer` is the quiche/JVM one; add an NW/macOS K/N server runner) that binds an ephemeral port, presents the pinned cert, and writes `build/wt-interop/config.properties`; a per-impl client runner reads it; a script coordinates (start server → run client → stop). Reuse the generated-`BrowserInteropConfig` injection + `scripts/browser-interop.sh` pattern.

**Hard constraints already learned (don't rediscover):** (a) the server build must be a SEPARATE process from the client/test build — they can't share one Gradle daemon (server build blocks); use `--no-daemon` or a standalone exec. (b) Gradle forks test workers → CLI `-D` doesn't reach them (forward `wt.*` props in the test task, as done). (c) `pinned` cert is 13-day; `:socket-quic-nw:generatePinnedW3cCerts` to regen. (d) browser needs ECDSA-P-256 ≤14d leaf via `serverCertificateHashes`; localhost is a secure context.

**CI wiring fork (settle with user):** a new macOS `review.yaml` job (or workflow) running the localhost cross-impl + NW↔Chrome cells, opt-in/gated like `-PwtBrowserInterop` so it only runs where Chrome/sim exist. Decide: separate workflow vs added job; whether to invest in iOS-sim cert-bundling now or defer.

**Open forks for the fresh session to resolve with the user:** (1) native server-runner shape — keep a gated test, or a real `application`/JavaExec (JVM) + a K/N executable (NW)? (2) reuse generated-Kotlin-config + script orchestration, or a served-file approach? (3) iOS-server-in-CI now vs defer-to-macOS-coverage.

---

## ⏩⏩ SESSION 3 UPDATE (2026-06-23 late) — read after SESSION 4; supersedes SESSION 2 + §1–§3 where they conflict

**HEADLINE: Apple WebTransport DONE-bar is GREEN.** `:socket-webtransport:macosArm64Test`
`AppleWebTransportTest` 3/3 pass, including `multiplexed_twoSessionsOverOneConnection_eachRoundTrip`
(the documented Phase-4 DONE bar). Full `:socket-quic-nw:macosArm64Test` regression suite green (11
suites / 47 tests, 0 failures) — the fix is safe in the hot path for every Apple QUIC stream.

**The blocker was NOT the datagram limitation — it was two Network.framework STREAM-ID bugs** (full
write-up in memory `apple-nw-stream-id-bugs.md`):
1. **Bidi-after-uni misclassified as unidirectional.** `extract_uni_stream` (nw_quic_helpers.h) sets
   `is_unidirectional=true` on options derived from the group; the bidi `extract_stream` passed NULL
   ("group default") and inherited the leaked flag → a request bidi opened after uni streams went out
   uni (wire id 14) → the H3 server routed it to `handleUniStream` → drained → `onRequest` never fired.
   **Fix:** `extract_stream` now copies the group's options and explicitly sets `is_unidirectional=false`.
2. **Client used synthetic stream ids that diverge from the wire.** NW's phantom stream consumes bidi
   id 0, so the first real client bidi is wire id 4, but the client labeled it 0. The WebTransport
   session id IS the CONNECT stream id, so `cliSid=0 != srvSid=4` → the server dropped every WT stream
   (`acceptIncomingBidi` found no session). **Fix:** client `openStream`/`openUniStream` now read NW's
   real wire id via `nw_helper_quic_stream_real_id` AFTER awaiting the flow's ready state (new helper
   `startAndResolveRealId`; the id is nil until the flow is live, so querying right after start is too
   early). Falls back to synthetic on a `STREAM_READY_TIMEOUT` (5s).

**Datagram-conflict fix (the option-A decision, IMPLEMENTED):** new `DatagramStreamConflictPolicy` enum
+ `QuicOptions.datagramStreamConflictPolicy` (default `PreferDatagrams`). `withHttp3Connection`/
`withHttp3Server` force `PreferStreams` via `QuicOptions.forHttp3()`, so the Apple NW backend skips
datagram-flow EXTRACTION (keeps inbound streams) while still advertising `max_datagram_frame_size`. WT
datagrams are therefore unavailable on Apple (documented on `WebTransportSession.sendDatagram`/`datagrams`
KDoc; **still need to file the tracking issue**).

**Files changed this session (uncommitted at time of writing — see close-out below):**
- `socket-quic/.../QuicOptions.kt` — `DatagramStreamConflictPolicy` + field.
- `socket-quic-nw/.../WithQuicConnection.apple.kt` — extract-datagram gating; `startAndResolveRealId` +
  real-id in `openStream`/`openUniStream`; `STREAM_READY_TIMEOUT`.
- `socket-quic-nw/.../WithQuicServer.apple.kt` — extract-datagram gating (mirror).
- `socket-quic-nw/.../nw_quic_helpers.h` — `extract_stream` sets `is_unidirectional=false`.
- `socket-http3/.../WithHttp3Connection.kt` (+ `WithHttp3Server.kt`) — `QuicOptions.forHttp3()` forces
  `PreferStreams`.
- `socket-webtransport/commonMain/.../WebTransportSession.kt` — Apple datagram-limitation KDoc.
- `socket-quic-nw/appleTest/.../AppleQuicUniStreamProbeTests.kt` — 3 NEW regression probes kept
  (`multipleConcurrentInboundStreams`, `lateBidiStream_afterRoundTrip`, `bidiHalfClose_roundTrip`).
- DELETED the throwaway `socket-webtransport/appleTest/.../AppleHttp3DiagnosticTest.kt` (bisection
  scaffolding; superseded by AppleWebTransportTest + the probes).

**COVERAGE GAP STILL OPEN (next session, decided):** Apple runs the 3-test `WebTransportTestSuite` (DONE
bar) + QUIC suites, but NOT the comprehensive `Http3LoopbackTestSuite` (~30 tests: plain GET/POST,
dynamic QPACK, server push, full WT matrix incl. uni streams both directions, close/drain/reset,
middleware) — that runs only on jvm+linuxX64. **NEXT: wire `socket-http3`'s `appleTest` source set + an
`AppleHttp3LoopbackTest : Http3LoopbackTestSuite()`** (mirror `LinuxHttp3LoopbackTest` + a p12 cert task
like socket-webtransport's `generateWebTransportTestP12`). This is real parity AND will surface any
remaining Apple-specific bugs in the untested paths. CAVEAT: that suite's **WT-datagram tests cannot
pass on Apple** (the NW limitation) — override/skip them on the Apple subclass + document.

**Remaining v6 work (unchanged):** Android emulator test (handoff §5.3 / step 2 — `Pixel_3a_API_33`);
browser (§3 Karma + DevTools MCP); buffer 6.0 Central release (§2/§6). Buffer still pinned to mavenLocal
`5.13.2` with 8× `mavenLocal()`.

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
