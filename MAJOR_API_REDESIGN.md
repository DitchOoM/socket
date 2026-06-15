# Major API Redesign (v6) — Design Doc

**Status:** Draft / RFC · **Branch:** `redesign/major-api-v6` · **Target:** next major (`com.ditchoom:socket` 6.0, `com.ditchoom:buffer` 6.0)

This is a breaking, coherence-first redesign. The goal is a clean end state, not source compatibility.

---

## 1. Goals

1. **Make timeout footguns structurally impossible** — no magic-number deadline can be silently inherited onto a persistent stream.
2. **One composable shape, reused everywhere** — the same source/sink/duplex trichotomy at the byte and message layers, injected and overridable like `BufferFactory`.
3. **Honest, swappable module structure** — separate *protocol* × *engine* × *platform*, so "Apple + quiche" and "browser WebTransport" are expressible.
4. **Preserve the one real moat: end-to-end zero-copy** — native-memory buffers flow from wire to app without a copy, including across the C/FFI boundary.
5. **First-class QUIC / HTTP3 / WebTransport / WebSocket** as composable transports.

## Non-goals

- Not an application framework. No routing, content negotiation, auth, serialization plugins, HTTP/1.1, or DI container. This is the transport substrate you build those on.
- Not source-compatible with 5.x. Legacy interfaces are deleted, not deprecated-in-place.

---

## 2. Positioning: how this differs from Ktor, and where it wins

The module topology below (a core API module + swappable engine modules) is deliberately the **Ktor engine model** — it's the proven shape for backend-swap in KMP. But this library sits at a **different altitude** than Ktor and has a narrow, deep advantage Ktor does not.

### Where this library wins

| Capability | This library | Ktor (3.x) |
|---|---|---|
| **End-to-end zero-copy** | Native-memory buffers (`BufferFactory.deterministic()`) whose addresses are handed *directly* to quiche / Network.framework / OpenSSL. `conn_stream_recv` decrypts straight into a buffer the app then owns — no copy from wire-decrypt to application. | I/O via kotlinx-io `Source`/`Sink` (segment-pooled, JVM-heap-backed). Copies at the segment and FFI boundaries. |
| **QUIC / HTTP3 / WebTransport** | First-class, multiplatform (jvm/android/apple/linux/node), with pluggable native engine. | No QUIC, no HTTP/3, no WebTransport. |
| **Low-level wire control, uniformly** | Congestion control (Reno/Cubic/BBR2), pacing, datagrams, per-stream `RESET_STREAM`/`STOP_SENDING` with app error codes, connection migration, keepalive PING — same `QuicOptions` across every platform. | Abstracts the wire behind channels; exposes none of these. |
| **No-impossible-states typing** | Tightest type per direction (`ByteSource`/`ByteSink`, `Sender`/`Receiver`) — a send-only stream cannot expose `read()`. | `ByteReadChannel`/`ByteWriteChannel` are separate but transports surface them ad hoc. |
| **Altitude** | Transport/protocol substrate; bring your own framing via `Codec`. | Full application framework. |

### Where Ktor wins (stated honestly)

- Vast ecosystem: plugins, auth, content negotiation, serialization, routing, mature HTTP/1.1+2 client/server.
- Maturity and adoption.

### The niche this defines

**The high-throughput, low-GC, QUIC-era transport that KMP doesn't otherwise have.** If you need WebTransport or HTTP/3 in Kotlin Multiplatform today, Ktor has no answer. If you're moving real-time media, game traffic, IoT/MQTT, or large payloads and GC pressure / copy overhead matters, the zero-copy native-memory path is a measurable win. You could build a Ktor-like framework *on top* of this; you would not replace this *with* Ktor.

> **Summary answer to "is this any different than Ktor?"** The *module shape* borrows Ktor's engine-swap pattern. The *library* is a lower-altitude, zero-copy transport with first-class multiplatform QUIC/HTTP3/WebTransport and direct-but-portable wire control — none of which Ktor provides. The advantage is narrow and deep, not broad.

---

## 2b. Ecosystem & integration

### buffer-codec — the headline use case
Write a `Codec<T>` **once** (declaratively, with buffer-codec `@DispatchOn`/`@FramedBy`/`@ForwardCompatible`), and `CodecConnection` runs it **zero-copy over any transport** (TCP/QUIC/WebTransport/WebSocket) on **every KMP platform**. A wire protocol — MQTT, or any custom binary protocol — is defined once and runs zero-copy everywhere. There is no ecosystem equivalent: Ktor's serialization is application-layer object mapping (kotlinx.serialization), not zero-copy *wire* codecs at the transport layer. This is the flagship story: **declarative protocol → zero-copy on all platforms over any transport.**

### Ktor engine adapter (inverted integration)
Don't import Ktor plugins — they're bound to Ktor's pipeline. Do the reverse: implement a Ktor **`HttpClientEngine`** backed by this library's QUIC/HTTP3 transport (a `ktor-client-ditchoom` module). Ktor's entire plugin ecosystem (ContentNegotiation, Auth, Logging, retries…) then runs over **HTTP/3 — which Ktor cannot do today.** Boundary cost: the engine converts to Ktor's `ByteReadChannel` (a copy), so the Ktor path trades zero-copy for ecosystem + H3; the native API stays zero-copy. Optional module, after the core lands.

### Selective design wins to adopt from Ktor
- A **lightweight composable interceptor/middleware** over `Connection` (`install`-style), building on the existing `Http3RequestFilter` seed.
- A **typed config DSL** layered over the data-class options.
- The **engine abstraction** (already adopted, §7).
- *Not* adopted: the heavy pipeline, full plugin framework, routing — that's framework altitude and would dilute the substrate.

### kotlinx-io — intentionally no interop
Core stays pure zero-copy `ReadBuffer`; no kotlinx-io adapter module ships (Risk 5, resolved). Adapting to kotlinx-io's segment/heap `Source`/`Sink` would force a boundary copy and dilute the moat. Consumers who need a bridge write their own and accept that copy.

---

## 3. The core insight: three orthogonal axes

Today every source set conflates three independent concerns, which is why the structure feels confusing and why "Apple + quiche" is impossible:

| Axis | Values | Today's problem |
|---|---|---|
| **Protocol** | TCP, QUIC, WebTransport, WebSocket | — |
| **Engine** | NIO2, Network.framework, quiche (JNI/FFM/koffi), browser-native | The apple source set *is* Network.framework; no seam to swap |
| **Platform** | jvm, android, apple, linux, browser, node | browser throws for everything instead of exposing WebTransport/WebSocket |

KMP forces `actual` into the same module as `expect`, so an engine can never be a separable, opt-in dependency *while it lives in a source set*. The fix is to express the engine as an **injected SPI interface** (not `expect/actual`), with a platform-appropriate default — exactly how `BufferFactory` is injected with a platform default. This is the Ktor `HttpClient(CIO)` vs `HttpClient(Darwin)` pattern, applied to the QUIC engine.

---

## 4. Module graph

```
socket-core            Consolidated types + TCP transport. No native engine dep.
                       • ByteSource / ByteSink / ByteStream  (raw bytes)
                       • Sender / Receiver / Connection       (typed messages)
                       • StreamMux, ReadPolicy / WritePolicy
                       • unified options tree, capability model
                       • TCP transport on every socket-capable platform

socket-quic            QUIC API (QuicScope, streams) + QuicEngine SPI.
                       Depends only on socket-core. No native lib.
 ├─ socket-quic-quiche   QuicheEngine   → jvm (JNI/FFM), android, linux, node (koffi)   [heavy native, app-size cost]
 ├─ socket-quic-nw       NetworkEngine  → apple (system Network.framework)              [zero app-size]
 └─ socket-quic-default  convenience bundle: provides defaultQuicEngine per target      [opt-in; apple→NW, others→quiche]

socket-http3           HTTP/3 + WebTransport over :socket-quic (any engine).
                       Native/JVM client + server.

socket-webtransport    Top-level WebTransport transport (expect/actual).
                       • native/jvm → delegates to socket-http3
                       • browser    → wraps native `new WebTransport(url)`  (NO quiche/h3 shipped to browser)

socket-websocket       WebSocket as a Connection / ByteStream.
                       • native → framing over socket-core TCP+TLS
                       • browser → wraps native `WebSocket`
```

Module count goes 3 → ~7. Each module now answers exactly one question (which protocol / which engine / which platform). That separation is the only structure under which the platform concerns below are expressible at all.

---

## 5. Resolving the platform concerns

### Browser = WebTransport (+ WebSocket) only
`socket-core` and `socket-quic` have **no browser target** — raw TCP/QUIC aren't on the classpath in a browser build, so there are no `UnsupportedOperationException` stubs. The browser source sets of `socket-webtransport` and `socket-websocket` provide *real* implementations backed by the native browser objects. Capability honesty by construction.

### Apple can opt into quiche
Default Apple build pulls `socket-quic-nw` (Network.framework, no size cost). An Apple consumer who wants quiche adds `socket-quic-quiche` and passes `QuicheEngine` to the connect call — same `QuicScope` API, they accept the app-size increase. The engine is injected like `bufferFactory`/`readPolicy`:

```kotlin
// default on Apple → Network.framework
withQuicConnection(host, port, options) { /* ... */ }

// Apple opt-in to quiche (requires socket-quic-quiche dependency)
withQuicConnection(host, port, options.copy(engine = QuicheEngine)) { /* ... */ }
```

### WebSocket is a first-class transport
A `Connection<T>` / `ByteStream` producer like TCP and QUIC — native over TCP+TLS framing, browser over the native `WebSocket`. Codecs compose over it identically.

### Capability model — type-gated, library-wide

Platform/engine divergence is surfaced through **one** mechanism: type-gated capabilities you query and exhaustively `when` over from common code. This replaces scattered `UnsupportedOperationException` stubs — an unavailable capability is a type you *can't reach*, not a call that throws.

**Two layers:**

*Coarse — which transports exist at all:*
```kotlin
data class NetworkCapabilities(val transports: Set<TransportKind>)
enum class TransportKind { TCP, QUIC, WEB_TRANSPORT, WEB_SOCKET }
expect fun networkCapabilities(): NetworkCapabilities
// browser → { WEB_TRANSPORT, WEB_SOCKET };  jvm/linux/apple → all four
```

*Fine — what advanced features a present transport/engine supports, as sealed providers:*
```kotlin
sealed interface WebTransportSupport {
    suspend fun connect(url: String, opts: WebTransportOptions): WebTransportSession   // every platform
    interface Multiplexed : WebTransportSupport {                                       // native-only variant
        suspend fun connectMultiplexed(url: String): MultiplexedConnection             // many sessions/requests over one held H3 conn
    }
}
expect fun webTransportSupport(): WebTransportSupport

// common code — base API always works; advanced control where the type is present:
val wt = webTransportSupport()
val session = wt.connect(url, opts)
if (wt is WebTransportSupport.Multiplexed) wt.connectMultiplexed(url)   // smart-cast, type-safe, max control
```

The advanced interfaces are **declared in `commonMain`** (so common code can name and branch on them) with actuals only where supported — negligible cost in unsupported builds (type metadata only; the heavy machinery stays in native source sets/engines). The same sealed-provider pattern is the general tool for every divergent feature: **WebTransport reuse, QUIC datagrams, connection migration, 0-RTT, engine-specific capabilities.** Principle: *an unsupported capability is unreachable by type, never a runtime stub.*

---

## 6. Consolidated type system

### Byte layer (raw bytes) — the deadline policy lives here

```kotlin
sealed interface ReadPolicy {
    data class Bounded(val deadline: Duration) : ReadPolicy   // request/response
    data object UntilClosed : ReadPolicy                       // persistent; liveness = transport idle-timeout
    fun toDeadline(): Duration = when (this) { is Bounded -> deadline; UntilClosed -> Duration.INFINITE }
}
sealed interface WritePolicy { /* Bounded(d) | UntilClosed, mirrors ReadPolicy */ }

interface ByteSource {
    val isOpen: Boolean
    val readPolicy: ReadPolicy                                // injected, role-appropriate (a val CAN be overridden per impl)
    suspend fun read(deadline: Duration): ReadResult          // the only abstract read
    suspend fun read(): ReadResult = read(readPolicy.toDeadline())   // consults policy — no inherited-default footgun
}

interface ByteSink {
    val isOpen: Boolean
    val writePolicy: WritePolicy
    suspend fun write(buffer: ReadBuffer, deadline: Duration): BytesWritten
    suspend fun write(buffer: ReadBuffer): BytesWritten = write(buffer, writePolicy.toDeadline())
    suspend fun writeGathered(buffers: List<ReadBuffer>, deadline: Duration): BytesWritten
    suspend fun writeGathered(buffers: List<ReadBuffer>): BytesWritten = writeGathered(buffers, writePolicy.toDeadline())
}

interface ByteStream : ByteSource, ByteSink { suspend fun close() }

// orthogonal capability mixins — kept separate (no fake capabilities)
interface HalfCloseable : ByteStream { suspend fun shutdownSend() }
interface Resettable    : ByteStream { suspend fun reset(errorCode: Long) }
```

**Why the footgun is gone:** there is no defaulted *parameter value* to inherit. The no-arg `read()` is a non-abstract method that consults the injected `readPolicy` **val** — and a `val` *can* be overridden per implementation (unlike a default parameter value, which Kotlin forbids overriding). `WebTransportStream` sets `override val readPolicy = UntilClosed`; an HTTP/3 request stream gets `Bounded(15s)`. Correct by construction.

### Typed message layer — already deadline-free, stays that way

```kotlin
fun interface Sender<in T>   { suspend fun send(message: T) }
fun interface Receiver<out T>{ fun receive(): Flow<T> }
interface Connection<T> : Sender<T>, Receiver<T> { val id: Long; suspend fun close() }
interface StreamMux<T> {
    suspend fun openBidirectional(): Connection<T>      // both
    suspend fun openUnidirectional(): Sender<T>         // send-only
    suspend fun acceptBidirectional(): Connection<T>
    suspend fun acceptUnidirectional(): Receiver<T>     // receive-only
}
```

The two trichotomies mirror exactly:

```
raw bytes:  ByteSource   ByteSink   ByteStream(=both)
typed msgs: Receiver<T>  Sender<T>  Connection<T>(=both)
```

### The adapter rule: propagate, don't clobber

Bridges that wrap a `ByteStream` (`CodecConnection`, the socket connection, `WebTransportMux`) must call the leaf's **no-arg** `read()`/`write()` so the leaf's policy propagates. They pass an explicit deadline *only* when a caller deliberately overrides. Today `CodecConnection.fillFromTransport()` injects `options.readTimeout`, which would defeat a WebTransport stream's `UntilClosed` policy — that is the bug this rule fixes. HTTP/3 request/response reader sites keep their explicit `Bounded` deadlines (legitimately bounded).

---

## 7. Engine SPI (pluggable QUIC backend)

```kotlin
// in :socket-quic — pure API, no native dep
interface QuicEngine {
    suspend fun connect(host: String, port: Int, options: QuicOptions, transport: TransportConfig): QuicConnection
    suspend fun bind(port: Int, host: String?, options: QuicOptions, tls: QuicTlsConfig): QuicServer
    val capabilities: EngineCapabilities   // e.g. supportsMigration, supportsDatagrams, maxStreams
}

// platform default, resolvable without depending on a specific engine module
expect val defaultQuicEngine: QuicEngine   // apple → NetworkEngine, others → QuicheEngine
```

Engines live in their own modules (`QuicheEngine` in `socket-quic-quiche`, `NetworkEngine` in `socket-quic-nw`). The `defaultQuicEngine` actual for each platform pulls its module's engine; a consumer overrides by passing an explicit engine. This is the Ktor `HttpClient(engine)` model at the QUIC layer.

---

## 8. Options tree (maximal consolidation)

One immutable, composable, injected-once tree. Folds in: the unified `TlsConfig` (replacing `TlsConfig` + `QuicTlsConfig`), the QUIC engine, the deadline policies, and `bufferFactory`. The global mutable `PlatformSocketConfig` singleton is **deleted**; its process knobs move here.

```kotlin
data class TransportConfig(
    val bufferFactory: BufferFactory = BufferFactory.Default,   // platform-aware default; auto-upgrades to network() for QUIC
    val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds), // TCP/request-response default; WT overrides to UntilClosed
    val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds),
    val connectTimeout: Duration = 15.seconds,
    val tls: TlsConfig? = null,
    val io: IoTuning = IoTuning(),                               // ex-PlatformSocketConfig knobs, now injected not global
)

data class QuicOptions(
    val alpnProtocols: List<String>,
    val engine: QuicEngine = defaultQuicEngine,                 // ← Apple quiche opt-in lives here
    val congestionControl: CongestionControl = CongestionControl.Cubic(),
    val pacing: Pacing = Pacing.Unlimited,
    val idleTimeout: Duration = 30.seconds,
    val keepAliveInterval: Duration? = null,
    val datagrams: Boolean = false,
    // ...
)
```

`TlsConfig`, `bufferFactory`, `readPolicy`, and `engine` are all the same composable shape: a sensible platform default, overridable by injection, validated at the entry point (e.g. QUIC's `requireNativeMemory()`).

---

## 9. WebTransport as a top-level transport

```kotlin
// commonMain (socket-webtransport)
expect suspend fun WebTransport.connect(url: String, options: WebTransportOptions): WebTransportSession
// jsMain    → new WebTransport(url); reads/writes bridge ReadableStream/WritableStream <-> ByteSource/ByteSink (boundary copy, localized by BufferFactory)
// jvm/native → Extended CONNECT over socket-http3 (existing path), connection reuse available
```

`WebTransportSession` stays (it adds datagrams + drain/close-with-code — not a generic `StreamMux`). Its streams become the consolidated types: bidi → `ByteStream` (already true post-#163), uni send → `ByteSink` + `Resettable`, uni receive → `ByteSource` + `Resettable` (cancel = reset of the read side). The bespoke `WebTransportSendStream`/`WebTransportReceiveStream` classes are deleted.

Browser note: the JS actual wraps the native object and does **not** pull `socket-quic`/`socket-http3` — the browser does HTTP/3 internally. The unavoidable `Uint8Array` ↔ native-buffer copy is localized at the JS boundary (the `BufferFactory` already abstracts allocation; the JS factory yields `Uint8Array`-backed buffers). Per CLAUDE.md, that boundary copy is the sanctioned exception.

---

## 10. Deletion / migration map

| Deleted (5.x) | Replaced by (6.0) |
|---|---|
| `Reader`, `Writer` (`com.ditchoom.data`) | `ByteSource`, `ByteSink`, `ByteStream` |
| `ClientSocket : Reader, Writer` + mutable `var bufferFactory` | `ClientSocket : ByteStream`; `bufferFactory` injected via `TransportConfig` |
| `SocketConnection`; `TcpByteStream` as an exception→ReadResult translator | `ClientSocket` *is* a `ByteStream` (no adapter) |
| `read(timeout: Duration = 15.seconds)` defaults everywhere | `ReadPolicy`/`WritePolicy` injected; no defaulted params |
| `TlsConfig` + `QuicTlsConfig` | single `TlsConfig` |
| `PlatformSocketConfig` (global mutable singleton) | `TransportConfig.io` (injected) |
| `enum NetworkCapabilities { FULL_SOCKET_ACCESS, WEBSOCKETS_ONLY }` | `NetworkCapabilities(Set<TransportKind>)` |
| `WebTransportSendStream`, `WebTransportReceiveStream` | `ByteSink` + `Resettable`, `ByteSource` + `Resettable` |
| QUIC uni stream modeled as bidirectional `QuicByteStream` (fake `read()`) | uni → `ByteSink` / `ByteSource` |
| QUIC engine hardcoded per apple source set | `QuicEngine` SPI + `socket-quic-quiche` / `socket-quic-nw` modules |

**Kept (earns its place):** `HalfCloseable` + `Resettable` (orthogonal capabilities), `ReadResult` (`Data`/`End`/`Reset`), `StreamMux` vs `WebTransportSession` (session adds datagrams + lifecycle), `Codec`/`CodecConnection`.

---

## 11. Cross-repo sequencing

The byte-layer types live in the **buffer** repo (`buffer-flow`, currently pinned `5.6.0`). The reshape is a buffer **major bump → 6.0** + re-pin here — the established train (worktree → `publishToMavenLocal` → re-pin `libs.versions.toml` → validate → release to Central → drop `mavenLocal()`).

**Phase 0 — buffer 6.0 (worktree, SNAPSHOT to mavenLocal):** add `ReadPolicy`/`WritePolicy` (Risk 1: `WritePolicy` mirrors reads, default `Bounded`); reshape `ByteStream` → `ByteSource`/`ByteSink`/`ByteStream` trichotomy with the policy-`val` + no-arg-overload shape (no defaulted params); sweep `buffer-flow` consumers. Pure `ReadBuffer`, no kotlinx-io (Risk 5).

**Phase 1 — socket-core:** re-pin buffer 6.0-SNAPSHOT; collapse `Reader`/`Writer` → byte trichotomy; `ClientSocket : ByteStream`; build the `TransportConfig` tree; delete `PlatformSocketConfig`, the binary `NetworkCapabilities`, `SocketConnection`. **Adapter rule** (propagate-not-clobber) applied to `CodecConnection` (+ the socket connection).

**Phase 2 — engine split:** extract `QuicEngine` SPI in `socket-quic` (no native dep, no default — Risk 2); move quiche backends → `socket-quic-quiche`; Network.framework → `socket-quic-nw`; add `socket-quic-default` bundle providing `defaultQuicEngine` per target. Validate Apple-default (NW) and Apple-opt-in (quiche). *(KMP spike: per-target default resolution inside the bundle.)*

**Phase 3 — http3 / webtransport:** WT streams → consolidated types; delete bespoke uni classes (→ `ByteSink`/`ByteSource` + `Resettable`); adapter rule on `WebTransportMux`. Introduce the **type-gated capability model** (Risk 3): `WebTransportSupport` sealed provider with native-only `Multiplexed`; promote per-session `connect(url)` to commonMain.

**Phase 4 — browser + websocket + capabilities:** `socket-webtransport` browser actual (native `WebTransport`); `socket-websocket` module (native + browser); generalize the sealed-provider capability model across divergent features (datagrams, migration, 0-RTT); per-platform `NetworkCapabilities` set.

**Phase 5 — release:** buffer 6.0 to Central; re-pin; drop `mavenLocal()`; write `docs/UPGRADING-6.0.md` (Risk 4: docs-only migration); socket 6.0. Optional follow-ups (post-core): `ktor-client-ditchoom` engine, the `install`-style interceptor model.

Each phase compiles + passes targeted tests before the next. The first prototype slice (to feel the ergonomics) is Phase 0→1 against a local buffer SNAPSHOT, exercised through `CodecConnection` and one WebTransport read.

---

## 12. Open questions / risks

1. **`WritePolicy` — RESOLVED:** mirror `ReadPolicy`. `ByteSink` carries a `writePolicy` val; no-arg `write()` consults it; explicit `write(buf, deadline)` overrides. `WritePolicy` defaults to `Bounded(15s)` *always* (infinite writes are rarely wanted), while `ReadPolicy`'s default is role-dependent. Kills the magic default on the write side too; symmetric mental model.
2. **Engine default — RESOLVED:** core `:socket-quic` has **no** default (zero coupling) — callers pass `engine`. A thin `:socket-quic-default` bundle depends on the right engine per target and provides `defaultQuicEngine` (apple→NW, others→quiche) for the common case. Ergonomics are opt-in; the core never references a backend. (One KMP spike remains: confirm the per-target default resolves cleanly inside the bundle.)
3. **Browser HTTP/3 reuse — RESOLVED (type-gated capability):** the base `WebTransportSupport.connect(url)` works everywhere; connection reuse is the native-only `WebTransportSupport.Multiplexed` sealed variant, *declared in commonMain* and reached via smart-cast. Common code branches exhaustively — max control where present, clean fallback where not. This is the first instance of the library-wide type-gated capability model (see §5); it generalizes to datagrams, migration, 0-RTT, and engine differences. Supersedes the earlier "absent from browser source set" framing.
4. **Migration cost — RESOLVED:** docs-only. A one-page `docs/UPGRADING-6.0.md` (old→new table, options→`TransportConfig`, engine-module-per-platform) plus the §10 deletion map. No compat shim, no codemod — clean hard break; consumers rewrite call sites.
5. **kotlinx-io alignment — RESOLVED:** stay pure. `ByteSource`/`ByteSink` are zero-copy `ReadBuffer` only; no kotlinx-io interop module ships. Maximal zero-copy purity (protects Goal 4) and no extra surface; consumers who need a kotlinx-io bridge write their own and accept the boundary copy.
