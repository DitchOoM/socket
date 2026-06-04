# HTTP/3 (`:socket-http3`) — handoff

Working state for continuing the HTTP/3 client (issue #86) on another machine.
Branch: **`feat/http3-codec`** (this branch is canonical — see "Branch reconcile" below).

## Where we are

`feat/http3-codec` was **rebased onto `origin/main`** (Gradle 9 / AGP 9 / Kotlin 2.3.21 /
**buffer 5.3.1** / liburing 2.14) and the HTTP/3 code was **extracted into a new
`:socket-http3` Gradle module**. All of it is green on **jvmTest + jsNodeTest**, ktlint clean.

Done (the "codec half" of #86 + bootstrap):

- **Codecs** (`com.ditchoom.socket.http3`): `VarIntCodec` (RFC 9000 §16), `Http3Frame` +
  `Http3FrameCodec` (RFC 9114 §7.1, hand-rolled — see "Why hand-rolled"), QPACK static-table
  (`QpackPrefixedInteger`, `QpackStaticTable`, `QpackFieldSectionCodec`, `QpackHuffman`).
- **`Http3StreamReader`** — frame reassembly over a `ByteStream` (`nextFrame` / `nextVarInt`).
- **`Http3Connection.bootstrap(scope, options)`** — opens the client control + QPACK
  encoder/decoder uni streams, writes the control type-prefix + SETTINGS, and runs a router
  over `QuicScope.streams()` that reads each peer uni stream's type prefix and dispatches it.
  Peer SETTINGS surface via `suspend fun peerSettings(): Http3Settings` (a typed view).
- **`openUniStream()`** on all real platforms (quiche JVM/Android/Linux + Apple), in `:socket-quic`.

## Branch reconcile (decided)

`origin/feature/socket-http3` is a **parallel, barely-started restart** of the same work (only
QUIC varint + a frame codec, on newest main, in a `:socket-http3` module). Our content is a
strict superset, so the decision was: **this branch absorbs its structure, not the reverse.**
We took its two valuable pieces — the newer main base (via the rebase) and the `:socket-http3`
module idea — and kept our far richer code. **`origin/feature/socket-http3` is now subsumed and
can be deleted** (left intact; delete is the user's call). Naming differs there — they used
`QuicVarInt` / `Http3SettingsPair` vs our `VarIntCodec` / `Http3Setting`; ours stayed.

## Deviations from `origin/feature/socket-http3`'s module shell (intentional)

- **Targets: `jvm + js`**, not their `jvm + linuxX64`. This preserves the jvmTest+jsNodeTest
  coverage our codecs already had and runs on macOS without a Linux cross-build. **TODO:** expand
  to the full `:socket-quic` matrix (linuxX64, Apple, Android, wasmJs) + maven-publish config.
- **KSP-for-main deferred.** Their shell wires the buffer-codec KSP processor onto the
  common-metadata compilation (for declarative `@ProtocolMessage` codecs). Our code is 100%
  hand-rolled, so KSP would generate nothing while adding build-failure surface. The exact
  wiring to copy when the first declarative codec lands is in
  `git show origin/feature/socket-http3:socket-http3/build.gradle.kts` (the `kspCommonMainMetadata`
  block + the `.editorconfig` that disables ktlint on generated sources).

## Why the frame codec stays hand-rolled (buffer 5.3.x finding)

buffer **v5.3.0** added `@ForwardCompatible` (skip-and-preserve unknown sealed variants) — which
solves the *throw-on-unknown* reason we originally hand-rolled. **But** the generated dispatcher
reads the discriminator as a **fixed-width scalar** (`readUByte`/`readUShort`…), and HTTP/3 frame
types are **QUIC varints** (1/2/4/8 bytes; e.g. the first GREASE type `64` = `0x40 0x40`). So a
declarative `Http3FrameCodec` is not feasible yet. The varint *length* would be fine via
`@FramedBy` + a VarInt `BoundingLengthCodec` (like `MqttRemainingLengthCodec`); only the type
**discriminator** is the blocker. Our codecs already do forward-compat correctly anyway
(`Http3Frame.Unknown` preserves unknown types; unknown SETTINGS ids / stream types are ignored).
A possible upstream follow-up: add varint-discriminator support to `buffer-codec`'s dispatcher.

## ✅ DONE since this handoff was written (on the Linux machine)

- **Half-close primitive** (`:socket-quic`, commit `04edc8c`): `HalfCloseableByteStream`
  interface + `QuicByteStream.shutdownSend()` / `QuicheStreamByteStream.shutdownSend()` —
  sends the send-side FIN (`adapter.streamClose` → quiche `stream_send(fin=true)`) without
  flipping `closed`, so `read()` keeps working; `write()` is rejected after; `close()` skips a
  duplicate FIN. Apple `NWQuicByteStream` parity (shared `sendFin()`). Regression test in
  `QuicServerTestSuite.halfCloseAllowsReadAfterSendFin` (in-process server, JVM + linuxX64).
- **Step 4 request/response** (`:socket-http3`, commit `2530f60`): `Http3Request`,
  `Http3Response` (status/headers + streaming body via `nextBodyChunk` / `readFullBody` +
  `trailers`), and `Http3Connection.request()` (open bidi → HEADERS [+ DATA] → `shutdownSend()`
  → read response; does NOT gate on `peerSettings()`). Scripted tests via the extended
  `FakeQuicScope` (now serves a bidi stream from `openStream`).
- **Step 5 public wrapper + live interop** (`:socket-http3`, commit `0c6e52d`):
  `withHttp3Connection(host, port=443, …)` = `withQuicConnection` + `bootstrap` (mirrors
  `withQuicMux`). `Http3PublicEndpointInteropTests` does a real `GET /` to cloudflare-quic.com +
  www.google.com (skip-on-unreachable; `:status` asserted *outside* the catch). **Both returned
  `status=200` locally** — the whole client works end-to-end against production `h3` (QUIC + real
  cert + SETTINGS + QPACK static-table HEADERS + Huffman-decoded response headers + half-close).
  Build wiring: `:socket-quic`'s staged quiche natives are put on `:socket-http3`'s jvmTest
  classpath (FFM loads `libquiche.so`); depends on `stageQuicheNativeResources`. **115 jvm + js
  green.**

  **The minimal HTTP/3 GET client (issue #86) is now functionally complete and interop-proven.**

## ✅ DONE — hardening + reach (this session, commits 8639e15 / 5590885 / 75dcc76)

- **Typed GOAWAY / MAX_PUSH_ID / CANCEL_PUSH frames** (were `Unknown`) + codec round-trips;
  **GOAWAY handled** on the control stream and surfaced via `Http3Connection.goAway:
  StateFlow<Long?>` (lowest last-stream-id; MAX_PUSH_ID is a no-op — client never pushes).
- **Frame variants are `@JvmInline value class`** now (single-field ones), matching the
  codebase convention (`DatagramReceiveResult`/`QuicStreamId`); `Unknown` stays a data class.
- **Trailers** surfaced via `Http3Response.trailers` (scripted test).
- **Streaming request body**: `request(method, …) { write(chunk) }` overload (Http3RequestBody
  sink → one DATA frame per write); shares `openRequestStream`/`readResponse` with the buffered
  overload.
- **linuxX64 target added.** Full common suite (119 tests: frame/QPACK/reader/connection/request)
  now runs on **jvm + js + linuxX64** — deterministic cross-platform validation of the H3 logic.
  The live interop test stays in commonTest; its linuxX64 binary whole-archives `libquiche.a`
  (mirrors `:socket-quic`'s `binaries.all { linkerOpts }`).
- **`openUniStream` on Linux K/N — DONE (commit `a5f2a36`).** `LinuxQuicConnection` had only
  `openStream` (bidi), inheriting the throwing default for uni; mirrored `JvmQuicConnection`
  (`open(unidirectional)` via the shared driver's `QuicheCmd.OpenStream` flag). **Result: the live
  interop GET now returns `status=200` on linuxX64 too — H3 works live on JVM + linuxX64.**

## ✅ DONE — in-process H3 loopback (step 1) + server uni-streams

- **In-process HTTP/3 loopback test** (`:socket-http3`): a real `withHttp3Connection` client talks
  to a hand-rolled `Http3LoopbackServer` over a real `withQuicServer`/`withQuicConnection` QUIC
  connection on `localhost` — the deterministic end-to-end harness the handoff asked for. Proves
  GET (status + headers + body), POST request-body echo, and `peerSettings()` resolution from the
  server's control stream. **Green on JVM + linuxX64** (`Http3LoopbackTestSuite` abstract base in
  commonTest + `JvmHttp3LoopbackTest`/`LinuxHttp3LoopbackTest` subclasses, exactly mirroring
  `:socket-quic`'s `QuicServerTestSuite` platform-parameterization; JS gets no subclass and runs
  nothing). Cert/key fixtures copied to `socket-http3/src/jvmTest/resources/certs/` (classpath) and
  `socket-http3/testcerts/` (native file path).
- **`Http3LoopbackServer`** (commonTest): the **first HTTP/3 server-role** implementation — opens
  the server control uni stream + writes `0x00` prefix + SETTINGS (mirrors the client's
  `writeControlStreamHeader`), drains the client's uni streams, reads each client bidi request
  stream's HEADERS (+ DATA) via `Http3StreamReader`/`QpackFieldSectionCodec`, and writes a canned
  HEADERS(+DATA) response then `shutdownSend()`. Seeds the future real server role.
- **Server-side `openUniStream()` (`:socket-quic`, PRODUCTION fix).** The server-accepted
  `QuicScope` (`DriverQuicConnection` on commonJvm, the server scope in `WithQuicServer.linux.kt`)
  overrode only `openStream()` and inherited the throwing default for `openUniStream()` — so the H3
  server couldn't open its control stream. Added `openUniStream()` (refactored to a shared
  `open(unidirectional)` helper, mirroring `JvmQuicConnection`); the driver already allocated
  server-initiated uni stream-ids (`nextUniStreamId = if (isServer) 3L`), so this was a one-line
  wiring gap. Covered now by the loopback test on JVM + linuxX64.
- **Gotcha learned:** QUIC stream I/O is zero-copy (`buffer.nativeMemoryAccess!!.nativeAddress`), so
  on Kotlin/Native both client and server frame/body buffers MUST come from a native-memory-backed
  factory — pass `ConnectionOptions(bufferFactory = BufferFactory.deterministic())`. `Default` is
  heap on K/N and NPEs in `DriverStreamAdapter.streamWrite`. (The live interop test already did this;
  now documented.)

## Testing strategy (decided)

Deterministic multiplatform = the **scripted commonTest suite** (jvm+js+linuxX64) for logic +
a planned **in-process H3 loopback** (`withQuicServer` ↔ `withHttp3Connection`, our codecs both
ends) for deterministic end-to-end — **NOT docker / Apple-containers** (docker = JVM/Linux-only +
infra; Apple containers are a dead end per TODO.md). The live interop GET stays for real-world drift.

Sequencing decided with the user: **loopback first** (it's the deterministic harness + seeds the
server role), **then** conformance gaps as test-first increments — *correctness* (H3 error-code
taxonomy + frame/stream validation enforcement) before *features* (dynamic QPACK, server push,
full server). The current client is a *conformant minimal* GET/POST client (QPACK capacity-0 +
no-push are spec-permitted), interop-proven — the gaps are additive, not instability.

## NEXT (start here)

1. ✅ **In-process H3 loopback test — DONE** (see "DONE — in-process H3 loopback" above). Green on
   JVM + linuxX64; `Http3LoopbackServer` seeds the server role; server `openUniStream()` landed.
2. **Conformance gaps, test-first on the loopback:** H3 error-code taxonomy (RFC 9114 §8.1) +
   frame/stream-validation enforcement (correctness) → then dynamic QPACK (RFC 9204), server push,
   full server role.
3. **Expand to Apple / Android / wasmJs + maven-publish** (need their hosts/CI).
4. **WebTransport** (Phase 2): RFC 9220 Extended CONNECT (gate on `peerSettings().enableConnectProtocol`)
   + RFC 9297 HTTP Datagrams + Capsule, over the existing QUIC datagram + stream plumbing.

## (historical) step 4b/5 plan — now DONE, see above

Settled design forks (from the user):

- **Do NOT gate on `peerSettings()`** before sending — a plain request depends on no peer setting;
  WebTransport's Extended CONNECT (Phase 2) does its own `enableConnectProtocol` check.
- **Streaming core + eager helper** for the response body: `request()` returns an `Http3Response`
  with `status` + headers (decoded from the first HEADERS frame) and a body you consume
  frame-by-frame via `Http3StreamReader`, plus a `readFullBody()` convenience.

Sketch:

```
data class Http3Request(method, scheme="https", authority, path,
                        headers: List<QpackHeaderField> = emptyList(), body: ReadBuffer? = null)
// pseudo-headers (:method/:scheme/:authority/:path) MUST precede regular headers (RFC 9114 §4.3.1)

class Http3Response(val status: Int, val headers: List<QpackHeaderField>, ...streaming body...)
suspend fun Http3Connection.request(req): Http3Response
// open client bidi stream → write HEADERS (QpackFieldSectionCodec) [+ DATA] → FIN →
// read response: first frame HEADERS (decode QPACK, parse :status), then DATA frames, optional trailers.
// Decode HEADERS with DecodeContext.Empty.with(QpackScratchPoolKey, pool).
```

### ✅ Blocker RESOLVED: half-close (send FIN, keep reading) — see "DONE" above (commit 04edc8c)

HTTP/3 finishes the **send** side of the request stream (FIN) while keeping the **read** side
open for the response. Today there is **no half-close primitive**: `QuicByteStream.close()` sends
the QUIC FIN *and* sets `closed=true`, after which `read()` throws (`check(!closed)`), at both the
`QuicByteStream` wrapper and `QuicheStreamByteStream`. quiche's `streamClose` already maps to
`stream_send(fin=true)` (a send-side FIN), so the fix is a wrapper/adapter seam, not a protocol
change. Options (pick in step 4):
- add `QuicByteStream.shutdownSend()` / a `streamWrite(..., fin=true)` variant that FINs without
  flipping `closed` (note precedent: commit `50814b6` already coalesces a FIN with the last data
  chunk on the read path), and
- the scripted-stream unit tests for request/response don't need this (they use `FakeQuicScope`);
  only the **live interop GET** does.

### Testing approach (settled)

- Routable logic → scripted-stream unit tests (extend `Http3ConnectionTests`' `FakeQuicScope` /
  `RecordingByteStream`; `QuicByteStream`'s constructor is now public so tests construct streams).
- Live H3 GET → a gated interop test à la `:socket-quic`'s `QuicPublicEndpointInteropTests`
  (skip-on-unreachable, never flaky-fail) against Cloudflare/Google `h3`. Cheaper precursor: #85
  `hq-interop`. Static-table-only QPACK (capacity 0).

## Build / test

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.9+10/Contents/Home  # JDK 21; system JDK 26 crashes the build
./gradlew :socket-http3:jvmTest :socket-http3:jsNodeTest :socket-http3:ktlintCheck
./gradlew :socket-http3:ktlintFormat   # auto-fix
```

## Open follow-ups

1. Half-close primitive in `:socket-quic` (blocks live request/response).
2. Step 4 request/response + scripted tests + gated interop GET.
3. Step 5: public `withHttp3Connection { … }` (wraps `withQuicConnection` + `bootstrap`).
4. Expand `:socket-http3` targets to the full matrix + publishing.
5. Wire KSP-for-main if/when a declarative codec is introduced.
6. Delete `origin/feature/socket-http3` once confirmed subsumed.
