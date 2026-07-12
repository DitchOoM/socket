# socket — open follow-ups

Tracked here so the gaps from each session aren't lost.

## ▶ NEXT SESSION KICKOFF — UDP support + read-timeout contract (2026-07-12)

Two workstreams were scoped this session. Both are **specced but not started**. Read these first:

- **`RFC_READ_TIMEOUT_CONTRACT.md`** (committed `5f6f334`, branch `rfc/read-timeout-contract`) — the read-timeout contract + per-platform conformance gaps + phasing. This is the TCP workstream's spec.
- Memory: `project_read_timeout_contract` and the UDP-infra exploration (four of five platforms already have internal `UdpChannel` impls inside `:socket-quic-quiche`; JS/Node has none).

### The two workstreams are NOT independent — settle the shared seam first

Both need `TransportConfig` at **socket-allocation** time, and today `ClientSocket.allocate()` runs *before* config exists (config arrives at `open()`; see RFC §7):
- UDP needs it to pick datagram-vs-stream.
- The TCP work needs it for the `IoConcurrency` / conformant-impl choice.

**Decide the allocation seam ONCE before fanning out** — either a JVM/per-platform *deferring wrapper* (`allocate()` returns a thin socket that resolves the impl in `open()`), or change the `expect`/`actual` to `allocate(config)` (cleaner, but ripples all five platforms). If two subagents redesign this in parallel they **will** collide on `allocate()` / `TransportConfig`.

### Suggested structure (fresh session)

1. **Decide + land the allocation seam** (small, shared prerequisite).
2. **Fan out** — ordering is not pure-parallel:
   - **TCP / read-timeout subagent** — Phase 1 of the RFC first: build the deterministic **"silent peer" harness** (connect, then never write/close) + the red-baseline `commonTest` matrix (RFC §6). This harness is *shared foundation* — the UDP tests want it too — and doubles as the first step of TCP↔QUIC test-harness parity. Then work RFC §8 phases: fix exception types → enforcement → destructiveness.
   - **UDP subagent** — Phase 1: connected-peer datagram API + hoist the existing internal `UdpChannel` impls (NIO `DatagramChannel`, Apple `NWConnection`-UDP + POSIX server, Linux io_uring) out of `:socket-quic-quiche` into a shared/lower module. **Node `dgram` deferred** (user decision — do it in Phase 2, unconnected). Must NOT unilaterally redesign the allocation seam.

Constraint reminder: no `ByteArray` in `*Main/` (CLAUDE.md); Apple lanes validate only on macOS CI (no Mac on this box).

## Test-harness migration — closed (2026-05-23)

Phases 0–5 of `TESTING_STRATEGY.md` are done. The work that landed:

- Phase 0 — flake reduction (commit `5421d42`).
- Phase 1 — harness skeleton: `test-harness/`, `generateHarnessConfig`, `HarnessConformanceTests` (`e3569a5`, `f51b719`).
- Phase 2 — TLS cert matrix: 5 certs × 2 scenarios = 10 conformance tests; cert-rejection family migrated (`9d55cd3`, `0450fd0`, `c4f4889`).
- Phase 3 — toxiproxy / L4 fault injection: `Toxiproxy` helper, deterministic broken-pipe regression, partial-read slicer test, `TlsErrorTests` sweep (`0bff529`, `3f9c917`, `a0d3962`).
- Phase 4 — netem + TLS-1.3 + QUIC: `netem-blackhole`, sixth nginx vhost, `quic-echo` container + `:socket-quic:quicEchoJar`, `QuicHarnessIntegrationTests`, `SimpleSocketTests`/`TlsValidPathConformanceTests` migrations (`d520030`, `3ea5887`).
- Phase 5 — CI fold-in + tlsDefault() switch: arch matrix in `review.yaml`, per-platform CA injection, `integration.yaml` deleted, `jsNodeTest` wired, valid-path tests on `tlsDefault()` (`7c44876`, `20313af`).

See `TESTING_STRATEGY.md` §6 for the full per-phase summary.

### Carried out of migration scope

- [x] **Subtype-precise `SocketClosedException` cross-platform regression.** ~The three `ExceptionConformanceTests` cases assert the sealed *parent* class only because toxiproxy 2.12's `reset_peer` toxic produces either EPIPE (BrokenPipe), ECONNRESET (ConnectionReset), or graceful EOF (EndOfStream) depending on kernel scheduling…~ Partially addressed: the read-path test (`pendingReadDuringPeerReset_producesSocketClosedException`) now drives the new `rst` sidecar (`test-harness/rst/`) which calls `setsockopt(SO_LINGER, on=1, linger=0)` + `close()` directly, producing a deterministic TCP RST. Connecting via the pinned bridge IP (`172.30.0.98`) bypasses docker-proxy so the RST reaches the client unmodified. The write-path tests still assert the parent class because toxiproxy is what's driving the write-after-reset/down — tightening those would need a different fixture shape.
- [ ] **Delete the `@Ignore`'d public-host originals throughout `TlsErrorTests`, `ExceptionIntegrationTests`, `JvmExceptionSubtypeTests`, `QuicIntegrationTests`, etc.** Kept in place under the green-throughout rule (TESTING_STRATEGY.md §6) until Apple CI proves the harness equivalents at least once on `macos-latest`. Sweep with a follow-up PR after the first green `review.yaml` run on this branch.
- [ ] **`SniStrictHostsTest` migration.** Needs separate `server_name`-only nginx vhosts (no `default_server` fallback) on port 443 to exercise SNI strictness. The `tls` container's `valid` block already serves `valid.test` + `localhost` as aliases; an additional pair of strict vhosts will let the migration land. Out of scope for this PR.
- [ ] **IPv6-only / multi-address DNS coverage** (`LinuxClientSocket` addrinfo iteration). Deferred during Phase 4: `LinuxClientSocket.open()` builds the addrinfo struct inside `memScoped` cinterop with no injection seam, and a real dnsmasq fixture would need root or container-side test execution. Ships when either (a) `LinuxClientSocket` grows a testable injection point, or (b) container-side test execution lands.
- [ ] **Full RFC 8305 Happy Eyeballs racing.** Current Linux addrinfo iteration is sequential — if the first IPv6 address SYNs out, the user waits ~75 s for the v4 fallback. Real Happy Eyeballs races A and AAAA in parallel with a small "resolution preference" delay. Not blocking, latency-sensitive future work.
- [ ] **Cross-platform parity test for addrinfo iteration.** JVM (`AsynchronousSocketChannel`) and Apple (`NWConnection`) iterate addresses internally; a unit test on each platform that documents the behavior would be useful once an injection seam exists.

## CI follow-ups (PR #48 CI rework)

- [ ] **macOS harness coverage.** All three fixture paths attempted on
  `macos-latest` failed:
    - **Path A** (homebrew socat + nginx): Apple Network.framework throws
      `SSLProtocolException` on every TLS handshake against native nginx
      (12 tests). Root cause unclear — possibly an ALPN or extension
      negotiation gap.
    - **Path B** (Apple `container` 0.12.3): CLI surface incomplete; no
      `container system start` subcommand, exit 64.
    - **Path C** (Colima `--vm-type=qemu`): Lima host agent crashes on the
      current macos-latest image (`exit status 2`); VZ driver also broken
      (the original handoff symptom).
  Current state: `HARNESS_DISABLED=true` set in `build-apple.yaml`, harness-
  backed Apple K/N tests skip via `isHarnessAvailable()`. Cross-platform
  contracts are still validated on Linux JVM/K-Native/jsNode. Re-evaluate
  when (a) macos-13/14 runners reopen with Colima support, (b) Apple
  `container` grows a compose plugin, or (c) we adopt a self-hosted Mac.
- [x] ~**Linux K/Native TLS hostname verification.** `LinuxClientSocket` (BoringSSL via cinterop) validates the cert *chain* but doesn't enforce SAN/hostname matching on top…~ Done — `ssl_set_verify_host` wrapper in `src/nativeInterop/cinterop/LinuxSockets.def` picks between `X509_VERIFY_PARAM_set1_host` (DNS) and `X509_VERIFY_PARAM_set1_ip_asc` (IP literal) by probing the hostname with `inet_pton`, and is called from `LinuxClientSocket.initTls` when `currentTlsConfig.verifyHostname` is on. `tlsHarnessWrongHostFailsWithDefault` re-enabled on linuxX64; `isLinuxNative()` skip-gate removed across all test source sets.
- [x] ~**`socket-quic:jvmTest` quiche-0.28 panic.** Aborts with a non-unwinding panic from `quiche_conn_recv` (`quiche/src/ffi.rs:2059:14`) → SIGABRT, exit value 134.~ **Done** (commit `5e55a5f`) — root cause was JVM sockaddr buffers GC'd while quiche held raw pointers; fix retains them via `onCleanup` closure on `QuicheDriver`. Also `cfadcdc` made `JvmQuicServer.connections()` use structured concurrency (`coroutineScope`) so handler lifetime is bound to the caller, preventing orphaned handlers from deadlocking driver shutdown.
- [ ] **CI-only handshake hang on late-suite jvmTest tests.** Originally 9 tests skipped on GH Actions ubuntu-24.04 runners via `assumeTrue(System.getenv("CI") == null || System.getenv("RUN_FLAKY_TESTS") == "1")`:
    - `QuicStreamMuxTests.bidirectionalStreamMuxExchange`
    - `ServerConnectionTimingTest.serverHandlerRunsOnClientConnect`
    - `ServerConnectionTimingTest.serverAcceptsStreamFromClient`
    - `ServerConnectionTimingTest.scopeBasedEchoRoundTrip`
    - `StaleConnectionDiagnosticTests.twoSequentialEchoConnectionsWork`
    - `StaleConnectionDiagnosticTests.noStreamConnectionThenEchoConnection`
    - `StaleConnectionDiagnosticTests.multipleNoStreamConnectionsThenEcho`
    - `StaleConnectionDiagnosticTests.connectionsByDcidIsCleanedUpAfterConnectionClose`
    - `StaleConnectionDiagnosticTests.immediateReconnectAfterNoStreamConnection`

    **2026-05-27 update: root cause located, partial mitigation landed.** The hang is **resource-pressure-driven** — tests across ~10 classes create `QuicServerEngine` / `QuicEngine` without `close()`, each leaking a scope + ~2 worker threads + a native quiche state block. On the small (2-4 core / 7 GB) GH runner the threshold crosses around the 130th test and coroutine dispatchers starve, so the 10s `withTimeout` on `awaitEstablished` fires. Dispatcher-starvation evidence: `ReactiveDriverTests.streamRecv_returns_data` (stub-API test, no engine of its own) hung past its own `withTimeout(2.seconds)` for 4 minutes in CI run `26482387255`. Local boxes have so much headroom the threshold never crosses. See `HANDOFF.md` for the full bisection log.

    Landed 2026-05-27 morning:
    - Buffer 5.0.0 → 5.2.0 (commit `fd9c308`).
    - Cargo `target/` cache (~3 min CI savings, commit in revert).
    - `@AfterTest` engine cleanup in `JvmQuicServerTestSuite` + `JvmQuicServerLifecycleTestSuite` (commit `5b3905b`). Partial — ~10 other suites still leak.
    - `timeout-minutes: 8` guard on `:socket-quic:jvmTest` step so one hang can't burn the 60-min job budget.

    Landed 2026-05-27 afternoon (API redesign step 1):
    - Scope-only engine construction in commonMain — `withQuicEngine { engine -> ... }` and `withQuicServerEngine { engine -> ... }` close the engine in a `finally` so leaks aren't possible by construction (commit `4b615a8`).
    - JVM `Cleaner` safety net — `JvmEngineCleaner` registers each engine's `SupervisorJob` with `java.lang.ref.Cleaner` so engines that escape the scope-only API still get GC-paced cleanup (same commit).
    - `StaleConnectionDiagnosticTests` migrated to the new lifecycle and its `assumeTrue(CI == null || RUN_FLAKY_TESTS)` gate dropped (commit `3014388`). 5 of the 9 gates closed in this commit.
    - Engine-lifecycle direction documented in `socket-quic/DRIVER_REDESIGN.md` (commit `9f572f8`).

    Landed 2026-05-27 evening (API redesign step 2 — done):
    - `f67aebd` — Remaining 4 gates migrated to `withQuicEngine` /
      `withQuicServerEngine` via the same `withEngines` helper as
      `StaleConnectionDiagnosticTests`. All 9 `assumeTrue(CI == null ||
      RUN_FLAKY_TESTS)` gates are gone; no source file reads
      `RUN_FLAKY_TESTS` at runtime.
    - `f1c736e` — Abstract `QuicServerTestSuite` /
      `QuicServerLifecycleTestSuite` switched from `serverEngine()` /
      `clientEngine()` factory methods to `withServerEngine` /
      `withClientEngine` block-takers. `JvmQuicServerTestSuite` +
      `JvmQuicServerLifecycleTestSuite` `@AfterTest` engine-tracker
      retrofits (commit `5b3905b`) deleted — scope-only construction
      closes the leak by construction. `LinuxQuicServerTests`
      delegates straight to the commonMain helpers.

    Full local `:socket-quic:jvmTest` stays at 162 tests / 0 skips / 0
    failures. Awaiting CI confirmation on PR #48.

    **2026-05-27 night update: engine-leak hypothesis was a contributor,
    not the root cause.** CI run `26514310996` on `dcfbc37` (lifecycle fix
    + all 9 gates open) failed at
    `QuicStreamMuxTests.bidirectionalStreamMuxExchange` with the same 15s
    outer `withTimeout` shape. 129 tests passed before the hang — right
    at the original ~130-test threshold the HANDOFF identified. Build
    halted at the first failure (no `--continue` then), so the other 8
    ex-gated tests' CI status is unknown from that run.

    The mux test was flagged in the HANDOFF as a separate-shape issue
    ("something inside the codec / CodecConnection / mux path that we
    couldn't pin down across 8 CI cycles"), and this CI signal supports
    that: non-mux QUIC handshake tests (`QuicConnectionTests`,
    `QuicLocalServerTests`, `JvmQuicServer*TestSuite` etc.) all passed in
    the same run.

    Landed 2026-05-27 night:
    - Re-gated `QuicStreamMuxTests.bidirectionalStreamMuxExchange` only,
      with a comment noting it's a separate mux/codec issue (not the
      engine-leak shape). The other 8 ex-gated tests remain ungated.
    - Added `--continue` to the `:socket-quic:jvmTest` Gradle invocation
      in `build-linux.yaml` so a single hang no longer hides the rest of
      the suite. With this, the next CI run reveals whether the lifecycle
      fix held for the other 8 tests, or whether they also still hang.

    **Open questions, pending next CI signal:**
    - Do the other 8 ex-gated tests pass on CI now? If yes: lifecycle
      fix worked for the dispatcher-starvation surface, mux is genuinely
      separate. If no: dispatcher starvation has a deeper cause and the
      engine-leak fix was structural cleanup but not behavioural.
    - What is different about the mux/codec handshake path? Worth
      drilling into once the broader signal is in.

    **Note on follow-up planning (no-engine refactor):** discussed but
    *not* started. The structural over-engineering (engine layer that
    quiche has no analog for, 3 scope layers, reactive command channel)
    is real but its removal is a sizable refactor. Worth doing only once
    we understand whether the dispatcher-starvation symptom has a deeper
    cause — flattening the API doesn't help if the leak is somewhere
    else (e.g. native heap from quiche, NIO selector pile-up, GC pressure).
- [ ] **Windows JVM Tests mapping gaps** (`JvmExceptionMapping.kt`). Six tests currently skip on Windows via `isWindowsJvm()`:
  - `ExceptionIntegrationTests.tlsToNonTlsServer_producesSSLSocketException` — Windows surfaces neither `SSLSocketException` nor `SocketClosedException`; need to detect the JSSE shape that escapes through the channel-close race.
  - `ExceptionIntegrationTests.connectionRefused_producesSocketConnectionException` + `connectionRefused_exceptionHasUsefulMessage` + `JvmExceptionSubtypeTests.connectionRefused_isSocketConnectionExceptionRefused` — Windows NIO2 holds the connect past the test's 2 s budget instead of returning ECONNREFUSED. Either tighten the wrapper to surface ECONNREFUSED faster, or extend the test budget on Windows.
  - `ResourceCleanupTests.repeatedOpenClose` — Windows IOException routes through the `closed`-message branch and produces `SocketClosedException.General` mid-test. Distinguish Windows-NIO2's "socket already closed" from "kernel just closed it under us".
  - `ResourceCleanupTests.socketClosedAfterUseBlock` — same shape as `repeatedOpenClose`: end-of-test `server.close()` races against `WindowsAsynchronousServerSocketChannelImpl.implAccept`'s pending continuation, surfacing `ClosedChannelException` that JvmExceptionMapping wraps as `SocketClosedException.General`. Forecast in HANDOFF.md and landed in CI run 26534024939; same `isWindowsJvm()` skip applied 2026-05-27 night.
- [ ] **Windows quiche JNI native.** `build-linux.yaml` cross-compiles `quiche_jni.dll` via MinGW but the result is never staged into JAR resources (the `nativeLibsByPlatform` map in `socket-quic/build.gradle.kts` excludes `windows-*`). `socket-quic:jvmTest` is excluded from the Windows job until this lands.
- [x] ~**`pendingReadDuringPeerReset_producesSocketClosedException` deterministic fixture.** Currently `@Ignore`'d (commit `421a676`) — flakes on GH ubuntu-24.04 runners. Replace toxiproxy's `reset_peer` with the sidecar RST-only fixture proposed above ("Subtype-precise `SocketClosedException` cross-platform regression") and re-enable.~ Done — `test-harness/rst/` sidecar drives a deterministic RST on every kernel, test re-enabled. Validated 3× back-to-back on JVM/linuxX64/jsNode locally.

## Other (pre-existing)

Carried from earlier sessions / memories. Not introduced by the harness migration.

- [ ] socket-quic pool ownership audit — see [[socket_quic_recvbufpool_bug]] for the regression that started this thread; the fix landed in commit `80575c1` but the pool-sharing contract isn't formally documented.
- [ ] **Exhaustive (non-stringly-typed) error causes at platform boundaries.** The QUIC layer is the gold standard — `QuicError` is a sealed hierarchy (`code: Long` + structured data classes) and `QuicCloseException` carries it so callers switch on the protocol cause without parsing a message. The socket-core boundary still flattens the *leaf cause* to a free-form `platformError: String?` (`SocketConnectionException.Refused`, the Apple NW engine's `"Failed to create QUIC connection group"`, JVM errno/`SSLException` text, Linux errno text, Node error codes). Because the same condition (ECONNREFUSED, ENOMEM, NW `-9808`/`errSSLBadCert`, host/network-unreachable, TLS-handshake) becomes a *different string per platform*, callers can't `when`-exhaustively recover and cross-platform handling drifts — directly the cause of the 6 Windows JVM exception-mapping skips above (Windows surfaces a different exception *shape/string*). Proposed: introduce an exhaustive sealed/enum cause (e.g. `ConnectionFailureReason { Refused, HostUnreachable, NetworkUnreachable, OutOfMemory, TlsBadCert, TlsHandshake, Timeout, Unknown(raw) }`), have every platform mapper (`JvmExceptionMapping`, Linux errno map, Apple NW/Sec map, Node) produce it, and keep the raw platform string only as non-discriminating diagnostic detail. Turns the Windows divergences into mapping-table gaps, not string-shape guesses. Cross-cutting — its own phase; align with `ReconnectionClassifier` (which already classifies `Throwable → ReconnectDecision`). User-requested direction 2026-06-15. Tracked as issue #166.
