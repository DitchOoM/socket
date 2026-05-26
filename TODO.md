# socket — open follow-ups

Tracked here so the gaps from each session aren't lost.

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
- [ ] **`QuicStreamMuxTests.bidirectionalStreamMuxExchange` hangs on GH Actions CI.** Skipped on CI via `assumeTrue(System.getenv("CI") == null)`. The bidi mux exchange's QUIC handshake never completes on ubuntu-24.04 hosted runners — diagnostic instrumentation shows neither side gets past `client: connectMux` / `server: connections handler invoked` before the 10s outer `withTimeout` fires. Passes locally in ~108ms (Ubuntu 24.04 WSL2; reproduction attempted with single-CPU JVM `-XX:ActiveProcessorCount=1`, `-Xmx512m`, `--workers=2` and `taskset -c 0` — none reproduce). Other tests using the same `engine.connect()` + `server.connections {}` pattern (`JvmQuicServerTestSuite.echoSingleStream`, etc.) pass on the same CI run, so the divergence is somewhere inside the codec / `CodecConnection` / mux composition. Worth a fresh look with a self-hosted runner or pulling in [`quic-interop-runner`](https://github.com/quic-interop-runner/quic-interop-runner) for protocol-level interop coverage.
- [ ] **Windows JVM Tests mapping gaps** (`JvmExceptionMapping.kt`). Five tests currently skip on Windows via `isWindowsJvm()`:
  - `ExceptionIntegrationTests.tlsToNonTlsServer_producesSSLSocketException` — Windows surfaces neither `SSLSocketException` nor `SocketClosedException`; need to detect the JSSE shape that escapes through the channel-close race.
  - `ExceptionIntegrationTests.connectionRefused_producesSocketConnectionException` + `connectionRefused_exceptionHasUsefulMessage` + `JvmExceptionSubtypeTests.connectionRefused_isSocketConnectionExceptionRefused` — Windows NIO2 holds the connect past the test's 2 s budget instead of returning ECONNREFUSED. Either tighten the wrapper to surface ECONNREFUSED faster, or extend the test budget on Windows.
  - `ResourceCleanupTests.repeatedOpenClose` — Windows IOException routes through the `closed`-message branch and produces `SocketClosedException.General` mid-test. Distinguish Windows-NIO2's "socket already closed" from "kernel just closed it under us".
- [ ] **Windows quiche JNI native.** `build-linux.yaml` cross-compiles `quiche_jni.dll` via MinGW but the result is never staged into JAR resources (the `nativeLibsByPlatform` map in `socket-quic/build.gradle.kts` excludes `windows-*`). `socket-quic:jvmTest` is excluded from the Windows job until this lands.
- [x] ~**`pendingReadDuringPeerReset_producesSocketClosedException` deterministic fixture.** Currently `@Ignore`'d (commit `421a676`) — flakes on GH ubuntu-24.04 runners. Replace toxiproxy's `reset_peer` with the sidecar RST-only fixture proposed above ("Subtype-precise `SocketClosedException` cross-platform regression") and re-enable.~ Done — `test-harness/rst/` sidecar drives a deterministic RST on every kernel, test re-enabled. Validated 3× back-to-back on JVM/linuxX64/jsNode locally.

## Other (pre-existing)

Carried from earlier sessions / memories. Not introduced by the harness migration.

- [ ] socket-quic pool ownership audit — see [[socket_quic_recvbufpool_bug]] for the regression that started this thread; the fix landed in commit `80575c1` but the pool-sharing contract isn't formally documented.
