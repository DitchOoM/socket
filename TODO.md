# socket — open follow-ups

Tracked here so the gaps from each session aren't lost.

## Phase-1 harness skeleton follow-ups (2026-05-22)

Surfaced while building `test-harness/` + the `generateHarnessConfig` wiring.

- [ ] **CI workflow wiring.** `harnessUp`/`harnessDown` are wired to `jvmTest` + `linuxX64Test`. The `test-arm64` job in `build-linux.yaml` runs the raw `.kexe` and bypasses Gradle (see `TESTING_STRATEGY.md` §3e) — needs explicit `docker compose up --wait` / `down -v` steps wrapping the binary execution. Apple CI (`build-apple.yaml`) needs Colima install + harness up/down per §3c.
- [ ] **Migrate remaining public-host plain-HTTP tests.** Phase 1 added `HarnessConformanceTests` (echo + HTTP status). `SimpleSocketTests.httpRawSocketExampleDomain` / `httpRawSocketGoogleDomain` still hit the real internet; `NetworkIntegrationTests.tcp_*` likewise. Replace with harness-backed equivalents, then `@Ignore` the public-host originals (TESTING_STRATEGY.md §6 Phase 1 green-throughout rule).
- [ ] **WSL2 Docker reachability.** Harness binds to `127.0.0.1` on the WSL host. Works from the WSL shell; would not work from Windows-side processes addressing WSL's Docker. Not a CI concern (Linux runners), worth a note in `test-harness/README.md`.
- [ ] **Compose-without-`--wait` fallback.** `docker compose up -d --wait` needs Compose v2.17+. Older binaries silently ignore `--wait` and tests race the healthcheck. Either pin the doc to "requires Compose ≥ 2.17" or fall back to polling `isHarnessAvailable()` in the `harnessUp` task.
- [ ] **Deterministic regression for the JVM broken-pipe wrapper** (`writeAfterPeerClose_producesSocketClosedException` / `brokenPipeOrReset_isSocketClosedSubtype`) lands with Phase 3's toxiproxy (`reset_peer` / `down` toxics). Until then, the fix in `1561478` is correctness-by-inspection.
- [ ] **Pre-existing ktlint debt** in the five files reformatted in `3019d03` was the first whole-tree `ktlintFormat` run in a while. Worth wiring `ktlintCheck` as a required PR gate in `review.yaml` so it can't drift again.
- [ ] **`TlsErrorTests.tlsConcurrentConnections` 10s timeout** — observed once during Phase 1 verification (connecting to public TLS host, handshake didn't complete in 10s). Pure network flake, fixed when `TlsErrorTests` migrates to the harness in Phase 2 (`tls` cert-matrix container, deterministic peer). Until then, expect occasional flakes here.
- [ ] **ktlint `filter { exclude("**/generated/**") }` is ineffective** — paths are relative to each source root, and generated files have no `generated` segment relative to *their* root. Worked around with `@file:Suppress("ktlint:standard:property-naming")` written into the generated `HarnessConfig.kt`. If the harness grows more generated files, consider a `KtlintExtension` filter that excludes by absolute path (lambda form) or move generated sources under a path that survives the glob.

## LinuxClientSocket addrinfo fallback (`4073f29`)

Today's commit walks the addrinfo linked list sequentially when the head address fails. This unblocks dual-stack hosts whose AAAA records are unreachable (the canonical reproducer was `broker.hivemq.com:8884`). Open items:

- [ ] **In-repo unit test for the iteration logic.** Currently the fix is only validated end-to-end via `websocket :linuxX64Test --tests "*PublicWssValidationTest.hivemqWssConnect"`. A `:socket:linuxX64Test` that targets a host known to return multi-address records (or a fixture that stubs `getaddrinfo`) would catch regressions inside this repo.
- [ ] **IPv6-only host coverage.** No regression test confirms that an IPv6-only host (no A record) still connects on the first AAAA.
- [ ] **Full RFC 8305 Happy Eyeballs racing.** Current change is sequential only — if the first IPv6 address takes the full TCP SYN timeout to fail, the user waits ~75 s before the IPv4 fallback is tried. Real Happy Eyeballs races A and AAAA in parallel with a small "resolution preference" delay. Not blocking, but worth doing for latency-sensitive use cases.
- [ ] **Cross-platform parity.** JVM (`AsynchronousSocketChannel`) and Apple (`NWConnection`) already iterate addresses internally via their platform abstractions. Worth confirming with a unit test on each platform that the documented behavior matches Linux's new fallback semantics.

## Other (pre-existing)

Carried from earlier sessions / memories. Not introduced today.

- [ ] socket-quic pool ownership audit — see [[socket_quic_recvbufpool_bug]] for the regression that started this thread; the fix landed in commit `80575c1` but the pool-sharing contract isn't formally documented.
