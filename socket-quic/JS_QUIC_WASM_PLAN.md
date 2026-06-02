# JS QUIC via WebAssembly — design note / feasibility assessment

Status: **deferred — not implemented.** Today `withQuicConnection` / `withQuicServer` on
the JS and wasmJs targets throw `UnsupportedOperationException` (see
`src/jsMain/.../WithQuicConnection.js.kt`), and `JsQuicGapTests` positively assert that
contract. This note records the viable path, **what's actually blocked**, and why — so the
next attempt starts from evidence rather than optimism.

> **2026-06 update — the headline changed.** An earlier draft of this note assumed the
> `quiche → wasm` build was "the hard part but doable," with the real risks being zero-copy
> and WASM memory growth. A sourced feasibility pass found the opposite: **the zero-copy
> model, `node:dgram`, entropy, time, and memory-growth handling are all the easy/solved
> parts. The wall is BoringSSL** — specifically, getting quiche's TLS handshake (which lives
> in BoringSSL's `libssl`) to compile and link to wasm, which **has no public precedent.**
> The sections below are rewritten around that finding. See **Why quiche → wasm is blocked**.

## TL;DR

- A **zero-copy** JS QUIC client on **Kotlin/JS + Node** is architecturally sound: the
  platform-neutral `QuicheDriver`, the `JsBuffer(Int8Array)` zero-copy primitive, and
  UDP-in-JS via `node:dgram` all work and need no rethink.
- The blocker is the **QUIC core that must run in wasm.** The plan assumed that core would be
  **quiche** (Rust + BoringSSL). That specific build is a **multi-month, research-grade effort
  with a real chance of never converging**, because quiche is hard-coupled to BoringSSL, the
  Rust BoringSSL bindings have no wasm support, and nobody has ever compiled BoringSSL's
  **`libssl` QUIC TLS API** (the part quiche's handshake needs) to wasm.
- If/when this is revisited, the path with **working precedent** is a **pure-Rust, sans-I/O
  QUIC core — `quinn-proto` + `rustls`** — compiled to wasm, driven from JS with the existing
  buffer/`dgram` design. That is a different abstraction on the JS side (not the quiche C API),
  so it is a deliberate pivot, not a continuation of the quiche route.

## Why it's zero-copy (the key insight — still valid)

`QuicheApi` (commonMain) passes all data as native addresses (`Long`) — "no byte array copies
anywhere." Each platform impl (JNI, FFM, K/N cinterop) supplies a real pointer. JS has no
native heap and no FFI, so the question was whether a JS buffer can yield a pointer the wasm
QUIC core can use. It can — if the core runs as wasm:

```kotlin
// QUIC core compiled to wasm, instantiated under Node:
val ptr  = wasm.exports.malloc(len)                         // allocate INSIDE wasm linear memory
val view = Int8Array(wasm.exports.memory.buffer, ptr, len)  // zero-copy view over that region
val buf  = JsBuffer(view)                                    // public ctor: wraps any Int8Array
buf.nativeMemoryAccess!!.nativeAddress  // == ptr  (JsBuffer.nativeAddress == buffer.byteOffset)
```

`JsBuffer.nativeAddress` returns the ArrayBuffer `byteOffset`; for a view into the wasm
module's linear memory that offset **is** the in-wasm pointer. So the `Long`-address contract
lines up and wasm and the wrapped buffer read/write the same bytes — no copy. (`JsBuffer(Int8Array)`
is public in the buffer lib; only `BufferFactory.wrap` is restricted to a Kotlin `ByteArray`,
so either use the ctor or add a small `wrap(Int8Array)` factory.) This insight is independent
of *which* QUIC library is the wasm core.

The routes that DON'T work zero-copy (unchanged from the original analysis):
- **koffi/N-API → native libquiche**: real C pointers, but JS data must be copied into
  native-allocated memory. (Previously explored and deferred; see `feature/socket-quic-js-wip`.)
- **Kotlin/Wasm's own `LinearBuffer`** (`wasmJsMain`) bridged to a *separate* wasm core: two
  distinct linear memories → copies.

## Why quiche → wasm is blocked (the finding)

1. **quiche is hard-coupled to BoringSSL; there is no pure-Rust TLS backend.** quiche's TLS
   options are all C: `boringssl-vendored` (default), `boringssl-boring-crate`, or `openssl`
   (the last with no 0-RTT). A rustls backend was explicitly **rejected by Cloudflare
   maintainers** — "supporting both would mean that the rustls port would always be
   second-class to the BoringSSL one" ([quiche#129][q129], 2019) — and re-requested and
   **closed without action** ([quiche#1116][q1116], 2021). It cannot be swapped via a feature
   flag. (quiche uses `ring` for packet protection, but the **handshake**'s BoringSSL
   dependency is the load-bearing one.)

2. **The Rust BoringSSL bindings have no wasm support.** `boring` / `boring-sys` issue
   ["Support wasm"][boring288] is **open, unstarted, zero comments** (2024-10). quiche's
   vendored build compiles BoringSSL through CMake/cargo with target assumptions that don't
   hold on wasm; reaching wasm means **forking that build pipeline** to a wasi-sdk toolchain
   and re-wiring `boring-sys` — work nobody has published.

3. **The one proven BoringSSL→wasm artifact is `libcrypto`-only — not the part quiche needs.**
   [jedisct1/boringssl-wasm][bwasm] compiles **libcrypto** to `wasm32-wasi` with assembly
   disabled (portable C via the `OPENSSL_NO_ASM` path) and an optional `OPENSSL_SMALL` mode.
   But quiche's handshake uses BoringSSL's **dedicated QUIC TLS API**
   (`SSL_set_quic_method` / `SSL_provide_quic_data` …), which lives in **`libssl`**. Compiling
   and linking *that* to wasm is **unproven territory** — it is the make-or-break unknown.

4. **No public example of quiche (or any BoringSSL QUIC stack) running in wasm exists.** A 2025
   Rust-forum request to compile quiche to wasm for WebTransport went **unanswered with no
   working result** ([forum][forum]). Every working "QUIC in wasm under Node" is a **pure-Rust**
   (`quinn` + `rustls`) or pure-Go stack.

The blockers the *original* plan worried about are, by contrast, genuinely easy:
- **Entropy** — `wasm32-wasi` → `__wasi_random_get`; `wasm32-unknown-unknown` → getrandom's
  `js`/`wasm_js` backend (works under Node).
- **Time** — the public QUIC API expects the app to drive timers (`connTimeout`/`onTimeout`
  from JS); where `std::time` is still touched internally, `web_time` fills the gap (the
  quinn-wasm approach), or `wasm32-wasi` provides clocks.
- **UDP / sockets** — a non-issue by design: UDP stays in JS (`node:dgram`); only the
  protocol/crypto state machine runs in wasm, fed buffers over the API. This is the documented
  quic-go / quinn-wasm pattern.
- **WASM memory growth** — solvable with the pre-size-and-cap / re-wrap-after-growth invariant
  described below.

## Target choice (unchanged)

- **Kotlin/JS on Node** (`jsMain` + `jsNodeTest`), loading the wasm core via
  `WebAssembly.instantiate`. UDP is `node:dgram`. Localhost reaches a host-launched
  `quic-echo` harness, same as the other platforms.
- **Not** the `wasmJs` (Kotlin/Wasm) target: browser-oriented, its `LinearBuffer` is bound to
  Kotlin/Wasm's own memory (not the QUIC core's), and browsers have no raw UDP (WebTransport is
  a separate API). Browser QUIC stays out of scope.

## If revisited: the viable path (pure-Rust core)

The recommended revisit is **not** "try harder on quiche → wasm." It is a pure-Rust,
**sans-I/O** QUIC core:

- **`quinn-proto` + `rustls`** (with `ring` or `aws-lc-rs`). `quinn-proto` is sans-I/O by
  design — "drive the state machine, feed it datagrams" — which is exactly the shape the
  existing `QuicheDriver` already implements, and it has **pluggable TLS**, so rustls drops in.
- Precedent: [Frando/quinn-wasm][quinnwasm] runs **quinn + rustls** on `wasm32-unknown-unknown`
  under Node (getrandom `js`, `web_time` for clocks, UDP relayed from JS). PoC-grade, but it
  proves the stack links and runs in the target environment.
- Reuse from this repo: the zero-copy `JsBuffer` model, a `node:dgram` `UdpChannel` /
  `UdpChannelFactory`, a wasm-heap `BufferFactory`, and the WASM-memory-growth invariant.
- The cost: the JS side would bind a **quinn-proto-shaped API**, not the quiche C API
  (`QuicheApi`). So `QuicheApi`/`QuicheDriver` are **not** reused verbatim on JS — JS would get
  its own thin driver over quinn-proto's endpoint/connection API. This is a deliberate
  architectural fork for the JS target only; the other platforms keep quiche unchanged.
- Avoid: `quic-go` (Go wasm runtime + GC weight, though it works under `wasip1`) and `msquic`
  (C/C++ with the same asm/build problems as BoringSSL, no wasm story).

### WASM memory-growth invariant (applies to either core)

When the wasm core grows its memory, `exports.memory.buffer` is **replaced** and every
`Int8Array` view (and wrapped `JsBuffer`, including its cached `DataView`) **detaches**.
Strategy: re-wrap views after any call that may grow memory, **or** pre-size the wasm heap and
cap it so growth can't happen mid-connection. Needs a deliberate invariant + tests. This is a
real footgun but a tractable one — not the reason the quiche route is blocked.

## What stays as-is until then

- `WithQuicConnection.js.kt` / `WithQuicServer.js.kt` keep throwing
  `UnsupportedOperationException` (a cleanly catchable signal, deliberately not `Error`).
- `JsQuicGapTests` keep positively asserting that contract on `jsNode` in CI — **not** silent
  skips. If/when a JS QUIC core lands, those tests fail by design, forcing real loopback /
  migration coverage to replace them.

## Sources

- quiche rejects rustls (maintainer, 2019): [quiche#129][q129]
- quiche rustls request closed (2021): [quiche#1116][q1116]
- `boring` "Support wasm" — open, unstarted (2024-10): [boring#288][boring288]
- BoringSSL libcrypto-only wasm32-wasi port (asm disabled): [jedisct1/boringssl-wasm][bwasm]
- Why quiche needs BoringSSL's QUIC TLS API + `ring` for packet protection: [Cloudflare blog][cfblog]
- Unanswered quiche→wasm request (2025-03): [Rust users forum][forum]
- Working pure-Rust QUIC in wasm (quinn + rustls): [Frando/quinn-wasm][quinnwasm]
- quic-go wasm pattern (state machine in wasm, UDP outside): [quic-go docs][quicgo]
- getrandom wasm support: [docs.rs/getrandom][getrandom]

[q129]: https://github.com/cloudflare/quiche/issues/129#issuecomment-524035205
[q1116]: https://github.com/cloudflare/quiche/issues/1116
[boring288]: https://github.com/cloudflare/boring/issues/288
[bwasm]: https://github.com/jedisct1/boringssl-wasm
[cfblog]: https://blog.cloudflare.com/enjoy-a-slice-of-quic-and-rust/
[forum]: https://users.rust-lang.org/t/how-to-compile-quiche-to-wasm-to-use-as-a-local-http-3-server-for-webtransport/126852
[quinnwasm]: https://github.com/Frando/quinn-wasm
[quicgo]: https://quic-go.net/docs/quic/wasm/
[getrandom]: https://docs.rs/getrandom
</content>
</invoke>
