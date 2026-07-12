# RFC — The read-timeout contract (cross-platform)

**Status:** Proposed — not implemented. Emerged from an investigation into (a) exposing a virtual-thread I/O strategy on the JVM and (b) test-harness parity with QUIC. Both questions collapsed into a prior one: **the library has no defined read-timeout contract, and the six socket implementations disagree three different ways.** This RFC defines the contract and the per-platform conformance work; the virtual-thread strategy becomes a downstream implementation detail that must obey it.
**Builds on:** [`RFC_DETERMINISTIC_SIMULATION.md`](./RFC_DETERMINISTIC_SIMULATION.md) — the contract can only be *locked* with a deterministic "silent peer" harness that runs identically on every platform. That harness is the same infrastructure this repo already has for QUIC and lacks for TCP.

## 1. Goal

`read(deadline)` should mean the **same observable thing** on every platform. Today it does not. A consumer that sets `ReadPolicy.Bounded(15.seconds)` and reads from a peer that accepts the connection and then goes silent gets six different outcomes depending on where the code runs. For a library whose entire value proposition is *one API, every platform*, an undefined timeout contract is a latent correctness bug in the contract surface, not merely an implementation wart.

The read contract funnels through one place:

```kotlin
// buffer-flow: com.ditchoom.buffer.flow.ByteSource
suspend fun read(deadline: Duration): ReadResult      // deadline from ReadPolicy.Bounded / UntilClosed
```

and every JVM/native/JS implementation routes its throwing read body through `translateRead { readRaw(deadline) }` (`src/commonMain/kotlin/com/ditchoom/socket/SocketReadResult.kt:23`). `translateRead` catches only `SocketClosedException` (→ `ReadResult.End` / `.Reset`); a timeout is expected to propagate as a thrown exception. That is the only part of the timeout story that is currently uniform.

## 2. The finding — three axes of divergence

Traced end to end, per platform, for the case *"peer is connected but sends nothing"*:

| Platform | Impl (file) | Deadline enforced? | On timeout | Exception type |
|---|---|---|---|---|
| **JVM NIO2** *(the default)* | `AsyncBaseClientSocket` + `aRead` (`nio2/util/AsynchronousSocketChannelExtensions.kt:51`) | **Yes** | **Destructive** — JDK sets the channel's `readKilled`; later reads throw `IllegalStateException` | `SocketIOException` *(mis-mapped)* |
| JVM NIO blocking | `BaseClientSocket` + `SocketChannelExtensions.read` blocking branch (`:204`) | **No — silently ignored** | never fires (blocks forever) | none |
| JVM NIO selector | same, non-blocking branch + `aSelect` (`SelectorExtensions.kt:18`) | **Yes** | Non-destructive | `SocketTimeoutException` |
| **Apple** (NWConnection) | `NWSocketWrapper.readRaw` (`appleNativeImpl/.../NWSocketWrapper.kt:77`) | **Yes** (coroutine `withTimeout`) | **Destructive** — `invokeOnCancellation { closeInternal() }` cancels the NWConnection (`:105`) | `TimeoutCancellationException` |
| **Node.js** | `NodeSocket.readRaw` (`jsMain/.../NodeSocketClient.kt:128`) | **Yes** (coroutine `withTimeout`) | Non-destructive | `TimeoutCancellationException` |
| **Linux** (io_uring) | `LinuxClientSocket.readWithIoUring` (`linuxMain/.../LinuxClientSocket.kt:426`) + event-loop deadline (`IoUringUtils.kt:294`) | **Yes** (manual `prep_cancel64`) | Non-destructive | `SocketTimeoutException` |

Three independent inconsistencies:

- **Axis 1 — enforcement.** The JVM blocking path drops its `timeout` parameter entirely (`SocketChannelExtensions.kt:204` is `if (isBlocking) withContext(Dispatchers.IO) { suspendRead(buffer) }` — `timeout` is never read). A silent peer hangs the coroutine indefinitely. Every other implementation enforces the deadline.
- **Axis 2 — destructiveness.** NIO2 (the JVM *default*) and Apple **destroy the connection** on timeout; the socket is unusable for further reads. Node, Linux, and the JVM selector path **recover** — the next `read()` works. The split is ~50/50 and follows no stated principle; it is an artifact of each platform's primitive.
- **Axis 3 — exception type.** The same event surfaces as `SocketIOException` (JVM default), `SocketTimeoutException` (Linux, JVM selector), or a raw kotlinx `TimeoutCancellationException` (Apple, Node). A consumer's `catch (e: SocketTimeoutException)` succeeds on Linux and **silently misses** the JVM default and Apple.

There is no consistent baseline here to preserve — the behavior is undefined, and each platform picked a different point in the space by accident.

## 3. The contract

A `read(deadline)` where `deadline` is finite (`ReadPolicy.Bounded`) and the peer produces no data within `deadline`:

1. **Enforced.** The call completes within `deadline` (± scheduling slop). It never blocks past the deadline. `ReadPolicy.UntilClosed` (infinite deadline) is the *only* way to opt out of enforcement.
2. **Non-destructive.** The timeout aborts *this read only*. The connection remains open and a subsequent `read()` / `write()` behaves normally. A timeout is not a close.
3. **Uniform type.** It throws `SocketTimeoutException` — a `SocketException`, never a `SocketClosedException` and never a bare `TimeoutCancellationException`. `translateRead` continues to *not* catch it, so it propagates to the caller as-is.

### 3.3 The exception carries a typed payload, not a string (landed with Phase 2)

`SocketTimeoutException` carries a sealed **`TimeoutContext`** (`Connect` / `Read` / `Write` / `Platform`) as its source of truth; `message` is *derived lazily* from it (the throw path passes the interned `""` to the supertype and overrides the getter, so no string is allocated unless something reads `.message`). Callers branch on `when (e.context)`, not on parsed text — the timeout-half of the "model error causes as sealed types, not free-form strings" direction. `TimeoutContext.Platform(detail)` is the explicit quarantine for the sites where a platform primitive handed us only a string (errno text, a JDK exception message, the NIO selector) with no structured operation/deadline to recover; the read/write/connect paths the library owns use the typed variants. Those typed variants carry a **non-null `Duration` deadline** — "no structured detail" is the separate `Platform` *variant*, never an overloaded `null` field, so the two states can't be confused. `host`/`port` stay first-class fields so endpoint-aware mappers carry them regardless of variant.

### 3.1 Why non-destructive

`Bounded` exists to express *"give up on this read, but keep the connection."* That is what makes it useful:

- **Retry / poll.** "No data for 15s? Send a keepalive and read again." Impossible if the timeout closed the socket.
- **Multiplexing & fallback.** A timeout on one logical read must not tear down a connection carrying other work.
- **Separation of concerns.** "This read was slow" and "this connection is dead" are different facts. Conflating them (destructive timeout) forces the consumer to reconnect for a condition that was recoverable.

Destructive-on-timeout is not a design choice anywhere it occurs — it is what falls out of the primitive (JDK's `readKilled`; Apple's `closeInternal` in the cancellation handler). The non-destructive implementations, by contrast, are **deliberate**: Linux's `PendingOperation` doc (`IoUringUtils.kt:20`) explicitly cancels only the in-flight recv SQE and leaves the fd open, specifically to make bare timeouts recoverable *and* to avoid the read-buffer use-after-free (see §5). Linux is the reference implementation for this contract.

### 3.2 Note on the "orphaned read" technique

Making a timeout non-destructive on a *blocking* read means the read must be abandoned without cancelling the operation that owns the receive buffer — run the blocking read on a socket-scoped coroutine, and have the caller `withTimeout` only the `await`, so expiry stops the *waiting*, not the *reading*. The pending read then survives to be awaited by the next `read()` call (single-flight). This is the same shape as Linux's io_uring design and NIO2's async model. **The buffer's lifetime must follow the read, not the caller** — freeing/recycling the pooled buffer on timeout while a background read still writes into it is the exact use-after-free class already hit in the QUIC JNI path. Any implementation of this contract on a blocking primitive must tie buffer release to read completion, never to caller timeout.

## 4. Per-platform conformance gaps

| Platform | Gap vs. contract | Fix |
|---|---|---|
| JVM NIO blocking | ~~Axis 1 (not enforced)~~ **conformant (Phase 3 landed)** | Routed through the single-flight orphaned-read pattern (§3.2) in `BaseClientSocket.blockingReadRaw`: the actual `channel.read` runs on a socket-scoped coroutine that owns the receive buffer, the caller `withTimeout`s only the wait, and a timed-out read is left orphaned for the next `read()` to re-await. Enforced *and* non-destructive — all five assertions green. |
| JVM NIO2 *(default)* | ~~Axis 2 (destructive), Axis 3 (`SocketIOException`)~~ **conformant (Phase 4a landed)** | The JDK read-timeout is destructive by design (`readKilled`), so the plain read no longer passes a timeout to the JDK: `AsyncBaseClientSocket.orphanedReadRaw` runs the `aRead` on a socket-scoped coroutine with `Duration.INFINITE` (mapped to the JDK's *untimed* read overload in `aRead`, so `readKilled` is never armed), and the caller `withTimeout`s only the wait — the same orphaned-read single-flight (§3.2) as the blocking path. Enforced *and* non-destructive; Axis 3 was already fixed in Phase 2. *(The TLS raw-read path still uses the destructive JDK timeout — out of scope, mirroring TLS-over-blocking in Phase 3.)* |
| JVM NIO selector | conformant | Reference for the JVM side. |
| Apple | Axis 2 (destructive), Axis 3 (`TimeoutCancellationException`) | Remove `closeInternal()` from the receive cancellation path *for the timeout case*; distinguish "deadline elapsed" (keep connection, throw `SocketTimeoutException`) from "cancelled/closed" (tear down). |
| Node.js | Axis 3 (`TimeoutCancellationException`) | Already non-destructive; wrap the `withTimeout` expiry as `SocketTimeoutException`. |
| Linux | conformant | Reference implementation. |

### 4.1 Two exception bugs to fix regardless of the rest

Independent of the destructiveness work, these are outright contract violations of Axis 3:

- **NIO2 mis-maps read timeout to `SocketIOException`.** `InterruptedByTimeoutException extends java.io.IOException` with a null message, so `wrapJvmException` (`JvmExceptionMapping.kt:75`) has no case for it and falls through to the generic `SocketIOException` branch. Add an explicit `InterruptedByTimeoutException → SocketTimeoutException` case. (Also: the follow-up `IllegalStateException` from the killed channel is a `RuntimeException`, so it escapes the `catch (e: IOException)` in `AsyncBaseClientSocket.read:55` unwrapped — moot once the read stops being destructive.)
- **Apple and Node leak `TimeoutCancellationException`.** Both rely on coroutine `withTimeout`, whose expiry throws kotlinx's `TimeoutCancellationException` (a `CancellationException`). Catch it at the read boundary and rethrow as `SocketTimeoutException` so the consumer-visible type is uniform.

## 5. Where the virtual-thread strategy fits

The original ask — "let consumers choose virtual threads vs. the async API on JDK 21+" — resolves cleanly *once the contract exists*:

- VT is **not** a semantics knob. It is one more JVM implementation that must satisfy §3 like every other. Its natural home is the orphaned-read pattern (§3.2), which is exactly what non-destructive blocking reads require — so the VT strategy and the "make JVM NIO2 non-destructive" fix are largely the **same engineering**.
- VT's *only* consumer-visible benefit over the conformant selector/NIO2 path is debuggability: real thread-per-connection blocking stack traces. It is **not** a scalability win — NIO2 already parks zero threads per idle connection. So VT is a "nice to have for debugging," gated behind the contract, never a default.
- Recommended surfacing (deferred until the contract lands): a JVM-only `IoConcurrency` (`Auto` = the conformant async path; `VirtualThreads` = opt-in), living on `IoTuning` alongside the existing Linux-only `ioQueueDepth` knobs (same precedent: a platform-scoped tuning field, documented as ignored elsewhere). Resolving it needs config at socket-allocation time; see §7.

## 6. Test matrix — the contract is only real if it's proven identically everywhere

Per [`RFC_DETERMINISTIC_SIMULATION.md`](./RFC_DETERMINISTIC_SIMULATION.md), the contract needs a deterministic **"silent peer"** fixture: a server that accepts the connection and then never writes and never closes. The same `commonTest` assertions run on every platform:

| Assertion | Covers |
|---|---|
| `Bounded(d)` read of a silent peer throws within `~d` | Axis 1 (enforcement) — fails today on JVM blocking |
| the thrown type is `SocketTimeoutException` | Axis 3 (type) — fails today on JVM default, Apple, Node |
| after the timeout, a second `read()` succeeds once the peer finally sends | Axis 2 (non-destructive) — fails today on JVM default, Apple |
| `UntilClosed` read of a silent peer does **not** time out | enforcement opt-out is honored |
| a write after a read-timeout succeeds | non-destructiveness extends to the write half |

This is precisely the QUIC-vs-TCP harness gap: QUIC has deterministic impairment + clock seams (`ManualDriverClock`, in-process `ImpairingProxy`, `StubQuicheApi`); TCP has Toxiproxy + `ScriptedTransport` but no uniform deterministic timeout fixture. Building the silent-peer harness is a prerequisite, and doubles as the first real step of TCP harness parity.

### 6.1 Empirical baseline (Phase 1 landed 2026-07-12)

The harness (`SilentPeer` — an in-process `ServerSocket` that accepts then goes silent; needs no Docker/netem, so it runs identically on every platform) and the five assertions (`ReadTimeoutContractTests` in `commonTest`; the JVM-variant re-runs in `JvmReadTimeoutVariantTests`) are implemented. The assertions encode the §3 contract, so a failure *is* a divergence. Measured red/green:

| Impl | (1) enforced | (3) `SocketTimeoutException` | (2) read survives | opt-out | (2) write survives |
|---|---|---|---|---|---|
| **Linux io_uring** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **JVM NIO2** *(default)* | ✅ | ❌ `SocketIOException` | ❌ `IllegalStateException` "Reading not allowed…" | ✅ | ✅ |
| **JVM NIO selector** | ✅ | ❌ `TimeoutCancellationException` | ✅ | ✅ | ✅ |
| **JVM NIO blocking** | ❌ hangs (`WatchdogExpired`) | ❌ (never throws) | ❌ (never throws) | ✅ | ❌ (never throws) |
| **Node** | ✅ | ❌ `TimeoutCancellationException` | ✅ | ✅ | ✅ |

> **Phases 2–4a landed after this baseline was measured.** With Phase 2 the JVM-selector / Node type reds are now `SocketTimeoutException`. With **Phase 3** the JVM-blocking row is fully green — the orphaned-read single-flight (`BaseClientSocket.blockingReadRaw`, §3.2) makes it enforced *and* non-destructive across all five assertions. With **Phase 4a** the JVM-NIO2 read-half destructiveness (`IllegalStateException` "Reading not allowed…") is gone — `AsyncBaseClientSocket.orphanedReadRaw` applies the same single-flight pattern (untimed JDK read, caller `withTimeout`s only the wait), so JVM-NIO2 is now fully green. The **only** remaining red is Axis-2 destructiveness on **Apple** (`closeInternal` in the receive cancellation path) — RFC Phase 4b, validated on the macOS CI lane.
| **Apple** | *(CI-only; not measured on this box — predicted ❌ type, ❌ read survives per §4)* | | | | |

Red on 4 of the 5 measured impls (Linux is the sole fully-conformant one); Apple's reds surface on the macOS CI lane. **Two refinements to §2/§4's predictions fell out of the measurement:**

- **JVM NIO2 destructiveness is read-half only.** A `write()` after a read-timeout *succeeds* — the JDK `readKilled` flag kills the read side but leaves the write side live. §4's fix scope narrows accordingly: the NIO2 non-destructive work is about the read half, not a whole-connection teardown.
- **The JVM NIO selector path is *not* Axis-3 conformant.** §2/§4 called it "conformant / the JVM reference," but it throws a bare `TimeoutCancellationException`, not `SocketTimeoutException` — it needs the same Phase-2 TCE→`SocketTimeoutException` wrap as Apple/Node. **Linux is therefore the *only* true reference impl today; no JVM variant is fully conformant.**

`ReadOutcome` (the harness's classifier) converts "hangs forever" into a deterministic `WatchdogExpired` value so the non-enforcing blocking path fails as a fast assertion instead of the 30 s framework timeout.

## 7. The allocation seam (shared with future UDP work)

`ClientSocket.allocate()` picks the implementation class *before* `TransportConfig` exists — config only arrives at `open()` (`ClientSocket.kt:19`). Any per-connection choice of implementation (the `IoConcurrency` strategy; later, a datagram vs. stream socket) needs config at allocation time. Two options were on the table:

- **JVM deferring wrapper** — `allocate()` returns a thin `ClientToServerSocket` that resolves the concrete impl in `open()` and delegates the ~13 `ByteStream`/`SocketController` members. Localized, no signature change.
- **`allocate(config)`** — thread `TransportConfig` into the `expect`/`actual`. Cleaner long-term and directly reused by the phased UDP work, but ripples across all five platforms' actuals.

**Decision (landed 2026-07-12): `allocate(config)`, dropping `config` from `open()`.** The wrapper only solves the JVM `IoConcurrency` case; UDP needs datagram-vs-stream selection on *every* platform, so the wrapper would force either five per-platform wrappers or a later move to `allocate(config)` anyway. `allocate(config)` also makes `config` a **constructor val** on each impl, so `ByteSource.readPolicy` / `ByteSink.writePolicy` become real immutable vals instead of reassigned-at-`open` mutable state. Shape:

```kotlin
expect fun ClientSocket.Companion.allocate(config: TransportConfig = TransportConfig()): ClientToServerSocket
expect fun ServerSocket.Companion.allocate(config: TransportConfig = TransportConfig()): ServerSocket   // symmetric — accepted sockets inherit the config
interface ClientToServerSocket : ClientSocket { suspend fun open(port: Int, hostname: String? = null) }  // config removed
```

`connect()` now does `allocate(config).open(port, hostname)`. The default arg means no-arg `allocate()` call sites still compile; only `open(…, config)` sites changed. `ServerSocket` gained config too (it had none before) and threads it into every accepted socket, so server-side reads obey the same contract. The JVM global `useAsyncChannels`/`useNioBlocking` knobs still drive impl selection for now — folding them into a config-borne `IoConcurrency` field is the phase-5 follow-up (§5); this change only made config *available* at `allocate` so that later selection has something to read.

## 8. Scope / phasing

1. **Define + assert (this RFC's core).** Land `SocketTimeoutException` as the uniform type in `translateRead`'s vocabulary; build the silent-peer harness (§6); write the failing `commonTest` matrix. Red across ~4 of 6 impls — that's the baseline.
2. **Fix the exception types (§4.1). ✅ LANDED 2026-07-12.** NIO2 `InterruptedByTimeoutException` → `SocketTimeoutException` (explicit case in `wrapJvmException`); Node/Apple `TimeoutCancellationException` → `SocketTimeoutException` (wrapped at the read boundary); the JVM **selector** path (found non-conformant in §6.1) routed through `aSelect`'s existing `SocketTimeoutException` by swallowing its own `TimeoutCancellationException`. Axis 3 now uniform on NIO2 / selector / Node (verified) + Apple (CI). Blocking path still can't satisfy it (never throws — Phase 3).
3. **Fix enforcement (JVM blocking). ✅ LANDED.** Implemented as the orphaned-read single-flight (§3.2) in `BaseClientSocket.blockingReadRaw`, not a deprecation of the pure-blocking path. Because the technique orphans the read rather than cancelling it, the blocking path turned **both** Axis 1 (enforcement) *and* Axis 2 (non-destructive, read + write halves) green at once — all five JVM-blocking-variant assertions now pass.
4. **Fix destructiveness (JVM NIO2 default + Apple).** The hardest slice — the orphaned-read / non-destructive receive work. Turns Axis 2 green. Requires the §3.2 buffer-lifetime discipline.
   - **4a — JVM NIO2. ✅ LANDED.** `AsyncBaseClientSocket.orphanedReadRaw` mirrors the Phase-3 blocking single-flight: the `aRead` runs on a socket-scoped coroutine with `Duration.INFINITE` (mapped to the JDK's untimed read in `aRead`, so `readKilled` is never armed), and the caller `withTimeout`s only the wait. A timed-out read is orphaned and re-awaited by the next `read()`; the pooled buffer is freed only on read completion/EOF. All five NIO2-default assertions green.
   - **4b — Apple. Remaining.** Remove `closeInternal()` from `NWSocketWrapper.readRaw`'s receive-cancellation path for the timeout case (see §9 — likely the orphaned-coroutine pattern with the native `nw_connection_receive` left outstanding). Validated on the macOS CI lane; needs a Mac to author + verify.
5. **(Optional) VT strategy.** Only after 1–4. Adds `IoConcurrency` + the allocation seam (§7).

## 9. Open questions

- **Non-destructive on Apple** — can `nw_connection_receive` be abandoned without cancelling the connection, the way io_uring cancels a single SQE? If Network.framework has no per-receive cancel, the Apple fix may require the orphaned-coroutine pattern with the native receive left outstanding (and its completion buffer lifetime managed as in §3.2). Needs a Mac to validate.
- **Should the pure-blocking JVM path survive?** If VT (selector-dispatched or orphaned-read) covers the "blocking mental model" need conformantly, the non-enforcing pure-blocking path (`useNioBlocking`) may be better deleted than fixed. It is currently test-only.
- **Write-timeout symmetry.** This RFC is scoped to reads. Writes have the same `WritePolicy.Bounded` / deadline shape; a follow-up should audit whether write timeouts are equally divergent and extend the contract.
