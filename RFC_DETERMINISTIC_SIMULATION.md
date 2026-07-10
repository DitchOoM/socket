# RFC — Deterministic network simulation, trace replay & the consumer test harness

**Status:** Draft — implementation in progress on `feat/deterministic-sim-harness`.
**Builds on:** [`RFC_UNIFIED_ESTABLISHMENT.md`](./RFC_UNIFIED_ESTABLISHMENT.md) (Transport/SessionTransport model, typed error vocabulary) and [`RFC_TRANSPORT_FALLBACK.md`](./RFC_TRANSPORT_FALLBACK.md) (NetworkMonitor/networkId, injected-clock precedent). Extends the existing deterministic seams: `StubQuicheApi`/`StubUdpChannel`/`ManualDriverClock` (quiche driver), `ScriptedTransport` + virtual-time racing tests (fallback layer), the `Liveness` seam (#222), and the committed-corpus replay discipline from `socket-http3`.

## 1. Goal

Three capabilities, built as **one event-timeline engine**:

1. **Deterministic replay** — a bug observed on a real device (Android field crash, iOS reconnect hang) is captured as a *fixture* and replayed byte- and order-exactly on every platform, forever, in milliseconds. Field bugs become permanent regression tests.
2. **Deterministic exploration** — a seeded generator produces adversarial event timelines (connectivity flaps mid-handshake, ENETDOWN during drain, timeout-vs-datagram races) over the same engine, with invariant checks and a shrinker that minimizes failures into committed fixtures. This targets the *event-ordering* bug class (recv_info UAF, reconnect races, drain storms) that byte-level fuzzing cannot reach.
3. **Consumer-facing control** — a consuming library or app writes plain `commonTest` code that provisions real network conditions (TLS cert matrix, latency, resets, blackholes, QUIC echo) through a published harness it controls programmatically — no docker CLI, no platform-specific test code.

A fixture is a recorded timeline; a fuzz case is a generated timeline; the corpus is shrunk failing timelines. Everything replays through the same interpreter.

## 2. Inputs vs. observations — the two roles of recorded data

The fixture format separates two kinds of recorded data, because they are used differently at replay time:

**Input events** are injected through seams to *drive* the code under test:

| Event | Replay seam |
|---|---|
| `DatagramIn(bytes, from: PathKey)` | `UdpChannel` stub |
| `SocketError(errno/type)` | `UdpChannel` / socket stub |
| `AvailabilityChanged(AVAILABLE/UNAVAILABLE/UNKNOWN)` | scripted `NetworkMonitor.availability` |
| `NetworkChanged(NetworkId: Unidentified/KindOnly/Link)` | scripted `NetworkMonitor.networkId` |
| `LivenessResult(Alive/Dead/Unknown)` | scripted `Liveness` (#222) |
| `ClockAdvance(Δt)` | `TestClock` |

(No expensive/constrained path flags exist in the `NetworkMonitor` model today, and there is no DNS-resolver seam — both are candidate future event types, not v1.)

**Observation snapshots** are *not* injected; they are recorded alongside the inputs and become golden assertions — replay must walk the same trajectory, not merely avoid crashing:

- quiche path stats (RTT, cwnd, delivery rate, lost/retransmitted/sent/recv counts, PMTU) sampled periodically and at state transitions,
- our own `ConnectionState` transitions and reconnect attempts,
- buffer accounting (allocation/free balance).

Packet loss is the instructive case: loss is not an injectable event — it manifests in the input timeline as *absent datagrams plus timeout firings*. But the recorded loss/RTT metrics let replay assert the driver *responded* to that loss the same way. (In Tier B simulation — §4 — loss *is* an input: a seeded impairment parameter. That is exactly why the tiers stay distinct.)

## 3. The timeline engine

```
fixture / generator
        │  (input events, ordered by one clock)
        ▼
  TimelineInterpreter ──▶ seams: UdpChannel · NetworkMonitor · Liveness · DNS · TestClock
        │
        ▼
  code under test (quiche driver / ReconnectingConnection / FallbackTransport / QuicConnection)
        │
        ▼
  observations ──▶ compared against recorded snapshots + invariants (§6)
```

- **One clock.** All timestamps come from a single `TestClock` that bridges the coroutine test scheduler's virtual time and the driver clock (the production seam behind `ManualDriverClock`). Any skew between the two reintroduces flakes in the harness meant to kill them, so the bridge is load-bearing.
- **Virtual time.** Recorded timestamps map to virtual time: a 90-second field reconnect saga replays in milliseconds; 500 fixtures stay inside unit-test budget.
- **Every platform.** The engine is pure common Kotlin over existing common seams — it runs in `commonTest` on JVM, Android, Linux K/N, Apple, and (for the non-quiche layers) JS.

### 3.1 Seam changes required (nondeterminism audit)

The audit found the timing hot paths largely seamed already: the driver's keepalive/idle timers route through `DriverClock` (default `RealDriverClock`), and the reconnect layer (`ReconnectingConnection`) is *fully* virtual-time-drivable today — its backoff is deterministic exponential with **no jitter or randomness**, its `withTimeoutOrNull(retryDelay)` backoff-vs-networkId race inherits the caller's context, and `NetworkMonitor`/`Liveness`/`ReconnectionClassifier`/`CapabilityCache.timeSource` are all injectable. The remaining production changes (all constructor/param plumbing — no cinterop/JNI work):

1. **Driver loop dispatcher** — `QuicheDriver` launches its control loop with a hardwired `Dispatchers.Default`, overriding the caller's scope. This is *the* blocker: it keeps `DriverClock.armTimeout` wakes off any virtual-time scheduler. Fix: launch in the caller's context (or a `driverDispatcher` param defaulting to `Dispatchers.Default`). The UDP reader loop stays on real I/O — sims replace the channel itself.
2. **Plumb `DriverClock` to the connect API** — the seam exists but none of the six platform construction sites (apple/linux/jvm × connection/server) forward a clock; all get `RealDriverClock`. Expose it through the internal connection factories.
3. **Seed the entropy** — connection IDs (`generateScid`) and stateless-reset tokens use hardwired `kotlin.random.Random` (common + apple/linux inline fills). Add an injectable `Random` defaulting to `Random.Default`; one seeded instance makes connection IDs reproducible.
4. **Wire the cert-validity clock** — `ServerCertificateHashVerifier`/`X509PinFields` already take `now: Instant = Clock.System.now()` but all three platform callers drop the parameter; thread it from connect options.

Out of scope (below the transport waterline, real I/O by nature): JVM NIO selector deadlines, io_uring timeouts, platform monitor poll loops (sims inject `MockNetworkMonitor` instead), the blocking-UDP dispatcher. Observability-only wall-clock stamps (`lastMessageReceived` etc.) get seamed opportunistically, not as blockers.

### 3.2 Build vs. reuse

The repo already contains most of the engine's parts; audit of the existing test infrastructure yields:

| Concern | Reuse | New work |
|---|---|---|
| Driver time control | `DriverClock` (production seam) + `ManualDriverClock` (race-free hand-fired timer: `advance()` rendezvous + re-arm sync) | — |
| Coroutine virtual time | kotlinx-coroutines-test (`runTest`, `testScheduler`, `currentTime`) — already a dependency of every relevant test source set; `socket-testsuite` exposes it as `api` | **TestClock bridge**: today the driver's `DriverClock` world and the `TestScope` virtual-time world are disjoint (driver tests run real-dispatcher `runQuicTest`; transport tests run `runTest`). The interpreter unifies them behind one clock. |
| quiche scripting | `StubQuicheApi` (full FFI surface: scripted stream recv/send results, close reasons, datagram queues, writable signals, recording counters) | packet-level inbound scripting: `StubUdpChannel.receive()` currently suspends forever (tests run `clientMode=false`); the interpreter feeds inbound datagrams on schedule with `clientMode=true` |
| UDP error injection | `StubUdpChannel(sendBehavior)` + the gated-send pinning technique from `ReactiveDriverTests` | — |
| Connectivity scripting | `MockNetworkMonitor` (`set`/`setNetworkId` over `StateFlow`s), `ScriptedTransport` outcome scripting, `FallbackTransport(networkId = {...})` | promote `MockNetworkMonitor` from root `commonTest` into the published harness |
| Connection-surface scripting | `MockQuicConnection` (quiche-free `QuicConnection`: `injectPeerStream`, `transitionTo`) | give it a clock seam (it currently runs on real `Dispatchers.Default`) |
| Timeline | `QuicheCmd` command channel, `ScriptedTransport.Outcome` per-dimension scripts | **ordered heterogeneous event timeline** — nothing today expresses "a scheduled sequence of events across dimensions"; each harness scripts one dimension independently |
| Assertions | `TrackingBufferFactory.assertNoLeaks()`, `StateFlow` state assertions, stub recording counters, `currentTime` timing assertions | **trace-oracle**: assert on the ordered emitted-event trace (golden trajectory), not just final scalar state |
| Fixture files | embedded-hex corpus pattern (portable to every platform; mirrors `fuzz/corpus/` files by hand) | **fixture codegen**: captured bundles are too big to hand-mirror — a converter emits a generated `.kt` (hex) file per fixture, keeping the proven no-runtime-IO portability instead of building an expect/actual resource reader |

## 4. Two replay tiers

**Tier A — driver replay (`StubQuicheApi`).** Byte-exact; tests *our* driver/lifecycle/reconnect code. This is the only correct home for field fixtures: encrypted field traffic cannot be re-decrypted by a fresh quiche instance (new keys), so recorded ciphertext replays against the stub, which reproduces the driver-visible behavior exactly.

**Tier B — semantic simulation (real quiche).** Real quiche client ↔ real quiche server over an in-memory pipe with seeded impairment (loss / reorder / duplication / delay).

*Virtual-time reality check (W4 implementation finding):* the original premise that quiche is caller-clocked is **wrong for the C FFI we bind** — `quiche_conn_timeout_as_nanos`/`quiche_conn_on_timeout` take no `now`; quiche stamps packets and computes deadlines against Rust's internal `Instant::now()`. Consequently:
- **Under virtual time:** pure event cascades work — a full real-quiche handshake completes under `runTest` with zero wall-clock (proven by `lossless_handshake_completes_under_virtual_time`).
- **Not under virtual time:** anything depending on quiche's internal timers (PTO loss recovery, idle timeout). Those scenarios run on real dispatchers with scaled-down timers, bounded by the wall-clock test runner. The unlock would be a `now`-taking quiche FFI surface (upstream patch); until then, timer-dependent timelines are Tier A territory.

*Determinism per seed (measured):* the impairment decision sequence and scenario outcome are strictly stable (asserted as trace-prefix equality); exact per-side datagram counts drift ±1 (BoringSSL's own RNG shapes TLS key shares → packet coalescing shifts; quiche's real-time delayed-ACK/PTO races arrivals). Tests assert the strong invariant strictly and bound the drift explicitly.

## 5. Capture — `TraceRecorder`

An opt-in tap that records, stamped by one clock:

1. datagram send/recv at the `UdpChannel` seam,
2. `NetworkMonitor` emissions (`availability` + `networkId`),
3. typed errors at the exception-mapping choke points,
4. periodic quiche path-stats snapshots + lifecycle state transitions,
5. qlog attachment (existing `QUIC_QLOG_DIR` support) for human diagnosis — qlog is *not* the replay format; it lacks connectivity/liveness events and platform error surfaces, is file-based on quiche's own clock, and doesn't exist on JS.

Output is a **versioned fixture bundle**. Consumers enable the recorder in a debug build of their app; when a field bug fires, the bundle *is* the bug report, and committing it *is* the regression test.

### 5.1 Tap points (capture audit)

All capture lands on existing seams except path stats:

1. **Datagram I/O** — a `UdpChannel` decorator wrapped around the channel passed to `QuicheDriver` (single platform-neutral choke point: one `send` in `flushOutgoing`, one `receive` in `udpReaderLoop`; same wrapper on the server's per-connection channels). Records `(t, dir, len, PathKey)` + payload.
2. **Connectivity** — collect `NetworkMonitor.availability` + `NetworkMonitor.networkId` (`StateFlow`s; every platform producer writes only these).
3. **Typed errors** — the four per-platform mapper funnels (`wrapJvmException`, Linux `mapErrnoToException`, Apple `NWSocketWrapper.mapSocketException`, Node mapping) plus the QUIC close reason at `QuicheDriver.resolveCloseError()`.
4. **Lifecycle** — collect `QuicheDriver.state` + `QuicheDriver.pathState` (QUIC) and `ReconnectingConnection.state` (transport); reconnect attempts and liveness teardowns already surface there. Probe outcomes via a decorating `Liveness`.
5. **Path stats — the one gap requiring new native work.** `quiche_conn_stats`/`quiche_conn_path_stats` (RTT, min/max RTT, rttvar, cwnd, delivery rate, lost/retrans, sent/recv bytes, PMTU) are **unbound on all four backends** today. Plan: add `connStats`/`connPathStats` to `QuicheApi`; cinterop targets are near-free (symbols already generated by `Quiche.def`), FFM needs new downcalls, JNI needs a shim addition. The driver polls on its existing timer wake. Until bound, loss/RTT visibility comes from the qlog attachment only.

One clock: the recorder reads the driver's injected `DriverClock` for QUIC-side events and aligns transport-side stamps to the same monotonic source.

## 6. The fuzz layer — deterministic simulation testing

1. **Timeline generator**: seeded RNG produces event schedules over Tier B — connectivity flaps mid-handshake, ENETDOWN during drain, migration during a stream write, a timeout firing one virtual tick before a datagram lands. Every failure prints its seed; every seed reproduces exactly, on any platform.
2. **Invariants instead of example assertions**:
   - no buffer leaks (`TrackingBufferFactory`),
   - `ConnectionState` never takes an illegal transition,
   - every native wrapper freed (the 150-created/0-freed class),
   - errors surface **typed**, never as strings (standing directive),
   - liveness: the connection eventually reconnects or surfaces a typed terminal error — it never hangs.
3. **Shrinker**: on failure, minimize to the smallest failing timeline prefix/subset and commit it as a fixture — the same discipline as Jazzer crashers becoming `Http3RoundTripCorpusReplayTests` cases. Fuzz findings and field captures feed the *same* corpus.

## 7. The container harness control plane (integration tier)

The simulation tiers need no containers. The existing docker harness (`test-harness/`) remains the *integration* tier — real TLS stacks, real kernels, real impairment — and gains a control plane so consumers drive it from `commonTest`:

- **Controller service** in the compose stack (Kotlin/JVM container, same pattern as `quic-echo`): `GET /describe` returns a JSON manifest of available scenarios → host:port (consumers never read `harness.env`; scenarios unavailable on a given runtime — e.g. netem under Apple `container` — are simply absent from the manifest), plus health and `POST /capture/start|stop` (tcpdump/qlog bundles for §5).
- **Consumer API** in `socket-testsuite`'s **main** source set (the established trick that lets consumers' test source sets see it):

```kotlin
withNetworkHarness {                       // skip-on-unreachable, like today
    tls(Cert.EXPIRED) { ep -> /* assert typed failure */ }
    impaired(latency = 200.milliseconds, jitter = 50.milliseconds) { proxy -> … }
    peerReset(afterBytes = 1) { … }
    blackhole { … }                        // connect-timeout semantics
    quicEcho { … }
}
```

Implementation generalizes the proven `Toxiproxy.kt` pattern (commonTest HTTP control client over the library's own `ClientSocket`); browser targets get a `fetch`-based control transport + CORS on the controller. Static pinned ports stay (the `harness.env` rationale); dynamism goes through toxiproxy.

## 8. Publishing

- Multi-arch harness images → GHCR on release (`docker buildx`), so consumers run a published `harness.yml` with no repo checkout.
- `socket-testsuite` published to Central and added to the release + `validate-artifacts.yaml` loop (the socket-webtransport #188 lesson: every consumer-facing artifact must be in the validation loop).
- A consumer-smoke project proving the end-to-end story: pull images, `compose up`, run `withNetworkHarness` scenarios from a consumer `commonTest`, down.
- Local macOS dev: runtime-agnostic `harnessUp` (docker vs Apple `container`). GitHub-hosted macOS CI is explicitly out of scope (colima-only, slow).

## 9. Implementation plan (this PR)

| Wave | Scope | Depends on |
|---|---|---|
| W1 | Seams: driver-loop dispatcher un-hardwired, `DriverClock` plumbed to connect API, seeded `Random` for scid/reset tokens, cert-validity clock wired | — |
| W2 | Timeline core: event model, interpreter, TestClock bridge (DriverClock ↔ coroutine scheduler), golden fixtures | W1 |
| W3 | `TraceRecorder` + fixture bundles + fixture→Kotlin codegen; `connStats`/`connPathStats` bindings | W2 |
| W4 | Tier-B semantic simulator (real quiche, in-memory impairment pipe, seeded, virtual time) | W1 |
| W5 | Timeline fuzzer: generator, invariants, shrinker, corpus | W2, W4 |
| W6 | Container control plane: controller service, `/describe`, `withNetworkHarness` in `socket-testsuite` main source set, migrate existing harness tests | — (parallel) |
| W7 | Publishing: GHCR image workflow, testsuite in release/validate loop, consumer-smoke | W6 |

## 10. Non-goals

- Byte-replaying encrypted field traffic through real quiche (impossible by design — Tier A exists for this).
- Replacing the existing byte-level fuzzers (Jazzer, seeded H3/QPACK fuzz) — the timeline fuzzer targets event ordering, a different axis.
- Simulating NW-specific QUIC behavior — Apple QUIC is quiche (June 2026 pivot); the driver seams cover Apple like every other platform.
- Per-test dynamic container provisioning (real isolation for parallel consumers) — deferred until static-ports + toxiproxy proves insufficient.
