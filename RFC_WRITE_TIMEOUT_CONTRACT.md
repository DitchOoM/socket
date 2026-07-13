# RFC: `write(deadline)` timeout contract

Status: **Phase 1 + Phase 2 landed** — harness, red baseline, and all-platform fixes.
JVM (NIO2 / blocking / selector), Node, and Linux verified green locally; Apple hand-authored,
validated on `build-apple` CI only. Companion to `RFC_READ_TIMEOUT_CONTRACT.md`. Branch:
`rfc/write-timeout-contract`.

## 1. Motivation — writes are *not* symmetric to reads

The read contract (`RFC_READ_TIMEOUT_CONTRACT.md`) settled on: deadline **enforced**,
timeout **non-destructive** (this-read-only, connection stays usable), uniform
`SocketTimeoutException`. A `ReadPolicy.Bounded(d)` that elapses is a recoverable
"no data yet" — the caller retries, muxes, or falls back on a *live* connection.

Writes are different, and the difference drives an asymmetric contract:

- A stalled **write** is **back-pressure** — connection-global TCP flow control. The
  peer's receive window is closed; the kernel send buffer is full. This is a *normal*
  condition, and the correct response is to **suspend the writer** until space frees up,
  not to fail. Killing a usable connection because the peer is momentarily slow is wrong.
- But back-pressure is *connection-global*: there is a single send buffer, so if bytes
  won't drain, **no** logical stream on the connection can send. There is nothing
  per-stream to preserve (unlike a read, where one stream timing out leaves the others
  and the connection healthy).

So the write contract is:

## 2. The contract

1. **Default = suspend / back-pressure.** The shipped default is
   `WritePolicy.UntilClosed` (infinite deadline). A no-arg `write()` to a peer that
   isn't draining **suspends** until the bytes are accepted or the connection actually
   breaks (RST/EOF/error). A blocked writer is flow control, not an error. *(This changes
   the previous `WritePolicy.Bounded(15s)` default — see §7 back-compat.)*

2. **`Bounded(d)` is opt-in and enforced.** A caller that explicitly wants an upper bound
   passes `WritePolicy.Bounded(d)` (or `write(buf, d)`). The write throws within `~d`
   rather than suspending indefinitely.

3. **`Bounded(d)` timeout is uniform `SocketTimeoutException`** (never a bare kotlinx
   `TimeoutCancellationException`, an errno-string exception, an `IllegalState`, or a
   hang). It carries `TimeoutContext.Write(d)`.

4. **`Bounded(d)` timeout is DESTRUCTIVE (auto-close).** This is the deliberate asymmetry
   with reads. When an opt-in bounded write blows its deadline, the send buffer is wedged
   and the connection's write capacity is gone; the connection is **closed**. A subsequent
   write throws `SocketClosedException`. Only the opt-in bounded path is destructive — the
   default (suspend) never kills the connection.

Rationale for "destructive only on the opt-in bounded path": it is cheap and uniform
across platforms (no orphaned-write single-flight / partial-byte accounting is needed —
the one place we deliberately kill the connection is exactly the place where killing it is
acceptable), while the common path stays non-destructive by simply suspending.

## 3. Axes (what the conformance tests assert)

Against the in-process `NonDrainingPeer` (accept-then-never-read) fixture:

| # | Axis | Assertion |
|---|------|-----------|
| §1 | default suspends | `UntilClosed` write to a non-draining peer parks (does not throw) |
| §2 | bounded enforced | `Bounded(d)` write throws within `~d` |
| §3 | uniform type | the throw is `SocketTimeoutException` |
| §4 | bounded destructive | after the timeout `isOpen == false`; next write throws `SocketClosedException` |

## 4. Baseline → after-fix (2026-07-12)

Red baseline from `WriteTimeoutContractTests` (+ `JvmWriteTimeoutVariantTests`), then the same
matrix after Phase 2. Apple is `build-apple`-CI-only.

| impl | §1 suspend | §2 enforced | §3 STE type | §4 destructive-close |
|------|:---------:|:-----------:|:-----------:|:--------------------:|
| JVM NIO2 (default) | ✅ | ✅ | ✅ | ❌→✅ |
| JVM NIO blocking | ✅ | ❌→✅ (was hang) | ❌→✅ | ❌→✅ |
| JVM NIO selector | ✅ | ✅ | ✅ | ❌→✅ |
| Node | ❌→✅ (was fire-and-forget) | ❌→✅ | ❌→✅ | ❌→✅ |
| Linux io_uring | ✅ | ✅ | ✅ | ❌→✅ |
| Apple NWConnection | CI | CI | CI | CI |

Baseline takeaways (all now fixed):
- **§4 (destructive auto-close) was red everywhere** — no impl auto-closed on a bounded
  write-timeout. The biggest cross-cutting fix: close at the `write()` boundary when a *finite*
  deadline elapses (only a finite deadline can time out; an infinite one suspends).
- **JVM blocking** dropped the deadline entirely; now runs the write on a background scope that the
  caller `withTimeout`s, closing the channel to unblock the parked syscall on timeout.
- **Node** was fire-and-forget; now awaits the write's flush callback so a peer that isn't draining
  suspends the writer (§1) and a bounded deadline enforces + closes (§2–§4).

### Node harness limitation

Node's `net.Socket` enters *flowing* mode the moment a `'data'` listener is attached — which our
`ServerSocket` does on accept — so its OS receive buffer is always drained into an unbounded channel
and our own `ServerSocket` can never be a non-draining peer. The common `WriteTimeoutContractTests`
therefore **skip Node** (`nonDrainingPeerIsReliable()` = `false` on JS/Wasm). The Node write path is
proven instead by `NodeWriteBackpressureTests` (jsTest), which uses a **raw** `net` server whose
accepted sockets get no `'data'` listener — they stay paused, so the client genuinely back-pressures.

## 5. Implementation (Phase 2, landed)

- **Auto-close on bounded write-timeout (all impls).** Centralized at the `write()` boundary:
  when a bounded deadline elapses, close the connection, then throw
  `SocketTimeoutException(TimeoutContext.Write(d))`.
- **JVM NIO2**: keep timed `aWrite` for `Bounded`; map its `InterruptedByTimeoutException`
  to `TimeoutContext.Write` + auto-close. For the `UntilClosed`/infinite default, use the
  JDK *untimed* write overload (mirror the read path's `aRead` INFINITE handling).
- **JVM blocking**: run the blocking write on a socket-scoped coroutine; caller
  `withTimeout(d)`; on timeout close the channel (unblocks the syscall) + throw STE(Write).
- **JVM selector**: `Selector.select` already throws on `OP_WRITE` starvation — retype to
  `TimeoutContext.Write` + auto-close.
- **Node**: `await 'drain'` under `withTimeout(d)` when `socket.write` returns `false`;
  infinite deadline → await with no bound (true back-pressure); on timeout close + STE(Write).
- **Linux**: keep the io_uring linked-timeout enforcement; retype the errno-string STE to
  `TimeoutContext.Write` + auto-close.
- **Apple**: `nw_helper_send_tcp` under `withTimeout(d)`; rewrap the leaked TCE to
  STE(Write); the existing `invokeOnCancellation { closeInternal() }` already closes — make
  it honest/uniform (fires only on the bounded-timeout path). Authored hand-indented,
  validated on macOS CI only.

## 6. Test harness

`NonDrainingPeer` (commonTest/harness) — the write-side mirror of `SilentPeer`: an
in-process `ServerSocket` that accepts a connection and **never reads it**, so the client's
writes back-pressure. `writeOutcome(deadline, watchdog)` drives sustained chunked writes on a
detached scope and classifies the result as `Threw` / `WatchdogExpired` (suspended on
back-pressure) / `CompletedWithoutBackpressure` (acknowledged never-read bytes) without ever
hanging the suite. No Docker/netem — runs on every platform with `FULL_SOCKET_ACCESS`.

## 7. Back-compat note

Changing the default `WritePolicy` from `Bounded(15s)` to `UntilClosed` is source-compatible
but **behavior-changing**: a no-arg `write()` no longer times out after 15s — it suspends
until sent or the connection breaks (standard blocking-write flow control). Callers that want
a hard bound must set `WritePolicy.Bounded(d)` explicitly. Flag the version bump accordingly.
