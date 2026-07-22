# RFC — Unified network test harness across TCP, UDP & QUIC

**Status:** Proposed. Scoping only — no code has moved yet. Supersedes the QUIC-only assumptions baked into today's harness surface.
**Builds on:** [`RFC_DETERMINISTIC_SIMULATION.md`](./RFC_DETERMINISTIC_SIMULATION.md) (the timeline engine, Tier-A/Tier-B model, `TraceSink`/`TraceEvent`, `withNetworkHarness`, the container control plane) and [`RFC_TRANSPORT_FALLBACK.md`](./RFC_TRANSPORT_FALLBACK.md) (`NetworkMonitor`/`networkId`, injected-clock precedent). Extends those seams to **all three transport stacks** and factors the reusable core into a published module.

## 1. Goal

The library ships three transport stacks — **TCP** (`:socket`), **UDP** (`:socket-udp`), **QUIC** (`:socket-quic*`) — and a **`NetworkMonitor`** that all three can react to. Today their test tooling has grown lopsided:

- the **container harness** (toxiproxy / netem / docker) is **TCP-first** — `echo`, `http`, `tls`, `rst`, `blackhole`, `impaired`, `peerReset` are all TCP;
- the **deterministic sim** (`SimTimeline`/`SimClock`/`ImpairedPipe`, recorder→fixture→replay, fuzzer+ddmin) is **QUIC-only**, living inside `:socket-quic-quiche`;
- **UDP has neither** — only per-platform conformance tests;
- **QUIC has no wire-level fault injection at all** (toxiproxy cannot proxy UDP);
- **`TraceSink`/`TraceEvent` are QUIC-namespaced** (`:socket-quic`), so nothing outside QUIC can reuse the recorder/replay machinery;
- **there is no scripted/fake `NetworkMonitor`**, so migration/reconnect reactions cannot be triggered hermetically.

**One mental model for all three stacks, plus the monitor.** A consumer of *any* stack, on *any* Kotlin Multiplatform target, writes plain `commonTest` code that:

1. runs the same conformance/round-trip suite against a **hermetic endpoint** for their transport,
2. injects **deterministic faults** (loss, delay, reorder, duplication, corruption, reset, blackhole, network change) through **one fault vocabulary** shared by the in-process sim and the on-the-wire proxy,
3. **records** a real-world failure as a fixture and **replays** it byte- and order-exactly forever,
4. does all of this with **one dependency + `docker compose up`** — no docker CLI, no platform-specific test code.

A fixture is a recorded timeline; a fuzz case is a generated timeline; the corpus is shrunk failing timelines. This is the RFC_DETERMINISTIC_SIMULATION contract, made transport-neutral.

## 2. The invariant: one model, three stacks, two tiers

Every stack implements the **same four abstractions** at the **same two tiers**. The stack-specific work is only the thin binding at each cell; the abstractions are shared.

### 2.1 The two tiers (from RFC_DETERMINISTIC_SIMULATION §4)

| Tier | What runs | Clock | Determinism | Needs docker | Where it runs |
|---|---|---|---|---|---|
| **Tier-A** — in-process sim | code under test over an in-memory `ImpairedPipe` | virtual (`SimClock`) | **bit-exact** for stacks we fully own (TCP, UDP); **prefix-exact ±1 datagram** for QUIC (quiche FFI is not caller-clocked — §6) | no | every KMP target, `commonTest` |
| **Tier-C** — hermetic container | the real stack over a real impaired network | wall clock | reproducible-in-*schedule* (per-datagram, via the scripted relay) | yes | any target whose `ClientSocket`/`UdpSocket` can reach the controller |

> Tier-B (real engine over a seeded in-process pipe) stays a QUIC-specific refinement of Tier-A and is unchanged by this RFC. The headline is **A and C reach parity for all three stacks.**

### 2.2 The four shared abstractions

1. **`Endpoint`** — an echo/fault server the code under test talks to. TCP has `echo`/`http`/`tls`/`rst`; UDP gains `udp-echo`; QUIC has `quic-echo`.
2. **`FaultSchedule`** — one declarative, seedable description of what the network does to packets/segments/datagrams. **The same schedule object drives both the Tier-A `ImpairedPipe` and the Tier-C wire proxy.** This is the load-bearing unifier — it is what makes the three stacks feel identical to a test author.
3. **Recorder → Fixture → Replay** — a neutral `TraceSink`/`TraceEvent` captures a real run; `ToFixture` codegens a golden fixture; the interpreter replays it. QUIC's existing `QuicTraceEvent` becomes a *projection* over the neutral event, not a parallel format.
4. **Fuzzer + ddmin** — a seeded generator emits `FaultSchedule` timelines, per-stack invariants check them, and the shrinker minimizes failures into committed corpora. (This is exactly the loop that found the two `QuicheDriver` reader-loop native-buffer leaks.)

### 2.3 Coverage matrix — current vs. target

`✅` shipped · `⚠️` partial · `❌` missing · `→` this RFC brings to parity

| | TCP | UDP | QUIC |
|---|---|---|---|
| Hermetic endpoint (Tier-C) | ✅ | ❌→✅ | ✅ (clean only)→ +faults |
| Wire fault plane (Tier-C) | ✅ toxiproxy | ❌→✅ udp-toxi | ⚠️→✅ udp-toxi |
| In-process sim (Tier-A) | ⚠️ 1 fixture→✅ | ❌→✅ | ✅ |
| Recorder→fixture→replay | ❌→✅ | ❌→✅ | ✅ (de-namespace) |
| Fuzzer + shrinker | ❌→✅ | ❌→✅ | ✅ |
| `withNetworkHarness` accessors | ✅ | ❌→✅ | ⚠️ echo-only→ +faults |

## 3. Where the pieces live — `:socket-testkit` (new, published)

The reusable core is factored into a **new published, transport-neutral module** so external KMP consumers can depend on it without dragging QUIC in.

```
:socket-testkit                         (NEW · published · transport-neutral · no QUIC dep)
 ├─ FaultSchedule + FaultScheduleDsl    the one fault vocabulary
 ├─ ImpairedPipe                        (lifted out of :socket-quic-quiche)
 ├─ TraceSink / TraceEvent              (MOVED from :socket-quic, de-namespaced)
 ├─ TimelineInterpreter / SimClock      the neutral replay engine
 ├─ FixtureCodec (record ↔ replay)      neutral v2 grammar (QUIC v1 line is a projection)
 └─ Fuzzer core (generator + ddmin)     invariants are injected per stack

:socket-testsuite     → depends on :socket-testkit; hosts withNetworkHarness + the per-stack suites
:socket-quic-quiche   → QuicTraceEvent projects over the neutral TraceEvent; ImpairedPipe re-homed
:socket-udp           → gains a Tier-A sim + conformance-under-fault suite built on testkit
:socket   (TCP)       → gains a Tier-A sim built on testkit (it already has the container tier)
```

**Split-package is fine** — the QUIC modules already share `com.ditchoom.socket.quic` across `:socket-quic` and `:socket-quic-quiche`; the same technique moves `TraceSink` out. The neutral core lands under `com.ditchoom.socket.testkit`.

**Publishing note:** `:socket-testkit` needs full multiplatform metadata like the library modules; validate via the buffer-project technique (CI `maven-local-merged` artifact — a local Mac publish cannot produce complete Apple metadata; see `[[buffer-6.0-reconciliation]]`).

## 4. `FaultSchedule` — the one vocabulary

A `FaultSchedule` is an ordered, seeded list of per-unit actions. The "unit" is the transport's natural quantum — a TCP byte-run/segment, a UDP datagram, a QUIC datagram — but the *action set is identical*:

| Action | TCP | UDP | QUIC | Tier-A seam | Tier-C seam |
|---|---|---|---|---|---|
| `delay(d, jitter)` | ✅ | ✅ | ✅ | `ImpairedPipe` | toxiproxy (TCP) / udp-toxi |
| `drop(nth \| p, seed)` | ✅ | ✅ | ✅ | `ImpairedPipe` | toxiproxy / udp-toxi |
| `reorder(window)` | — | ✅ | ✅ | `ImpairedPipe` | udp-toxi |
| `duplicate(nth)` | — | ✅ | ✅ | `ImpairedPipe` | udp-toxi |
| `corrupt(nth, mask)` | ✅ | ✅ | ✅ | `ImpairedPipe` | udp-toxi |
| `resetAfter(nBytes\|nDgrams)` | ✅ | — | — | pipe close | `rst` sidecar / toxiproxy |
| `blackhole()` | ✅ | ✅ | ✅ | pipe silence | netem-blackhole |

**Determinism contract:** `drop(nth = k)` drops the *k-th* unit — bit-deterministic on a real wire, which is why we build the scripted `udp-toxi` relay rather than leaning on `netem`'s statistical `loss %`. `drop(p, seed)` is available for realism but is only reproducible-in-distribution. Tier-A always uses the exact form.

## 5. `udp-toxi` — the scripted UDP relay (Tier-C, UDP + QUIC)

toxiproxy is TCP-only, so UDP and QUIC get a purpose-built sidecar. It is a small dual-stack UDP forwarder that, per datagram, consults a `FaultSchedule` pushed over a control API (mirroring toxiproxy's provisioning ergonomics) and applies `delay/drop/reorder/duplicate/corrupt`. Because it acts on the *k-th datagram*, not a probability, a recorded schedule reproduces exactly.

- **New sidecar** `test-harness/udp-toxi/` (+ compose service, + controller `/describe` manifest entry).
- **`udp-echo` sidecar** `test-harness/udp-echo/` — the datagram analogue of `echo` (socat `UDP-LISTEN:…,fork EXEC:cat`-style).
- QUIC gets wire faults **for free**: point `quic-echo` upstream through `udp-toxi` (P2), so `quicEcho()` finally has loss/reorder/duplication in Tier-C — today it has none outside the sim.
- Same-toxiproxy port-isolation discipline applies: name+port isolate the relay per Gradle test-task family (root vs. `:socket-testsuite`), per the `suite-echo`@15900 lesson in `[[test-harness-container-direction]]`.

### 5.1 Consumer surface (one function, three stacks)

`withNetworkHarness { }` stays the single, skip-safe entry point (unreachable controller → skip, never flaky-fail). New accessors:

```kotlin
withNetworkHarness {
    // TCP (today)
    roundTrip(echo(), "hi"); tls(TlsScenario.EXPIRED); impaired(latency = 200.ms) { … }

    // UDP (new)
    val e = udpEcho()
    impairedUdp(FaultSchedule { drop(nth = 3); reorder(window = 2) }) { proxy -> … }

    // QUIC (new: faults on the existing accessor)
    impairedQuic(FaultSchedule { drop(nth = 5); delay(20.ms) }) { proxy -> … }
}
```

## 6. Determinism envelope per stack (be honest about the cap)

| Stack | Tier-A determinism | Why |
|---|---|---|
| **TCP** | bit-exact | we own the whole stack; virtual clock is authoritative |
| **UDP** | bit-exact | same — no third-party engine between us and the socket |
| **QUIC** | trace-prefix-exact, ±1 per-side datagram | **quiche's C FFI is not caller-clocked** (`quiche_conn_timeout_as_nanos`/`on_timeout` use Rust `Instant::now()`); BoringSSL RNG + real-time ACK races add ±1 drift — see RFC_DETERMINISTIC_SIMULATION §4 and `[[test-harness-container-direction]]` |

Until §6.1 lands this asymmetry is a **feature, not a defect**: TCP and UDP Tier-A are *more* deterministic than QUIC's, so they carry the bit-exact regression corpora while QUIC leans on Tier-C (`udp-toxi`) for loss/PTO realism. But we are **not** leaving the QUIC gap open — §6.1 closes it.

### 6.1 Closing the QUIC gap — caller-clock + deterministic-RNG patch (P7)

Earlier framing called the quiche clock issue an out-of-scope upstream problem. That was too pessimistic, because **we build quiche from source and already patch it.** `socket-quic-quiche/build.gradle.kts` shallow-clones `cloudflare/quiche.git` pinned at `0.29.2` and applies two idempotent, marker-guarded source patches (`build.rs` BoringSSL-dedup + unversioned-soname). A caller-clock patch **rides the same mechanism** — a source patch we maintain, not a fork, not a binary swap.

**Shape of the patch (measured, not guessed).** An earlier draft claimed the clock was localized to a `connection/mod.rs` boundary (≈a dozen sites). That was wrong — the spike has been run against the actual `0.29.2` tree:

- there is **no `connection/mod.rs`**; `Connection` lives in `quiche/src/lib.rs`;
- `Instant::now()` appears at **72 sites across ~15 files** (≈55 production, excluding `tests.rs`/`test_utils.rs`): `lib.rs` (10), `recovery/mod.rs` (14), `minmax.rs` (11), `path.rs` (8), `congestion/delivery_rate.rs` (5), plus scattered `bbr`/`gcongestion`/`packet`/`flowcontrol` sites.

So it is **not** the case that all time is threaded down from one boundary — leaf modules (`minmax`, `delivery_rate`, congestion controllers) read the clock directly. A per-`Connection` field therefore can't reach them. The tractable design is instead a **uniform mechanical substitution**:

- add a tiny internal `crate::now() -> Instant` that returns a **thread-local virtual time when set**, else real `Instant::now()` (quiche is single-threaded per connection in our usage → thread-local is sound and **zero cost / zero behavior change in production**);
- replace all 72 `Instant::now()` call sites with `crate::now()` — a regex substitution, appliable by the same marker-guarded patch mechanism as `build.rs`;
- expose one C FFI entry (`quiche_conn_set_virtual_time_nanos`) that drives the thread-local for the connection's thread.

That makes loss/PTO/timeout **caller-clocked**, killing the "real-time ACK races" half of the ±1 drift. The uniform-substitution shape keeps the patch mechanical, but **72 sites across recovery internals is a wider blast radius than the two `build.rs` patches** — it will conflict more often on quiche bumps, which is precisely why **upstream-first** (below) is the real plan, not a nicety.

**The other half is BoringSSL's RNG** (packet-number / CID / nonce material). Clock injection alone gets QUIC Tier-A to *near*-bit-exact; **full** bit-exactness also needs a **deterministic, test-only seeded `RAND_METHOD`** installed on the test BoringSSL (a standard technique — never in production). Two independent knobs: ship the clock patch first (bigger win, simpler), RNG-seeding as a fast follow.

**Costs, stated plainly:**
- **Maintenance tax** — the patch is fitted to `0.29.2`'s `connection/mod.rs`; a quiche bump re-fits it. Marker-guarded like the existing patches → it fails loudly if upstream drifts, never silently mis-patches.
- **Upstream-first** — a pluggable clock is genuinely useful to Cloudflare (they want deterministic sim too) and is the right long-term home. Contribute it upstream; carry the patch only until it lands.
- **Cross-platform build fallout** — none beyond what we already own: the patch applies inside the existing per-platform cargo build (JVM JNI+FFM / Apple / Linux / Android NDK); no new toolchain.

**Resulting envelope after P7:** TCP/UDP bit-exact (unchanged); **QUIC bit-exact with clock+RNG**, near-bit-exact with clock alone. The `[[test-harness-container-direction]]` "quiche FFI not caller-clocked" cap is retired, not merely worked around.

**Version target — bump to `0.29.3` first (P7 precursor).** We pin `0.29.2`; upstream `0.29.3` is out. The `0.29.2 → 0.29.3` diff touches `quiche/src/lib.rs` (+46/−85 — the exact file the clock patch lands in), `recovery/*`, `ffi.rs`, and `quiche/include/quiche.h` — so patching `0.29.2` would mean re-fitting the clock patch immediately. Bump first, then patch the version we actually ship. The bump is independently worth it:
- **CID/DCID retirement handling improvements** — migration machinery, directly relevant to PR #245 auto-migration;
- **H3/QPACK correctness fixes** (frame-size limits, a QPACK size-calc bug, incremental state-buffer allocation) — relevant to `:socket-http3` / `[[h3-qpack-conformance-plan]]`;
- flow-control fix, `MAX_PTO_EXPONENT` removal.

Bump checklist: re-verify cinterop/JNI against the changed `quiche.h` + `ffi.rs` (the `crypto/boringssl.rs` "drop c_void return" is an FFI-signature change); confirm the two existing `build.rs` patches still apply (`build.rs` is **not** in the 0.29.3 diff → they should); update `quicheSha256`. This bump is a clean standalone PR even if P7 slips.

**Spike outcome (already measured):** the `Instant::now()` surface is **72 sites / ~15 files**, not a dozen in one file (see "Shape of the patch" above). P7 proceeds with the thread-local `crate::now()` substitution design; the width is the reason upstream-first leads.

**✅ P7 SPIKE BUILT + VALIDATED on linuxX64 (2026-07-21, branch `feat/quiche-caller-clock`).** The clock half is implemented end-to-end and green against the current self-built BoringSSL:

- **Source patch** — `patchQuicheForCallerClock(sourceDir)` in `socket-quic-quiche/build.gradle.kts` (rides the existing `downloadQuicheSource` patch chain, alongside the two `build.rs` patches). It is **marker-guarded** (`socket-caller-clock` in `lib.rs`, written **last** so the marker means all steps completed) and **fails loudly on drift** (after the rewrite it asserts no `Instant::now()`/`.elapsed()` remains — a bump that adds a clock site throws instead of silently leaving a real-time read). Four edits: (1) rewrite all 72 `Instant::now()` **plus the one `pkt_space.largest_rx_pkt_time.elapsed()`** → `crate::now()` across `quiche/src`; (2) append the `crate::now()` machinery to `lib.rs` (thread-local `Option<Instant>` + a fixed per-process `OnceLock` anchor + `set_virtual_time_nanos`/`clear_virtual_time`); (3) export `quiche_set_virtual_time_nanos(u64)` / `quiche_clear_virtual_time()` from `ffi.rs`; (4) declare them in the source `quiche.h`. **`nm libquiche.a` confirms both symbols exported (`T`).**
- **Kotlin seam** — `DriverClock.quicheTime(): DriverTime` (sealed `Real | Virtual(nanos)`, not a nullable — no impossible states). `CallerClockQuicheApi` decorates `QuicheApi`, pinning the clock **in the same synchronous frame** as each connection call (same OS thread, no suspension between push and read); installed only under a virtual clock, so production keeps the bare api at zero cost. `QuicheApi.setThreadVirtualTimeNanos`/`clearThreadVirtualTime` are bound on all four real backends (JNI c+kt, FFM, apple+linux cinterop), no-op default for test doubles.
- **Header gotcha (load-bearing):** the K/N cinterop **and** the JNI shim read the *committed, vendored* `socket-quic-quiche/libs/quiche/include/quiche.h` (via `-I`), which the build does **not** overwrite (`copyTo` is guarded `!headerDest.exists()`). So the two FFI decls must be **committed into that vendored header** — patching only the build-time source `quiche.h` is invisible to the binding. This is why the base 0.29.3 build ships a deliberately ABI-subset-compatible 0.29.2-era vendored header.
- **Validation (linuxX64, alien1, self-built BoringSSL):** `LinuxCallerClockTests.virtualTimeDrivesQuicheInternalClock` — a real quiche client `Connection`, timers armed by one `send()`, clock advanced only via the FFI: `connTimeout` **shrinks** as virtual time advances (the discriminator — on an unpatched lib it would not move) and the connection **idle-times-out on the virtual clock with no real sleep (2 ms)**. Production regression green: `LinuxQuicSmokeTest` + `LinuxQuicIdleTimeoutTests` (real 2 s idle) + `LinuxQuicImpairmentTests` (loss/reorder/jitter/blackhole recovery) all pass with intact real-clock timings — **nothing sets the thread-local, so `now()` == `Instant::now()`, zero behaviour change**.

**Remaining for full P7 (this spike deliberately scoped to the clock, on linux):** (a) **deterministic BoringSSL RNG** — the other ±1-drift half (packet-number/CID/nonce), a test-only seeded `RAND_METHOD`; clock alone is *near*-bit-exact, clock+RNG is bit-exact. (b) **Wire the seam into the Tier-A sim** — a `SimClock`-backed `DriverClock.quicheTime()` so `withNetworkHarness` QUIC Tier-A actually drives it (the plumbing exists; the sim just returns `Real` today). (c) **Validate JVM (JNI+FFM) and Apple backends** — Kotlin compiles on all; the JVM `.so` / Apple `.a` need a patched rebuild + a per-backend behavioural test (linux cinterop is the only one exercised so far). (d) **Upstream-first** — contribute the pluggable clock to Cloudflare; carry the patch until it lands. (e) **BoringSSL provisioning is orthogonal:** this was validated on socket's *self-built* pin (`f1c75347`, ships 0.29.3); the pending `boringssl-kmp` canonical-plugin flip (buffer #298, pin `44b3df6f`, 0.29.2 anchor) is a **separate later merge point** — re-confirm/bump ABI compat when quiche flips onto the canonical BoringSSL.

## 7. `NetworkMonitor` extraction — module + its own recorder/harness

`NetworkMonitor` lives in the **root `:socket`** module today. PR #245's own follow-ups already commit to extracting it; this RFC adds the harness dimension and orders it **after** `:socket-testkit` lands so it reuses the neutral `TraceSink` rather than inventing a parallel format.

```
:network-monitor                        (NEW · published · com.ditchoom:network-monitor)
 ├─ NetworkMonitor + networkId (Link/kind/index model, sealed route states)
 ├─ platform actuals                    JVM(FFM+polling) / Apple(getifaddrs+NW) / Android(Context) / Linux(netlink) / JS
 ├─ NetworkMonitorRecorder              captures Observed.{NetworkChanged, AvailabilityChanged} → neutral TraceSink
 └─ ScriptedNetworkMonitor  (in testkit-backed test fixtures)  replays a path-change timeline
```

- **Build template:** `:socket-udp` (per the PR #245 follow-up note) — including the cinterop-package strategy to avoid symbol clashes.
- **`NetworkMonitorRecorder`** writes to the **same neutral `TraceSink`**. The sim's `Observed` vocabulary already contains `NetworkChanged`/`AvailabilityChanged` (see `SimTrace`), so the fixture grammar is already half-specified.
- **`ScriptedNetworkMonitor`** is the missing piece for **deterministic migration tests**: PR #245's `AutoMigrationWiring` reacts to `NetworkMonitor.networkId`, but with no scripted/fake monitor today, "phone walks off Wi-Fi onto LTE" cannot be triggered hermetically. The fake makes it a plain `commonTest` on every platform — and directly de-risks the auto-migration feature.
- **Reuse target:** `../webrtc` (stated in PR #245) and the fallback layer both consume `:network-monitor` + its recorder for free.
- **Dependency direction:** `:socket-quic-quiche` → `:network-monitor` (its auto-migration reactor already depends on the monitor), and both → `:socket-testkit` for the shared `TraceSink`. No cycles (mirrors RFC_TRANSPORT_FALLBACK §9 discipline).

## 8. Phasing (each phase = one mergeable PR)

| Phase | Deliverable | Unblocks |
|---|---|---|
| **P0** | Create `:socket-testkit`; move `TraceSink`/`TraceEvent` + `ImpairedPipe` in, de-namespace, make `QuicTraceEvent` a projection. Pure move, no behavior change. | everything |
| **P1** | UDP Tier-A (sim + first fixtures) **and** Tier-C (`udp-echo` + `udp-toxi` sidecars, `udpEcho()`/`impairedUdp()`). | P2, directly supports #245 migration story |
| **P2** | Route `quic-echo` through `udp-toxi`; add `impairedQuic()`. QUIC gets hermetic wire faults for the first time. | — |
| **P3** | TCP Tier-A sim (it has containers but no in-process sim), reusing P0. | full A/C parity |
| **P4** | Generalize fixture-codegen + fuzzer+ddmin to all three; seed regression corpora. | durable regressions |
| **P5** | Extract `:network-monitor` + `NetworkMonitorRecorder` + `ScriptedNetworkMonitor`. Depends on P0's `TraceSink`. | webrtc reuse, deterministic migration tests |
| **P6** | Package `:socket-testkit`/`:socket-testsuite` for external consumption: one dependency + `docker compose up` runs all three stacks on every target. | KMP consumers |
| **P7** | quiche caller-clock source patch (§6.1) + deterministic-RNG follow-on → QUIC Tier-A bit-exact. Spike-gated. | retires the QUIC determinism cap |

**Ordering constraint:** P0 before everything; P5 after P0. P1→P2 sequential (P2 reuses the relay). P3/P4 parallelizable once P0 lands.

**Cross-cutting (§10):** the new `udp-echo`/`udp-toxi` sidecars in P1 ship as `linux/amd64`+`linux/arm64` manifest lists from day one, and P6 wires the `HARNESS_RUNTIME=docker|apple` selection + the no-`platform`-pin lint. Native multi-arch is a P1 acceptance criterion, not a P6 afterthought — a sidecar that only exists as amd64 would force qemu on every Apple-Silicon dev machine.

**P7 is independent** of P0–P6 and slots after P2 (QUIC Tier-C must exist to cross-check the caller-clocked sim against a real run). It can proceed in parallel with P3–P6.

## 9. Local-dev & CI constraints (inherited, still binding)

- **Clean local gate:** `HARNESS_DISABLED=true ./gradlew ktlintCheck jvmTest macosArm64Test` — TLS-CA / rst harness tests fail on dev Macs by design (keychain CA → guaranteed `-9808` if forced; rst sidecar unreachable from the macOS host). New UDP/QUIC fault suites must be equally skip-safe.
- **GH-hosted macOS CI is out for containers** (colima-only, slow) — macOS harness use is local dev (Docker Desktop / Apple `container`). UDP/QUIC Tier-C therefore validates on Linux CI; Tier-A (the deterministic tier) is what runs on Apple in CI.
- **Proxy isolation:** every new proxy/relay gets its own name+port per Gradle test-task family, or toxics/schedules bleed across parallel tasks.
- **`docker compose up` never rebuilds** — `harnessUp` passes `--build`; the new sidecars must be covered by it.

## 10. Container runtimes & native multi-arch (no emulation)

The harness must run **natively on both host architectures** — arm64 (Apple Silicon dev machines, arm64 Linux CI) and amd64 (Intel Macs, x86-64 Linux CI) — under **two runtimes**: **Docker** and **Apple `container`**. Emulation (qemu-user / Rosetta-for-Linux) is disallowed *during tests*: it silently changes timing, which corrupts the very latency/loss/reorder behavior this harness exists to measure, and it is slow.

### 10.1 The constraint

- **Apple `container`** runs an **arm64 Linux** guest on Apple Silicon (it does not emulate amd64). It **requires** an `linux/arm64` image variant for every sidecar. This is the primary local-dev runtime on Macs (Docker Desktop is the alternative; `[[linuxarm64-apple-container-testing]]`).
- **Docker on Linux CI** is `linux/amd64` (GH-hosted `ubuntu` runners) — requires the `linux/amd64` variant.
- **GH-hosted macOS CI stays out** for containers (colima-only, slow — `[[test-harness-container-direction]]`); Apple's Tier-C therefore runs on **local** Macs via Apple `container`/Docker Desktop, and Tier-A (pure Kotlin, no container) is what Apple runs in CI.

### 10.2 The rule: every sidecar image is a multi-arch manifest list

Each `test-harness/*` image (`controller`, `echo`, `http`, `tls`, `rst`, `quic-echo`, **`udp-echo`**, **`udp-toxi`**) publishes a **manifest list covering `linux/amd64` + `linux/arm64`**, so *both* runtimes pull the variant matching the host — natively, no `--platform` override, no qemu.

- **Build:** `docker buildx build --platform linux/amd64,linux/arm64 --push` for the GHCR-published images (extends the W7 GHCR pipeline). Cross-arch image builds are fine to emulate at *build* time on CI (build correctness is arch-neutral); the ban is on *running the tests* under emulation. Where a base is JVM (`controller`, `quic-echo`, `udp-echo` if JVM-backed), the jar is arch-neutral and only the **JRE base image** must be multi-arch — trivially true for Temurin/eclipse-temurin.
- **`udp-toxi` language choice is now load-bearing:** if written in a JVM/Kotlin (matches the codebase, jar is arch-neutral, one multi-arch JRE base) or Go (trivial `GOARCH` cross-compile, static, tiny image) it multi-arches cleanly. **Avoid** anything needing per-arch native C toolchains in the image. Leaning JVM/Kotlin for reuse of the existing `FaultSchedule` types, Go only if datagram throughput demands it.
- **`netem`/`tc` (blackhole) and `socat` (echo)** already have multi-arch upstream bases — no action beyond declaring both platforms.
- **Apple `container` note:** it consumes the same OCI manifest lists and selects `linux/arm64` automatically. No separate image set — the multi-arch manifest is the single source for both runtimes.

### 10.3 CI & guardrails

- **Compose/`harnessUp` must not pin `platform:`** to a fixed arch — let the runtime select by host. A stray `platform: linux/amd64` in `docker-compose.yml` would force qemu on Apple Silicon; add a lint/CI check that no service pins a platform.
- **Runtime abstraction:** `harnessUp` already shells `docker compose`; add an `apple` path (`container`/`container compose` equivalent) selected by env (`HARNESS_RUNTIME=docker|apple`), so the same Gradle task drives both. The `--build` + name/port-isolation discipline (§9) applies to both.
- **Assert native execution in CI:** a smoke step logs `uname -m` inside a sidecar and fails if it doesn't match the host arch — cheap insurance that no emulation slipped in.

## 11. Resolved decisions

1. **`udp-toxi` control protocol → HTTP/REST control plane over TCP (toxiproxy-shaped), UDP data plane.** *Separate the two planes.* The **control plane** — provisioning a `FaultSchedule`, clearing it — is a plain HTTP/1.1 REST API over **TCP**, identical in shape to toxiproxy's own control API (toxiproxy listens on `:8474/TCP` to configure proxies whose *traffic* may be anything). The **data plane** — the datagrams `udp-toxi` actually impairs and forwards — is **UDP**. No HTTP/3 anywhere: the control API is *about* UDP traffic, it does not *run over* it. This reuses the existing `ToxiproxyClient` HTTP-over-`ClientSocket` code and keeps `impaired` / `impairedUdp` / `impairedQuic` provisioning symmetric. The `FaultSchedule` is the REST body.
2. **Fixture grammar → extend v1 ASCII (call it v2), not a binary format.** Add a `transport` tag + `connectionId` + `unitIndex` to the existing line grammar. Human-readable / git-diffable has already paid off, and `:socket-quic-trace-tools`' deobfuscator already parses the ASCII line — a binary format would orphan that tooling. v1 lines remain a valid v2 subset, so the QUIC projection is unchanged.
3. **TCP unit granularity → byte-run canonical; segment-level is a Tier-A-only opt-in.** toxiproxy can't see TCP segments, so Tier-C is byte-run. Make **byte-run the canonical unit** so Tier-A and Tier-C stay in parity, and expose segment boundaries only as a clearly-marked Tier-A-only advanced knob, documented as *not reproducible in Tier-C*. One mental model by default; the sharper tool is opt-in and labeled.
4. **Browser targets → Tier-A in P6; Tier-C-from-browser deferred (fast-follow).** `:socket-testkit` Tier-A is pure Kotlin, so wire js/wasmJs in P6 — deterministic sim in the browser is cheap and high-value. Tier-C *from* a browser needs a `fetch`/WebTransport control transport (the controller already serves CORS); real but deferred to a fast-follow so it doesn't block P6.

## 12. Open questions

*(none blocking — the four above are resolved; §6.1's spike gate is the one remaining "confirm before building" item.)*
