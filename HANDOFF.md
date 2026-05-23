# Handoff — test-harness migration (2026-05-22)

Picking this up in a fresh session? Start here.

**Mode:** this doc is written for a fresh session to **drive to completion autonomously** — Phases 3 → 4 → 5 → cleanup sweep — with clear exit gates and subagent opportunities marked. The [_Definition of done_](#definition-of-done) below tells you when to stop; the [_Continuation plan_](#continuation-plan) sequences the remaining work; the [_Tradeoffs_](#tradeoffs-locked-in) section at the end captures the design choices already made so you don't re-litigate them.

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

## Definition of done

The migration is "done" when **all five conditions** hold:

1. **No test in default `jvmTest` / `linuxX64Test` / `jsNodeTest` reaches a public-internet host.** (Apple coverage proves on CI; you can't validate locally without macOS.) Verify with `grep -rE "example\.com|cloudflare|httpbin|nginx\.org|badssl\.com|broker\.hivemq" src/{commonTest,commonJvmTest,jvmTest,linuxTest,jsTest} | grep -v '@Ignore' | grep -v '// '` returning empty.
2. **`tlsHarnessValidPassesWithInsecure` switches to `tlsDefault()`** and passes on every platform — the per-platform CA-trust injection (§7.3) is wired in CI.
3. **The broken-pipe wrapper fix (`1561478`) has a deterministic regression test** running via toxiproxy `reset_peer` — failing without the wrapper, passing with it.
4. **`integration.yaml` is deleted** and `review.yaml` runs the harness on every PR via an arch-matched matrix (`ubuntu-24.04` / `ubuntu-24.04-arm` / `macos-latest`-Colima).
5. **`TESTING_STRATEGY.md §6` Phase 5 marked done**, `TODO.md` Phase-1/2/3 sections empty.

When all five are true: push the branch, open the PR, stop. Anything beyond (further test additions, harness extensions) is out of scope.

## Continuation plan

Work the phases in order. Each phase has its own exit gate (a test command that must return green) and explicit subagent opportunities. **Commit per phase**, mark the doc done, then move on — don't batch.

### Phase 3 — toxiproxy / L4 fault injection (3–5 commits)

The deterministic regression for the broken-pipe wrapper (`1561478`) is the Phase-3 anchor.

**Deliverables** (TESTING_STRATEGY.md §2d):

1. `toxiproxy` service in `test-harness/docker-compose.yml`. Image `ghcr.io/shopify/toxiproxy:2.12.0`. Ports: `8474` (control API), `15000` (→ echo), `15080` (→ http), `15443` (→ tls:valid). Bind to `127.0.0.1`. Healthcheck: `nc -z 127.0.0.1 8474`.
2. `TOXIPROXY_API_PORT`, `TOXIPROXY_ECHO_PORT`, `TOXIPROXY_HTTP_PORT`, `TOXIPROXY_TLS_PORT` in `harness.env`; constants in `HarnessConfig`. Toxiproxy needs the upstream URL too — write a tiny `init.json` (committed) with the four proxies defined, or have a helper POST them on test setup.
3. Toxic-setup helper in `commonJvmTest` (or `commonTest` if you can get a tiny multiplatform HTTP/JSON client) — POSTs to `http://127.0.0.1:8474/proxies/<name>/toxics`. Three toxic types matter first: `down`, `reset_peer`, `slicer` (writes split into N pieces with delay between).
4. **Migrate `ExceptionIntegrationTests.writeAfterPeerClose_producesSocketClosedException`** + **`JvmExceptionSubtypeTests.brokenPipeOrReset_isSocketClosedSubtype`** onto the `15000` proxy with a `down` (or `reset_peer`) toxic. Assert the *exact* `SocketClosedException.BrokenPipe` / `…ConnectionReset` subtype the wrapper produces. Once these pass, the wrapper fix in `1561478` has the regression it deserves.
5. New `PartialReadConformanceTests` with a `slicer` toxic that splits a multi-byte UTF-8 char across reads. Asserts the harness equivalent of the `tlsWithSni` decode fix still holds.

**Exit gate:**
```bash
./gradlew jvmTest linuxX64Test --rerun-tasks
```
Expect: all `*ExceptionIntegrationTests.writeAfterPeerClose*` and `*JvmExceptionSubtypeTests.brokenPipeOrReset*` cases run deterministically (no flake across 5 consecutive runs).

**Subagent opportunity (run in parallel with Phase 3):** dispatch a `general-purpose` subagent to do the **TlsErrorTests remaining migration** — read-shape variants (`tlsReadIntoWriteBuffer`, `tlsBothReadOverloadsReturnSameData`, `tlsMultipleSequentialReads`, `tlsFirstReadReturnsData`), concurrent (`tlsConcurrentConnections`), larger-response (`tlsLargerResponse`), reconnect (`tlsReconnectAfterClose`), and the JSON/multi-host collapse (`tlsToExampleDotCom`/`tlsToNginx`/`tlsToHttpbin`/`tlsJsonApi`/`tlsWithValidCertificate` → one `tlsHarnessValidJsonGet` + reuse of existing `tlsHarnessValidGetReturnsHttp`). The subagent writes harness-equivalent tests in `TlsConformanceTests` (or a new `TlsValidPathConformanceTests`), then `@Ignore`s the originals with pointer comments — same pattern as commit `0450fd0`.

### Phase 4 — netem (L3) + QUIC echo (2–4 commits)

**Deliverables** (TESTING_STRATEGY.md §2c, §6 Phase 4):

1. **Static netem profiles.** Add a `netem` service (or run inside toxiproxy with its existing `NET_ADMIN` cap) that pre-applies a `tc qdisc add ... netem` rule on a dedicated egress port. Phase-4 minimum: one "100 % packet loss" port for deterministic connect-timeout coverage (replaces the magic `10.255.255.1` IP in `SimpleSocketTests.connectTimeoutWorks` / `closeWorks`).
2. **`quic-echo` container.** Wraps the existing `QuicEchoTestServer` from `socket-quic/src/sharedJvmTestProtocol/`. Listens on UDP `14433`. Migrate `QuicIntegrationTests` off `cloudflare-quic.com`. Add `QUIC_ECHO_PORT=14433` to `harness.env`.
3. **TLS-1.3-only port.** Sixth nginx block in `tls/conf.d/default.conf` with `ssl_protocols TLSv1.3` listening on `493` → host `14493`. Add `TLS_TLS13_PORT=14493`. Migrate `tlsFirstReadReturnsData` to use it.
4. **Multi-address / IPv6-only DNS** — address the `TODO.md` Linux addrinfo-fallback gaps. Either a tiny `dnsmasq` container (cleanest) or container `extra_hosts:` config to serve a name with both an A and an unreachable AAAA record. Add a `linuxX64Test` that connects to it and asserts the sequential fallback kicks in.

**Exit gate:**
```bash
./gradlew jvmTest linuxX64Test --rerun-tasks   # base
./gradlew :socket-quic:jvmTest :socket-quic:linuxX64Test --rerun-tasks   # quic
```
All migrated tests green; no test in either module reaches `cloudflare-quic.com` or other public QUIC/UDP hosts.

**Subagent opportunity:** the `quic-echo` Dockerfile is independent of base-module work — give it to a subagent in parallel with the netem / DNS work.

### Phase 5 — Browser/wasmJs CORS + CI fold-in + cleanup (3–6 commits)

**Deliverables:**

1. **CORS confirmation for browser tests.** The Phase-1 http container already serves `Access-Control-Allow-Origin: *`. Verify any browser test that wants to fetch from `127.0.0.1:14080` actually works — add one minimal browser-target test if none exists.
2. **Delete the now-dead public-host tests.** Anything `@Ignore`'d with a "replaced by harness" pointer comment and a green harness equivalent — gone. Per the green-throughout rule, only after Apple CI has run the harness equivalent at least once on `macos-latest`.
3. **CI workflow rewrites.** Touch each:
   - `review.yaml` — add the harness arch matrix (`ubuntu-24.04` / `ubuntu-24.04-arm` / `macos-latest`). On Apple: `brew install colima docker && colima start`. On every runner: `bash test-harness/tls/gen-certs.sh && docker compose -f test-harness/docker-compose.yml up -d --wait` before tests, `down -v` after.
   - `build-linux.yaml` `test-arm64` job — wrap the raw `.kexe` execution with the same `compose up --wait` / `down -v` steps (it bypasses Gradle, so harnessUp/harnessDown can't fire; do it at YAML level).
   - `build-apple.yaml` — same Colima setup.
   - `integration.yaml` — **delete**. Its content moves into the always-on `review.yaml` harness job (no more label-gating, no more public-host dependency).
4. **Per-platform CA-trust injection** (decision §7.3). For each CI job that runs the harness:
   - JVM: `keytool -importcert -trustcacerts -file test-harness/tls/certs/ca.crt -alias harness-root -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -noprompt`
   - Linux: `sudo cp test-harness/tls/certs/ca.crt /usr/local/share/ca-certificates/harness-root.crt && sudo update-ca-certificates`
   - macOS: `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain test-harness/tls/certs/ca.crt`
   Then switch `tlsHarnessValidPassesWithInsecure` → `tlsHarnessValidPassesWithDefault` using `SocketOptions.tlsDefault()`.
5. **`jsNodeTest` harness wiring.** Add `jsNodeTest` to the `harnessUp` / `harnessDown` task list in `build.gradle.kts`. (Browser/wasmJs stays skipped per decision §7.4.)
6. **Final `TESTING_STRATEGY.md`** — mark §6 Phase 5 done. Empty out the per-phase TODO.md sections. Bump README if it mentions public hosts anywhere.

**Exit gate:**
```bash
grep -rE "example\.com|cloudflare|httpbin|nginx\.org|badssl\.com|broker\.hivemq" \
  src/{commonTest,commonJvmTest,jvmTest,linuxTest,jsTest} \
  socket-quic/src \
  | grep -v "@Ignore" | grep -v "// " | grep -v "TESTING_STRATEGY"
```
Returns empty (or only documentation/comments). All `*Test` tasks green across JVM, linuxX64, Android, jsNode.

**Subagent opportunity:** each CI YAML file is self-contained — split across 3 subagents (`review.yaml`, `build-linux.yaml`, `build-apple.yaml`) running in parallel. They write the edits, you commit them sequentially after reviewing.

### Cleanup sweep (1–2 commits)

After Phase 5, walk `TODO.md` top to bottom. Anything still unchecked: do it or move it to a new "Phase 6 — not in this migration" section. Then:

```bash
./gradlew ktlintFormat
./gradlew check                # all modules
git push origin feature/transport-codec-api
gh pr create --title "Test-harness migration: deterministic local TLS/L4/L3" --body "@HANDOFF.md"
```

## Other deferred items (sorted by impact, all in `TODO.md`)

These are tracked in `TODO.md` and folded into the relevant phase above. Don't re-litigate here.

## Subagents — your context is the bottleneck

**Read this before you start any phase.** The session that produced this branch burned ~70 % of its context on file reads + test-run output. To finish Phases 3–5 in one session you have to **stay an orchestrator** — delegate everything that involves reading large files, running tests, or grinding through repetitive code, and keep only architectural state + commit hashes + exit-gate verdicts in your own context.

A rough rule: **if a task would make you Read a file you haven't seen, give it to a subagent.** Subagents have fresh context windows; they can read 2 000-line test files without it costing you a thing. They return a summary; that's all you keep.

### Default to delegation for

| Task | Why | Agent type | What to keep |
|---|---|---|---|
| **Reading the existing test file to plan a migration** (`TlsErrorTests`, `NetworkIntegrationTests`, `SniStrictHostsTest`, `LinuxTlsTests`, `ExceptionIntegrationTests`, `JvmExceptionSubtypeTests`) | Each is hundreds of lines; you only need "which tests + what assertion patterns + which `@Ignore`s after migration" | `Explore` for inventory, `general-purpose` for the migration itself | Just the test names + commit hash after |
| **Running `./gradlew jvmTest linuxX64Test --rerun-tasks`** | Gradle output is thousands of tokens of compilation + test logging; you only need pass/fail + flaky-test details | `general-purpose` agent that runs the command and returns just `BUILD SUCCESSFUL` / specific failure stacks | Pass/fail + names of any failing tests |
| **Per-platform CA-injection recipe research** | Three platforms × multiple commands × testing the recipe = a lot of pages; pure research output is short | `claude-code-guide` for the JVM `keytool` bits, `general-purpose` for Linux/Apple specifics — run in parallel | The final 3 CI YAML snippets only |
| **CI workflow YAML rewrites** | Each `.yaml` is 100–300 lines; the edits are localized but you have to understand the surrounding job structure | `general-purpose` per file (3 in parallel), each given the harness up/down snippet + the colima/CA-injection step list as inputs | Just the commit hash per file |
| **Dockerfile/container construction** (Phase 4: `quic-echo`, `netem`) | Independent builds; researching `tc qdisc` syntax + base images takes a lot of context | `general-purpose` per container, parallel | Path to the new files + healthcheck command used |
| **Toxic-setup helper HTTP/JSON client** | A few hundred lines of multiplatform-safe HTTP code | `general-purpose` agent with a precise API spec + target file | API surface only |
| **`grep`/`find` sweeps** ("which tests still reach a public host?") | Output can be long, you need a one-line summary | `Explore` agent — exists for exactly this | The final list |

When you spawn a subagent, **give it the relevant section of this `HANDOFF.md` + the relevant section of `TESTING_STRATEGY.md` + the template pattern** (commit ref like `0450fd0` or a specific file like `TlsConformanceTests.kt`). Don't make it re-derive context you already have.

Use **parallel** subagents when their file scopes are disjoint (the three CI workflows, the cert-injection-per-platform research, Phase-3 migration sweep + Phase-3 toxiproxy integration). Serialize when they touch the same files — Phase 3's toxiproxy compose changes and the toxiproxy-using migration tests must be sequential.

### Keep in the main agent's context

- **Architectural integration decisions.** Toxiproxy proxies-vs-direct port allocation, the toxic-setup helper API shape, multiplatform parity calls. Single-author judgment.
- **Anything that touches `commonJvmMain` production code** (`JvmExceptionMapping.kt`, `BaseClientSocket.kt`, `AsyncBaseClientSocket.kt`). Don't farm out production-code edits.
- **Commit message authorship.** Subagents return work; you commit. Keeps the history coherent.
- **Exit-gate verification.** When a subagent reports "tests pass", run the gate yourself one time per phase to confirm — but only one time, and only the gate command from the per-phase block above.
- **Strategy doc + TODO updates.** Marking phases done, moving TODOs around — orchestrator's job.

### Context-budget heuristic

If you find yourself reading a 500+ line file you've never seen before, **stop and spawn a subagent instead**. If you've used `Bash` on `./gradlew jvmTest` more than twice in one phase, the third time goes to a subagent (give it the test name + expected outcome; let it parse the output). If you've spent 20+ turns on a single phase, you're probably under-delegating — reassess.

A rough budget per phase: ≤ 25 % of your context window. If Phase 3 alone consumed 50 %, Phases 4 and 5 won't fit. Stop and write a Phase-3-only handoff if that happens — better one good commit + a clean handoff than three rushed phases.

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
git log --oneline -15                   # see the commits done here
cat HANDOFF.md                          # this file
cat TODO.md                             # open follow-ups
sed -n '436,470p' TESTING_STRATEGY.md   # phase-by-phase migration plan
```

Then start at **Phase 3** above and work down. The harness already works end-to-end; everything from here is incremental and gated by the per-phase exit tests.

---

## Tradeoffs locked in

If you find yourself tempted to revisit any of these, read the rationale first. They were made deliberately; flipping any of them means re-doing work.

1. **CA-trust via CI-time injection, not a runtime `SocketOptions` API extension** (decision §7.3).
   _Trade:_ requires per-platform CI steps (JVM `keytool`, Linux `update-ca-certificates`, Apple `security add-trusted-cert`); gives real default-path coverage. Alternative would have been a new `SocketOptions.tlsTrustingCa(ca)` API — cleaner in the test but ships a permanently-useful production API surface only because tests need it. Defer the API; do the CI step.

2. **`tlsInsecure()` as the Phase-2 placeholder for the valid-path test.**
   _Trade:_ ships infra now (10 conformance tests already green on JVM + linuxX64) at the cost of not testing default validation against the harness CA. Phase 5 flips it to `tlsDefault()` once CA injection is wired. The four *rejection* scenarios already use `tlsDefault()` and do exercise default validation — they just don't depend on what's trusted.

3. **Skip-on-not-available (`isHarnessAvailable()`) at test level, not Gradle-task-level abort.**
   _Trade:_ the suite stays green when the harness isn't up (local dev without Docker, Windows runners, `HARNESS_DISABLED=true`) — discoverability cost: a developer running `./gradlew jvmTest` without Docker sees green even though harness tests didn't run. Mitigated by the new test class names being prefixed `harnessXxx` and the per-class doc-comment explaining the skip.

4. **Pinned static ports, not Testcontainers dynamic mapping.**
   _Trade:_ every platform sees identical numbers (the only design that works for K/N + wasmJs, which can't read JVM system properties), at the cost of port-conflict risk if a developer is already running something on 14000/14080/14443/etc. Mitigated by binding all ports to `127.0.0.1` only and choosing high-numbered, unusual ports. Testcontainers is still considered for *lifecycle* on JVM tests, but discovery stays generated-config-driven.

5. **In-repo `test-harness/` with per-run image builds, not a published image** (decision §7.6).
   _Trade:_ per-CI-run build time (~30 s for the tls + echo containers) for simplicity (no separate publish pipeline, no version-skew between code + harness images, images built natively per arch). Revisit when CI builds become the bottleneck.

6. **Colima on macOS CI, not Apple `container`** (decision §7.7).
   _Trade:_ no native arm64 micro-VM speed boost on Apple Silicon; gains runtime parity with Linux CI (same Docker Compose, same networking semantics) and avoids forking the harness orchestrator. Apple `container`'s compose / inter-container networking story is still immature — revisit purely as a local-dev convenience once that matures, never as a second CI orchestrator.

7. **`socat EXEC:cat` for echo, not a Go binary.**
   _Trade:_ no programmable behavior (can't simulate "echo every other byte", can't add stats endpoints), zero build deps (no Go toolchain, no compile step, no dependency-update churn). If Phase 4+ needs programmable echo, replace it then. Today the toxiproxy `slicer` toxic covers most "split / delay / drop" needs without touching the echo binary.

8. **`@file:Suppress("ktlint:standard:property-naming")` in the generated `HarnessConfig.kt`, not SCREAMING_SNAKE_CASE renaming.**
   _Trade:_ a one-line `@file:Suppress` in the generator output for call sites that read `HarnessConfig.echoPort` (idiomatic Kotlin object access) rather than `HarnessConfig.ECHO_PORT` (Java-style constant). Also tried `ktlint { filter { exclude("**/generated/**") } }` — doesn't work in this project layout because the glob is relative to each source root and generated files have no `generated/` segment under their root. See gotchas above.

9. **Wall-clock budgets (`elapsed < Nms`) → `withTimeout` watchdog + observable-state assertion** (Phase 0).
   _Trade:_ loss of "cancellation completes in <500 ms" latency precision for determinism across CI / WSL2 loads. The watchdog still catches a *genuinely hung* cancel (it just gives 5–10 s of headroom). Reading the test stops telling you about latency floor; the right place for that signal is benchmarks, not unit tests. (`checkPort` poll specifically went 5 s → 10 s in commit `0450fd0` after harness-load surfaced a 5.034 s reap.)

10. **`assertFailsWith<SSLSocketException>` for cert-rejection tests, not the looser `<SocketException>`.**
    _Trade:_ requires JVM and BoringSSL to map cert errors to the same family — *which they do*, verified in commits `9d55cd3`/`0450fd0`. Gains: a harness-down test would fail (`SocketConnectionException.Refused` ≠ `SSLSocketException`) rather than vacuously pass. Without this strictness, a silent skip would mask the regression we're using the harness to *prevent*.

11. **One `expect/actual` for `harnessHost()`, generated `HarnessConfig` for everything else** (decision §3a).
    _Trade:_ the host value alone needs platform awareness (browsers may eventually want `window.location.hostname`); ports never do. One expect = trivial to maintain. Five port-expect/actuals would be untenable. The generator + single expect was the minimum viable design.

12. **Discovery via TCP probe to echo port, not a `harness.lock` marker or Docker socket query.**
    _Trade:_ 500 ms worst-case probe per test run when harness isn't up (immediate refusal on localhost — sub-ms in practice). No assumption that the test process can see the Docker socket, no shared filesystem state — works identically on JVM, K/N, JS Node. The cost shows up as 1 ms × N harness-backed tests when harness is down; acceptable.
