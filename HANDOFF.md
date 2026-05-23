# Handoff — test-harness migration (2026-05-22)

Picking this up in a fresh session? Start here.

## State at handoff

- **Branch:** `feature/transport-codec-api`
- **Commits ahead of origin:** 12 (not pushed yet)
- **Last commit:** `c4f4889 docs: mark Phase 2 infrastructure + cert-validation migration complete`
- **Working tree:** clean (`git status` shows nothing)
- **Strategy doc:** [`TESTING_STRATEGY.md`](TESTING_STRATEGY.md) — read this first if you don't have context.
- **Follow-ups:** [`TODO.md`](TODO.md)

## Session arc, in one screen

| Phase | Commit | Status |
|---|---|---|
| Bump `buffer` → 5.0.0 | `f0e6608` | shipped |
| Strategy doc | `1965818` | shipped |
| Doc amend: arm64 + Apple `container` | `d7841fd` | shipped |
| **Phase 0** — de-flake (assertion fixes, no harness) | `5421d42`, `b58d4b9` | ✅ done |
| Wrapper bug fix (JVM read/write paths route IOException through `wrapJvmException`) | `1561478` | ✅ done |
| Whole-project `ktlintFormat` | `3019d03` | ✅ done |
| **Phase 1** — harness skeleton (echo + http, generated config, `isHarnessAvailable`) | `e3569a5`, `f51b719` | ✅ done |
| **Phase 2** — TLS cert matrix (5 certs × strict/lenient = 10 tests, both JVM + linuxX64 green) | `9d55cd3`, `0450fd0`, `c4f4889` | ✅ infra + cert-rejection migrated; valid-path-with-default-CA deferred |

## Pick up here — Phase 3 (toxiproxy / L4 fault injection)

The deterministic regression test for the **broken-pipe wrapper fix** (`1561478`) is the Phase-3 deliverable. Today the wrapper fix is correctness-by-inspection; Phase 3 makes it reproducible via `reset_peer` / `down` toxics.

What Phase 3 needs (per [TESTING_STRATEGY.md §2d / §6 Phase 3](TESTING_STRATEGY.md)):

1. **toxiproxy service** in `test-harness/docker-compose.yml`. Image: `ghcr.io/shopify/toxiproxy:2.12.0`. Expose:
   - `8474` — JSON control API
   - `15000` — proxy → echo (faulted)
   - `15080` — proxy → http
   - `15443` — proxy → tls:valid
2. **harness.env entries** + `HarnessConfig` constants: `TOXIPROXY_API_PORT`, `TOXIPROXY_ECHO_PORT`, `TOXIPROXY_HTTP_PORT`, `TOXIPROXY_TLS_PORT`.
3. **toxic-setup helper** in `commonTest` — a thin HTTP/JSON client (POST to `http://127.0.0.1:8474/proxies/<name>/toxics`) that creates / clears toxics per test. Three toxics matter first: `down`, `reset_peer`, `slicer` (split a write into N pieces, delay between).
4. **`ExceptionIntegrationTests` migration** — move `writeAfterPeerClose_producesSocketClosedException` and `JvmExceptionSubtypeTests.brokenPipeOrReset_isSocketClosedSubtype` onto a toxiproxy `down` (or `reset_peer`) toxic so the peer-close happens deterministically. Assert the exact `SocketClosedException.BrokenPipe` / `…ConnectionReset` subtype the wrapper produces.
5. **Partial-read regression test** — `slicer` toxic that splits a multi-byte UTF-8 char across reads, asserting that the harness-version of the `tlsWithSni` decode path handles it (matches the Phase-0 fix).

`TESTING_STRATEGY.md §2d` has the full design.

## Other deferred items (sorted by impact, all in `TODO.md`)

- **CA-trust CI injection** (decision §7.3). Until this lands, `tlsHarnessValidPassesWithInsecure` uses `tlsInsecure()` — it's a TLS-handshake smoke, not default-validation coverage. Workflow change: import `ca.crt` into JVM `cacerts` / Linux CA bundle / Apple keychain in CI YAML.
- **arm64 Linux CI wiring** — `build-linux.yaml`'s `test-arm64` job runs the raw `.kexe` (not Gradle), so the harness up/down must be wired at the workflow-step level wrapping the binary. Same goes for cert generation (`bash test-harness/tls/gen-certs.sh` step). Macos CI needs `colima start` first.
- **Remaining migrations** — many `TlsErrorTests` (read-shape, concurrent, larger-response), `NetworkIntegrationTests.tls_*`, `SniStrictHostsTest`, `LinuxTlsTests`. All have direct harness equivalents now; the sweep is mechanical.
- **TLS 1.3-only port** (`TLS_TLS13_PORT=14493`) — needed for `tlsFirstReadReturnsData`. Sixth nginx block with `ssl_protocols TLSv1.3`.
- **JS Node TLS coverage** — `jsNodeTest` should run the conformance suite too. Add it to `harnessUp`/`harnessDown` wiring.
- **Pre-existing Linux Happy Eyeballs** items from earlier sessions (still in TODO.md).

## Verification recipe (copy-paste)

```bash
# Generate certs + bring up harness + run multiplatform conformance
./gradlew jvmTest linuxX64Test --rerun-tasks --console=plain

# Skip harness entirely (e.g. local dev without Docker)
HARNESS_DISABLED=true ./gradlew jvmTest

# Manual harness lifecycle
cd test-harness && docker compose up -d --wait && cd ..
# … run things …
cd test-harness && docker compose down -v && cd ..

# Cert regeneration (idempotent — pass --force to redo)
bash test-harness/tls/gen-certs.sh
bash test-harness/tls/gen-certs.sh --force
```

Expected: `BUILD SUCCESSFUL`. Harness conformance tests are 5–9 ms each on JVM (TLS handshakes on localhost) and similar on linuxX64.

## Mental model — how the pieces fit

```
test-harness/                      ← committed (compose, scripts, configs)
  harness.env                      ← single source of truth for host + ports
  docker-compose.yml               ← echo + http + tls services (Phase 3 adds toxiproxy)
  echo/Dockerfile                  ← alpine + socat
  http/{conf.d,www}                ← nginx routes + static
  tls/{gen-certs.sh,conf.d}        ← cert pipeline + 5 server blocks
  tls/certs/                       ← gitignored; openssl-generated
                       │
                       │ docker compose up --wait
                       ▼
[bound to 127.0.0.1 only — never exposed beyond loopback]
                       │
                       │ harnessUp Gradle task
                       │ dependsOn(generateHarnessCerts)
                       ▼
[ jvmTest | linuxX64Test ]  ← dependsOn(harnessUp), finalizedBy(harnessDown)
                       │
                       │ generateHarnessConfig task reads harness.env →
                       │ build/generated/.../HarnessConfig.kt onto commonTest's source path
                       ▼
commonTest/.../harness/
  HarnessConformanceTests.kt       ← TCP echo + HTTP status-line
  TlsConformanceTests.kt           ← 10 cert-matrix tests
                       │
                       │ skip via isHarnessAvailable() if harness not up
                       ▼
[ test runs against 127.0.0.1:<HarnessConfig.*Port> ]
```

Key invariants:
- **One `expect/actual`** for harness host: `internal expect fun harnessHost(): String`. All 5 platform actuals return `HarnessConfig.host` today.
- **All ports pinned**, all bound to `127.0.0.1`. Each platform sees identical numbers.
- **Skip-on-not-available** is at the test level (`isHarnessAvailable()` TCP-probes the echo port). The Gradle tasks gracefully no-op on Windows / missing Docker / `HARNESS_DISABLED=true`.

## Decisions locked in (TESTING_STRATEGY.md §7)

1. macOS CI uses **Colima** (not Docker Desktop, not Apple `container`).
2. **netem static profiles** — no dynamic control shim until a test demands it.
3. **CA injection per platform in CI** for the valid path (deferred to Phase 3+).
4. **Browser / wasmJs skips harness** — keeps the `WEBSOCKETS_ONLY` early-return.
5. `integration.yaml`'s label-gated public-internet job is **folded into always-on `review.yaml` harness job** (CI YAML change deferred).
6. Harness lives **in-repo** (`test-harness/`), images built per CI run.
7. **Apple `container` not adopted** — immature multi-service compose / inter-container networking; would fork the orchestrator from Linux CI.
8. **arm64 is first-class, no QEMU** — runner matrix (`ubuntu-24.04` / `ubuntu-24.04-arm` / `macos-latest`), each builds harness images for its own arch (multi-arch base images = nginx, toxiproxy, alpine).

## Gotchas hit this session — keep these in mind

- **Container `localhost` resolves to `::1`** via docker's embedded DNS. nginx's default `listen 80;` and socat-LISTEN bind IPv4 only → `localhost` connect refused. Healthchecks must use **`127.0.0.1`** explicitly.
- **Compose `--wait` is v2.17+.** Older Compose silently ignores it → tests race the healthcheck. Tracked.
- **ktlint `exclude("**/generated/**")` is ineffective** in this layout because the filter glob is relative to each source root, and generated files have no `generated` segment under their root. Worked around with `@file:Suppress("ktlint:standard:property-naming")` in the generated `HarnessConfig.kt`.
- **`assertFailsWith<SocketException>` is too loose** for TLS-rejection tests — it'd also catch `SocketConnectionException.Refused` (which would happen if the harness were down → tests vacuously pass). Use **`assertFailsWith<SSLSocketException>`** for cert-rejection assertions.
- **`SimpleSocketTests.suspendingInputStream`** flaked once with the 5-s `checkPort` budget under Phase-2-concurrent load. Bumped to 10 s in `0450fd0`. A real fd leak hangs indefinitely; 10 s gives WSL2 room.
- **`gradlew jvmTest` is UP-TO-DATE-cacheable** even when the harness state changes between runs. Use `--rerun-tasks` when verifying harness changes. (Acceptable for normal dev; the harness up/down still fire and the tests skip cleanly if the harness can't start.)
- **`harnessUp` no-ops on Windows runners** (no bash). Windows CI never exercises the harness — that's fine; the harness exists to remove the public-internet dep from Linux/Apple paths.
- **The `socket-quic:jvmTest FAILED`** seen if you pass `--tests "<commonTest pattern>"` without scoping `:` to the root project — the filter matches zero tests in socket-quic and Gradle fails the task. Use `./gradlew :jvmTest --tests "…"`.

## Where to start in the fresh session

```bash
git status                              # working tree should be clean
git log --oneline -12                   # see the 12 commits done here
cat HANDOFF.md                          # this file
cat TODO.md                             # open follow-ups
sed -n '436,470p' TESTING_STRATEGY.md   # phase-by-phase migration plan
```

Then either start Phase 3 (see above), or pick a deferred item from `TODO.md`. The harness already works end-to-end; everything is incremental from here.
