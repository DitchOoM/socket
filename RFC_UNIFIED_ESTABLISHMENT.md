# RFC — Unified connection establishment + transport-agnostic errors

**Status:** Proposed · **Date:** 2026-07-01 · **Branch:** `feat/composable-transports`
**Supersedes discussion in:** `HANDOFF_COMPOSABLE_API.md` · **Related issue:** #166

> This RFC is the design deliverable that must be agreed before the transport code lands. It
> reconciles the three establishment shapes on `main` into one model, decides how URL-addressed
> transports fit the host:port `Transport` SPI, and folds in the error-type unification (#166). It
> deliberately keeps a future WebSocket transport expressible **without building it**.

---

## 1. The problem, precisely

A library author (MQTT client, a custom binary protocol, an RPC layer) should write protocol logic
**once** against an abstract byte stream and let the *application* choose TCP / QUIC / WebTransport
underneath — with reconnection and error handling written once too. On `main` the foundation is
already here (`Transport`, `ByteStream`, `CodecConnection`, `ReconnectingConnection`,
`TransportConfig`), but three things block the promise:

1. **Only one transport implements the SPI.** `TcpTransport` exists; QUIC and WebTransport streams
   *are* `ByteStream`s but there is no `QuicTransport` / `WebTransportTransport`, so you cannot pass
   them to `CodecConnection.connect(...)`.

2. **Three inconsistent establishment shapes** are visible on the public surface:

   | Shape | Example | Lifetime | Addressing | Multiplicity |
   |---|---|---|---|---|
   | Stateless factory | `Transport.connect(host, port, config): ByteStream` (TCP) | the returned stream | host:port | 1 stream |
   | Scoped block | `withQuicConnection(host, port){ QuicScope.() }` | block boundary | host:port | N streams + datagrams |
   | Held object | `webTransportSupport().connect(url): WebTransportSession` | explicit `close()` | **URL** | N streams + datagrams |

   Building `QuicTransport` / `WebTransportTransport` naively *before* reconciling these would bake
   the inconsistency into the public API.

3. **Errors are not transport-agnostic.** TCP throws the `SocketException` sealed hierarchy; QUIC
   throws `QuicCloseException` (already a `SocketClosedException` — good); WebTransport throws
   `WebTransportException`, which extends `Exception` directly and is **not** catchable as
   `SocketException`. And at the platform boundary the *cause* is still a free-form
   `platformError: String` (issue #166), so the same condition surfaces differently per platform.

---

## 2. The core insight: two orthogonal axes

The three shapes look like three APIs but they vary along only **two independent axes**:

- **Axis A — multiplicity.** Does one *establishment* yield **one byte stream** (TCP) or a
  **session that multiplexes many** streams + datagrams (QUIC, WebTransport)?
- **Axis B — lifetime ergonomics.** Is the resource **scoped** (`withX { }`, closed at the block
  boundary) or **held** (returned object you `close()` yourself)?

The mistake is treating "TCP vs QUIC vs WebTransport" as the axis. It isn't. Once you separate the
axes, the unification is mechanical:

- **Axis B is sugar, not substance.** Scoped == held wrapped in `try/finally`. We pick *held* as the
  primitive and define *scoped* as a one-line extension over it. No transport needs both hand-written.
- **Axis A is a real capability difference**, so it gets **two SPIs, not one** — but they nest: a
  single-stream transport is just "a session, projected to one stream."

This yields the model in §3.

---

## 3. The model

### 3.1 Layer 1 — `Transport` (the transport-agnostic surface). **Unchanged.**

```kotlin
interface Transport {
    suspend fun connect(hostname: String, port: Int, config: TransportConfig): ByteStream
}
```

This is what a transport-agnostic protocol library binds to. It is the **single-stream projection**
of any transport:

- **TCP** — the connection *is* the `ByteStream` (`TcpTransport`, exists).
- **QUIC** — establish a connection, open **one** bidirectional stream, hand it back. Closing the
  stream tears the connection down (`QuicTransport`, this RFC).
- **WebTransport** — establish a session, open **one** bidirectional stream, hand it back. Closing
  the stream closes the session (`WebTransportTransport`, this RFC).

A library that only needs "a reliable, ordered byte pipe" (MQTT, most RPC framings) binds here and
**never learns which transport it got**. `CodecConnection` and `ReconnectingConnection` already ride
on this and need no changes.

> **Why keep the signature `(host, port, config)` instead of a new address type?** See §4 —
> URL-addressed transports carry their URL-specific coordinates (scheme, path) on the *transport
> instance*, so the call site stays uniform and existing TCP callers are untouched.

### 3.2 Layer 2 — `SessionTransport<S>` (the multiplexed surface). **New, additive.**

Power users who need multiple streams and/or datagrams over one connection use the richer session
handle. To make the *lifetime* consistent across QUIC and WebTransport, we name one convention:

```kotlin
/** A multiplexed transport: one establishment yields a held session that muxes many streams. */
interface SessionTransport<S : Any> {
    /** Establish and return a held session. Caller owns close(). */
    suspend fun establish(hostname: String, port: Int, config: TransportConfig): S
}

/** Scoped sugar over any held session that is a SuspendCloseable-shaped resource. */
suspend fun <S, R> SessionTransport<S>.use(
    hostname: String, port: Int, config: TransportConfig,
    close: suspend (S) -> Unit,
    block: suspend (S) -> R,
): R {
    val session = establish(hostname, port, config)
    return try { block(session) } finally { close(session) }
}
```

The session type `S` is transport-specific **on purpose** — `QuicScope` has datagrams + migration;
`WebTransportSession` has session close codes + uni/bidi typing. Forcing them into one interface would
either lie (stubs) or shrink to a uselessly-small common denominator. Capability differences stay
expressed *by type* (the v6 discipline: "an ability you have is a type you can `is`-check").

**What this means for the existing scoped APIs.** `withQuicConnection { }` and
`webTransportSupport().connect(url)` are already the two ends of Axis B:

- QUIC's **public** shape is *scoped* (`withQuicConnection`), with a *held* escape hatch
  (`QuicEngine.connect` → `QuicConnection`, which `QuicTransport` uses internally).
- WebTransport's **public** shape is *held* (`connect(url): WebTransportSession`), and callers add
  `try/finally` themselves.

The convention above simply **names** the duality and gives the held→scoped bridge one home. We do
**not** rewrite either public API in this RFC (that churn is not worth it and both already work); we
document them as the two instances of one convention, and any *new* session transport (a future WS
`Multiplexed`) supplies `establish()` and gets `use { }` for free.

### 3.3 How Layer 1 is built from Layer 2

`QuicTransport` / `WebTransportTransport` are the **single-stream projection** of their session:

```
Transport.connect(host, port, config)                       // Layer 1 (agnostic)
  = establish session (Layer 2)                             // held QUIC conn / WT session
  → open ONE bidirectional stream
  → return a ByteStream that owns the session:
        read()/write() delegate to the stream
        close()        closes the stream, THEN the session   // no leak on the error path
```

The projection is the only new concept, and it is tiny. It is what makes "one byte pipe, don't care
which transport" real.

### 3.4 Layer 2b — `MultiplexingTransport` (the agnostic **multiplex** surface)

The single-stream `Transport` is not the only agnostic surface, and it must **not** be positioned as a
lowest-common-denominator that hides multiplexing. A library that genuinely needs *many* concurrent
streams should also be able to write **once** and run over QUIC or WebTransport interchangeably — it
just can't include TCP (TCP has no multiplexing; that's physics, not an API gap).

buffer-flow already defines the neutral multiplex abstraction: **`StreamMux<T>`** — `openBidirectional()`/
`openUnidirectional()`/`acceptBidirectional()`/`acceptUnidirectional()` returning the tightest typed
`Connection<T>`/`Sender<T>`/`Receiver<T>` per direction. QUIC already adapts to it (`QuicStreamMux`);
WebTransport did **not**, which is the gap this RFC closes with `WebTransportStreamMux`.

The agnostic entry point mirrors `Transport`, one tier up:

```kotlin
interface MultiplexingTransport {
    suspend fun <T, R> withMux(host, port, codec, config, block: suspend StreamMux<T>.() -> R): R
}
```

- `QuicMultiplexingTransport(quicOptions)` and `WebTransportMultiplexingTransport(path)` implement it.
- **Scoped, not held** — `StreamMux` explicitly does not own the connection lifecycle (the transport
  scope does); `withMux { }` is the honest primitive (the connection lives for the block, all streams
  force-close on exit), exactly like the pre-existing `withQuicMux { }`.
- A library binds to `MultiplexingTransport` + `StreamMux<T>` and runs over QUIC **or** WebTransport
  with no transport-specific code.

**So there are two agnostic tiers, not one LCD:**

| Tier | Surface | Transports | For |
|---|---|---|---|
| Single stream | `Transport.connect(): ByteStream` | TCP · QUIC · WebTransport | one reliable ordered byte pipe (MQTT, most framed protocols) |
| Multiplexed | `MultiplexingTransport.withMux(): StreamMux<T>` | QUIC · WebTransport | many concurrent typed streams (+ uni streams) |

A library picks the tier that matches its needs; both are transport-neutral. Transport-specific *power*
beyond `StreamMux` (QUIC datagrams/migration, WebTransport session close codes) still lives on the
per-transport session APIs, reached by `is`-check — capability-by-type, never a stub.

---

## 4. Addressing: host:port vs URL (the interface question)

TCP and QUIC address by **host:port**. WebTransport addresses by **URL**
(`https://host:port/path`) — the path selects the WebTransport resource; a future WebSocket is the
same (`wss://host:port/path`). The base `Transport.connect(host, port, config)` has no path.

**Decision: keep `connect(host, port, config)`; carry URL-specific coordinates on the transport
instance.**

```kotlin
// host:port transports — address is fully the call args
val tcp  = TcpTransport()
val quic = QuicTransport(quicOptions = QuicOptions())

// URL transport — scheme is fixed (https), path lives on the instance
val wt   = WebTransportTransport(path = "/mqtt")            // or a full-URL secondary ctor

// uniform call site — the library only ever sees host:port
CodecConnection.connect("broker.example.com", 443, MyCodec, transport = wt)
//  → WebTransportTransport builds https://broker.example.com:443/mqtt internally
```

**Rationale.**
- A transport-agnostic library is *handed a pre-configured `Transport`* by the application and calls
  `connect(host, port)`. The application decides TCP vs QUIC vs WT and, for URL transports, bakes in
  the path. The library stays addressing-agnostic — exactly the goal.
- Existing `TcpTransport` callers and the `Transport` signature are untouched — zero churn.
- **WebSocket-compatible:** a later `WebSocketTransport(path = "/ws")` slots in identically.

**Alternatives considered and rejected.**
- *A sealed `Endpoint { HostPort | Url }` on the base interface.* Cleaner in theory but churns every
  existing call site and pushes URL-parsing concerns onto host:port transports. The path-on-instance
  form gets the same expressiveness with none of the churn; we can still add an `Endpoint` overload
  later without breaking anyone.
- *Two base methods (`connect(host,port)` + `connect(url)`).* Splits the agnostic surface in half —
  a library would have to know which to call, defeating the point.

A caller that genuinely holds a full URL and wants WebTransport specifically (not via the agnostic
surface) can bypass the transport abstraction entirely and call `webTransportSupport().connect(url)`
directly — that path already exists, so `WebTransportTransport` does not duplicate it with a full-URL
constructor.

---

## 5. Where reconnection and framing fit (unchanged, confirmed)

- **Framing** is the `Codec<T>`'s job, run by `CodecConnection<T>` over whatever `ByteStream` the
  `Transport` produced. Nothing transport-specific. A message-framed transport (WebSocket, later)
  fits by exposing a `ByteStream` whose `read()` returns message-aligned chunks, or by a
  message-level `Connection<T>` — both already expressible.
- **Reconnection** is `ReconnectingConnection<T>`, whose `connect: suspend () -> Connection<T>`
  closure calls `CodecConnection.connect(host, port, codec, transport)`. Because the transport is
  captured in the closure, **reconnection is transport-agnostic today** — a QUIC or WT transport
  drops in with no change. The only requirement this RFC adds: establishment failures must be
  classifiable, which §6 guarantees by mapping them into `SocketException` (the
  `DefaultReconnectionClassifier` already reasons over that hierarchy).

---

## 6. Error unification (issue #166)

Two distinct gaps, addressed at two altitudes.

### 6.1 One thrown vocabulary at the transport surface (this RFC implements)

**Principle: the `Transport` / `ByteStream` / `CodecConnection` surface throws exactly one error
family — the `SocketException` sealed hierarchy — regardless of TCP / QUIC / WT.** "Write once,
handle errors once" is impossible if catching `SocketException` misses WebTransport failures.

Current state:
- TCP → `SocketException` subtypes. ✅
- QUIC → `QuicCloseException : SocketClosedException` (carries structured `QuicError`). ✅
- WebTransport → `WebTransportException : Exception`. ❌ not under `SocketException`.

**Change:** at the two new transport boundaries, map native establishment/stream failures into
`SocketException`:
- `QuicTransport` — QUIC already throws `SocketClosedException`; establishment timeouts/handshake
  failures are mapped to `SocketConnectionException` / `SSLHandshakeFailedException` /
  `SocketTimeoutException` as appropriate.
- `WebTransportTransport` — wrap `WebTransportException` thrown during `connect()` into the matching
  `SocketException` (handshake/CONNECT failure → `SSLHandshakeFailedException` or
  `SocketConnectionException`; peer-lacks-WebTransport → `SocketConnectionException`). The **stream**
  read side already surfaces peer reset as buffer-flow `ReadResult.Reset`, which `CodecConnection`
  maps to `SocketClosedException.ConnectionReset` — so mid-stream aborts are already unified. The
  write-side `WebTransportStreamException` is wrapped to `SocketClosedException.ConnectionReset`
  (carrying the original as `cause`) when it escapes through the `ByteStream.write` path.

The rich per-transport exceptions (`QuicCloseException.quicError`, `WebTransportStreamException.errorCode`)
remain available to power users on the Layer-2 surface — unification narrows the *thrown type at the
agnostic boundary*, it does not erase structured detail (both are reachable as the wrapper's `cause`).

### 6.2 Exhaustive typed causes at the platform boundary (designed here, sequenced as follow-up)

Issue #166's deeper ask: replace `platformError: String?` on `SocketConnectionException.Refused`
(and siblings) with an **exhaustive sealed cause**, produced by *every* platform mapper
(JVM/Linux-errno/Apple-NW/Node), so the same condition is the same value cross-platform (and the 6
skipped Windows mapping tests become table entries, not string guesses).

**Proposed type** (mirrors `QuicError`, the in-repo gold standard):

```kotlin
sealed interface ConnectionFailureReason {
    val description: String
    data object Refused : ConnectionFailureReason
    data object HostUnreachable : ConnectionFailureReason
    data object NetworkUnreachable : ConnectionFailureReason
    data object TlsBadCertificate : ConnectionFailureReason
    data object TlsHandshake : ConnectionFailureReason
    data object OutOfMemory : ConnectionFailureReason
    data object Timeout : ConnectionFailureReason
    data class Unknown(val raw: String) : ConnectionFailureReason   // raw kept as diagnostic, never switched on
}
```

Wired onto the establishment exceptions as a `val reason: ConnectionFailureReason`, with the raw
platform string demoted to non-discriminating `message`/`cause` detail.

**Decision (2026-07-01 sign-off): do 6.2 in this PR as well ("both now").** It touches every
platform's error-mapping code (`JvmExceptionMapping`, the Linux errno map, the Apple NW/`Sec` map,
the Node error-code map). The Apple and Windows lanes **cannot be validated from this Linux box** —
those are implemented faithfully and must be run on a macOS/Windows runner (mirrors the standing
`V6_MAC_VALIDATION.md` convention). JVM/Linux-native/JS lanes are validated locally. The transports
also satisfy 6.1 (one thrown family) so the two efforts compose.

---

## 7. WebSocket-compatibility check (not built)

The model must not need rework when `socket-websocket` eventually lands. Verifying against WS's
shape (URL-addressed, held-lifetime, message-framed, browser-native + native-over-TCP/TLS):

- **Addressing** — `WebSocketTransport(path = "/ws")` fits §4 identically. ✅
- **Multiplicity** — WS is single-stream → it implements Layer-1 `Transport` directly (like TCP), no
  session projection needed. ✅
- **Framing** — WS frames are messages; a `ByteStream` view returns message-aligned chunks, or a
  `Connection<WebSocketMessage>` sits directly on Layer 2. Both already expressible; `CodecConnection`
  unaffected. ✅
- **Lifetime** — held; if a future multiplexed WS wanted scoped sugar it gets `use { }` free (§3.2). ✅
- **Errors** — map WS close/handshake failures into `SocketException` per §6.1. ✅

No part of the model assumes a fixed transport set. WS is expressible; we are simply not building it.

---

## 8. Scope of the implementation that follows this RFC

**In this PR (after sign-off):**
1. `QuicTransport : Transport` in `socket-quic-default` (wraps `defaultQuicEngine.connect` + one
   stream; error mapping per §6.1) + tests.
2. `WebTransportTransport : Transport` in `socket-webtransport` (wraps
   `webTransportSupport().connect(url)` + one bidi stream; URL per §4; error mapping per §6.1) + tests.
3. The single-stream **projection** wrapper (session-owning `ByteStream`) — one small class per
   transport (or one shared helper), whichever reads cleaner.
4. `SessionTransport` + `use { }` convention (§3.2) — additive, small.

**Explicitly NOT in this PR:**
- The `socket-websocket` module / `WebSocketTransport` (parked; kept expressible per §7).
- Any rewrite of `withQuicConnection` / `WebTransportSession` public surfaces (§3.2 — documented as
  convention instances, not changed).

**Added to this PR by sign-off:** the §6.2 platform-mapper sweep (`ConnectionFailureReason` on every
mapper). Apple/Windows lanes are compile-faithful only and need runner validation.

**Known parked follow-up (carried from #189, not this work):** the capsule-`Length` DoS cap in
`runCapsuleLoop` — a protocol decision, unchanged here.

---

## 9. Sign-off resolutions (2026-07-01)

1. **Addressing (§4)** — ✅ path-on-instance, keep `connect(host, port, config)`. Implemented.
2. **`SessionTransport` (§3.2)** — ✅ landed now (`SessionTransport<S>` + `use { }`). Implemented.
3. **Error unification depth (§6)** — ✅ **both now**. 6.1 (unified thrown family) *and* 6.2
   (`ConnectionFailureReason` across all four platform mappers) are in this PR. Apple + Windows lanes
   are compile-faithful only and need a macOS/Windows runner to validate at runtime.

## 10. Implementation status (this branch)

Delivered on `feat/composable-transports`:

- **`ConnectionFailureReason`** (sealed) + **`ConnectionFailure`** interface — socket-core.
  `SocketConnectionException` (incl. new `Other(reason)`), `SocketUnknownHostException`,
  `SocketTimeoutException`, `SSLSocketException` subtypes now carry `reason`. All four central mappers
  (`wrapJvmException`, `mapErrnoToException`, `mapSocketException`, `wrapNodeError`) enriched
  (SSL bad-cert vs handshake; `ENOMEM`/`OutOfMemoryError` → `OutOfMemory`).
- **`SessionTransport<S>`** + `use { }` + **`SessionOwningByteStream`** (single-stream projection) +
  **`MultiplexingTransport`** (agnostic multiplex surface, §3.4) — socket-core.
- **`QuicTransport`** + **`QuicSessionTransport`** + **`QuicMultiplexingTransport`** — socket-quic-default.
- **`WebTransportTransport`** + **`WebTransportSessionTransport`** + **`WebTransportStreamMux`**
  (the missing `StreamMux` adapter) + **`WebTransportMultiplexingTransport`** — socket-webtransport
  (added `api(project(":"))` so it can implement the socket-core `Transport`/`MultiplexingTransport` SPIs).
- Tests: `ConnectionFailureReasonTest` (common), `JvmExceptionReasonTests` (jvm), `QuicTransportTest`
  (jvm), `WebTransportTransportTest` (common). All green on JVM; common/native/JS/wasmJs compile clean.

Validated locally: JVM (compile + all new tests), Linux-native (compile, incl. test), JS + wasmJs
(compile). **Not validated here** (needs runners): Apple targets (compile + run) and the JVM-on-Windows
mapper behavior for the 6 still-skipped tests — those skips are behavioral (NIO2 timing/shape), which
the typed-reason work does not itself change.
