# JS QUIC via quiche-wasm — design note / spike plan

Status: **not implemented.** Today `withQuicConnection` / `withQuicServer` on the JS
and wasmJs targets throw `UnsupportedOperationException` (see
`src/jsMain/.../WithQuicConnection.js.kt`). This note records the viable path and
what's actually hard, so the next attempt starts from facts rather than the thin
stub comment (which points at a `socket_quic_js_koffi_deferred.md` memory that no
longer exists).

## TL;DR

A **zero-copy** JS QUIC client is feasible on **Kotlin/JS running on Node**, by
compiling **quiche → WebAssembly** and driving it through the existing
`QuicheApi` / `QuicheDriver` abstraction. The buffer library already has the
primitive that makes it zero-copy. The hard parts are the quiche.wasm build, a
`JsQuicheApi`, and WASM memory-growth handling — **not** the buffer model.

This supersedes the earlier "deferred on zero-copy grounds" conclusion, which
applied to the **koffi → native libquiche** route (which does force copies), not
to the quiche-wasm route.

## Why it's zero-copy (the key insight)

`QuicheApi` (commonMain) passes all data as native addresses (`Long`) — "no byte
array copies anywhere." Each platform impl (JNI, FFM, K/N cinterop) supplies a real
pointer. JS has no native heap and no FFI, so the question was whether a JS buffer
can yield a pointer quiche can use. It can — if quiche runs as wasm:

```kotlin
// quiche compiled to wasm, instantiated under Node:
val ptr  = quicheWasm.exports.malloc(len)                         // allocate INSIDE quiche.wasm memory
val view = Int8Array(quicheWasm.exports.memory.buffer, ptr, len)  // zero-copy view over that region
val buf  = JsBuffer(view)                                         // public ctor: wraps any Int8Array
buf.nativeMemoryAccess!!.nativeAddress  // == ptr  (JsBuffer.nativeAddress == buffer.byteOffset)
```

`JsBuffer.nativeAddress` returns the ArrayBuffer `byteOffset`; for a view into
quiche.wasm's linear memory that offset **is** the quiche pointer. So the
`QuicheApi` `Long` contract lines up and quiche.wasm and the wrapped buffer read/write
the same bytes — no copy. (`JsBuffer(Int8Array)` is public; only `BufferFactory.wrap`
is restricted to a Kotlin `ByteArray`, so either use the ctor or add a small
`wrap(Int8Array)` factory in the buffer lib.)

Contrast — the routes that DON'T work zero-copy:
- **koffi/N-API → native libquiche**: real C pointers, but JS data must be copied
  into native-allocated memory. (The deferred approach.)
- **Kotlin/Wasm's own `LinearBuffer`** (`wasmJsMain`, tied to `wasmExports.memory`)
  bridged to a *separate* quiche.wasm: two distinct linear memories → copies.

## Target choice

- **Use Kotlin/JS on Node** (`jsMain` + `jsNodeTest`), loading quiche.wasm via
  `WebAssembly.instantiate`. UDP is `node:dgram`. Localhost reaches a host-launched
  `quic-echo` harness, same as the other platforms.
- **Not** the `wasmJs` (Kotlin/Wasm) target: it's browser-oriented and its
  `LinearBuffer` is bound to Kotlin/Wasm's own memory, not quiche's. Browsers also
  have no raw UDP (WebTransport is a different API), so browser QUIC stays out of scope.

## Work breakdown

1. **Build quiche → wasm.** Emscripten or `clang --target=wasm32`, exporting
   `malloc`/`free` + the quiche C API used by `QuicheApi`. Decide standalone-wasm vs
   Emscripten JS glue. Ship the `.wasm` as a jsMain resource; load + instantiate once.
2. **`JsQuicheApi : QuicheApi`** (jsMain). Implement each method as a call into
   quiche.wasm's exported functions, passing the `Long` offsets. The existing
   commonMain `QuicheDriver` drives it unchanged — this is the bulk of the work but
   mechanical (mirror `CinteropQuicheApi` / `FfmQuicheApi`).
3. **`withQuicConnection.js` / `withQuicServer.js`** actuals: wire a `node:dgram`
   UDP socket to the driver's recv/send, replacing the `UnsupportedOperationException`
   stubs. (Server can come later; client first.)
4. **WASM memory-growth handling — the real footgun.** When quiche.wasm grows its
   memory, `exports.memory.buffer` is *replaced* and every `Int8Array` view (and
   wrapped `JsBuffer`) detaches. Strategy: re-wrap views after any call that may grow
   memory, or pre-size the wasm heap and cap it. Needs a deliberate invariant +
   tests.
5. **Pooled-buffer integration.** To stay zero-copy end-to-end, the driver's pooled
   recv/send buffers must live in quiche.wasm memory (wrap-on-allocate via a
   jsMain BufferFactory that mallocs in the wasm heap), not the JS GC heap. recv:
   read UDP datagram into wasm memory, wrap, feed quiche. send: quiche writes into
   wasm memory, wrap, hand to dgram.
6. **No-ByteArray discipline.** Per CLAUDE.md, annotate any genuine boundary copy
   (e.g. dgram <-> wasm if a datagram can't land directly in wasm memory) with
   `@Suppress("NoByteArrayInProd")` + a one-line reason.

## Tests that light up once the client works

The cross-platform suites in `commonTest` are written against the `QuicScope` /
`StreamMux` API and already run elsewhere — they should apply to `jsNodeTest`
with the existing skip-on-unavailable patterns:
- `QuicPublicEndpointInteropTests` (no harness needed — hits cloudflare/google).
- `QuicHarnessIntegrationTests` (needs the `quic-echo` peer reachable on localhost).
- `QuicIntegrationTests`, `QuicStreamMuxCommonTests`.
Replace the wasmJs/js **gap contract tests** (which assert `UnsupportedOperationException`)
with real functional runs for the implemented surface; keep gap tests for the
genuinely-unsupported bits (browser, server-if-deferred).

## Open questions to resolve in the spike

- Standalone-wasm vs Emscripten glue (affects how memory + exports are surfaced).
- Can a UDP datagram be received directly into wasm memory via dgram, or is one
  copy at that edge unavoidable? (If unavoidable, it's a single boundary copy, not
  the per-stream copies the koffi route implied.)
- Memory-growth frequency under load — pre-size to avoid mid-connection growth?
- Server path (`NWListener`-equivalent) — defer to a follow-up; client first.
