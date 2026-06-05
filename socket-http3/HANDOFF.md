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

## ✅ DONE — step 2: RFC 9114 §8 error handling (taxonomy + enforcement + wire codes)

Full-depth error handling, test-first on the loopback, across `:socket-http3` + `:socket-quic`:

- **Error-code taxonomy** (`Http3ErrorCode`): RFC 9114 §8.1 (`0x0100`–`0x0110`) + RFC 9204 §8.3
  QPACK (`0x0200`–`0x0202`). `Http3StreamException` carries an `errorCode: Long` (default
  `GENERAL_PROTOCOL_ERROR`, so old `assertFailsWith`/`catch` sites are unaffected).
- **Client-side validation enforcement**: `readResponse` (first frame must be HEADERS; DATA/SETTINGS
  before it → FRAME_UNEXPECTED; stream end → REQUEST_INCOMPLETE; unknown ignored),
  `Http3Response.nextBodyChunk` (only DATA + trailing HEADERS; else FRAME_UNEXPECTED), control stream
  (`handleControl`/`readControlFrames`: first frame SETTINGS else MISSING_SETTINGS /
  CLOSED_CRITICAL_STREAM; duplicate SETTINGS or DATA/HEADERS → FRAME_UNEXPECTED). Connection-level
  violations route through `abortConnection`, surfaced via `connectionError` / `awaitConnectionError()`.
- **CONNECTION_CLOSE with the H3 code** (`:socket-quic` PRODUCTION): `QuicScope.closeWithError(code)`
  (default throws, like `openUniStream`); one default override on internal `QuicConnection` delegates
  to `close(QuicError.ApplicationError(code))` → `quiche_conn_close(app=true)`. Covers every platform.
  `abortConnection` calls it so connection-level violations reach the peer with the RFC code.
- **RESET_STREAM / STOP_SENDING with the H3 code** (`:socket-quic` PRODUCTION): plumbed
  `quiche_conn_stream_shutdown` end-to-end — C JNI shim (`nConnStreamShutdown`) + `QuicheApi`
  .connStreamShutdown across FFM (jvm21, the **active JDK 21 path**), JNI (commonJvm), cinterop (linux);
  `QuicheCmd.StreamShutdown` + driver handlers; `QuicheStreamAdapter.streamShutdown`; new
  `ResettableByteStream` (`reset()` = RESET_STREAM + STOP_SENDING) on `QuicByteStream` /
  `QuicheStreamByteStream`. **No prebuilt-binary change** — the symbol is already exported by
  `libquiche{,_jni}` (whole-archived `libquiche.a`); CI/local rebuild the shim from source.
  H3 wiring: `Http3Response.cancel()` → REQUEST_CANCELLED; a malformed response *message*
  (missing/non-numeric `:status`) → MESSAGE_ERROR resets just the stream (§4.1.2), while an invalid
  frame *sequence* stays a connection error (§4.1).
- **End-to-end malformed-peer loopback tests** (jvm + linuxX64): a server sending DATA-before-HEADERS
  makes the client abort the connection with FRAME_UNEXPECTED; a server omitting `:status` on one
  request makes the client reset just that stream (MESSAGE_ERROR) while a follow-up request on the same
  connection still returns 200 — proving the stream-vs-connection scope distinction on the wire.

**Apple note:** `NWQuicByteStream` doesn't implement `ResettableByteStream` yet, so `reset()` on Apple
falls back to a graceful `close()` (no RESET_STREAM code on the wire); JVM + linuxX64 send the real
code. Follow-up: an NW reset + exposing the *peer's* application close code on the server `QuicScope`
(so a loopback test can assert the server observed the code, not just the client).

## ✅ DONE — step 3: dynamic QPACK (RFC 9204), full bidirectional

Scope chosen with the user: **full bidirectional** (client decodes the server's dynamically-compressed
responses AND dynamically compresses its own requests). Built as 6 increments, **all DONE** (A–F),
committed + tested. Wired into `Http3Connection` and validated three ways: exhaustive unit tests,
a deterministic bidirectional **loopback** (symmetric `Http3LoopbackServer` with its own
encoder/decoder, green jvm + linuxX64), and the **live interop GET** which now returns 200 from
Cloudflare AND Google while decoding their *dynamically-compressed* response headers.

DONE (all in `:socket-http3` commonMain + commonTest, green on jvm; pure Kotlin so js/linuxX64 compile):
- **A — `QpackDynamicTable`**: insertion-ordered table, name+value+32 size accounting, capacity
  eviction, absolute indexing (`insertCount`), `maxEntries = maxCapacity/32`, `canInsertWithoutEviction`.
- **B — `QpackFieldSectionPrefix`**: Required Insert Count wrap/reconstruct (§4.5.1.1) + signed Base
  (§4.5.1.2). The tricky math, isolated + heavily tested.
- **C — instruction codecs**: `QpackEncoderInstruction` (SetCapacity/InsertWithNameRef/
  InsertWithLiteralName/Duplicate) + `QpackDecoderInstruction` (SectionAck/StreamCancellation/
  InsertCountIncrement) byte formats; shared `QpackStringLiteral` helper (Huffman-or-raw at any prefix).
- **D — `QpackDecoder`** (stateful): decoder table, `applyEncoderInstruction`, `decodeSection` with
  reactive blocked-stream waiting (StateFlow until insertCount ≥ RIC) + all field-line representations
  (static/dynamic/post-base) + Section Ack / Insert Count Increment emission.
- **E — `QpackEncoder`** (stateful): conservative **always-non-blocking** strategy — reference only
  static or *acknowledged* dynamic entries; insert-for-future-but-literal-this-time; never evict (insert
  stops when full). Tracks Known Received Count from decoder-stream acks. Returns the encoded section as
  a pooled `ReadBuffer` (over-allocated then `resetForRead()` → exact `remaining()`), emitting inserts as
  a side effect. Round-trips through a wired `QpackDecoder` in tests.

**F — DONE.** `Http3Connection` advertises capacity 4096 / 100 blocked streams; a per-connection
`QpackDecoder` (live from bootstrap) decodes responses and the peer's encoder stream; a `QpackEncoder`
(created once peer SETTINGS reveal a usable table, capped at the peer's max) compresses requests and
processes the peer's decoder stream. The router shares one `StreamProcessor` across the type-prefix
read and the instruction reader so no buffered bytes are lost; per-stream write mutexes serialize the
two QPACK uni streams; the QPACK halves use internal mutexes for `Dispatchers.Default` safety. The
symmetric `Http3LoopbackServer` (opt-in `qpackCapacity`) gives the deterministic bidirectional E2E.

Original F plan (for reference):
1. **Incremental QPACK instruction reader** (the hard part): the peer's QPACK encoder/decoder uni streams
   carry instructions with no length prefix that can split across `stream.read()` boundaries. Need either
   a `peekInstructionLength`-style helper (mirror `Http3FrameCodec.peekFrameSize`; `QpackPrefixedInteger`
   has no peek today — add one) or a suspend byte-source that refills from the stream on demand. Do NOT
   ship an "assume one instruction per read" shortcut — that's a silent correctness gap.
2. **Bootstrap/SETTINGS**: advertise `QPACK_MAX_TABLE_CAPACITY` + `QPACK_BLOCKED_STREAMS` > 0 (currently
   hard-coded 0 in `writeControlStreamHeader`). Instantiate per-connection `QpackEncoder`(peer max, emit→
   client encoder stream) + `QpackDecoder`(our max, emit→client decoder stream).
3. **Router**: replace the `drain` of the peer's QPACK_ENCODER stream with feed→`decoder.applyEncoderInstruction`,
   and QPACK_DECODER stream with feed→`encoder.processDecoderInstruction` (uses the reader from #1).
4. **Requests**: `openRequestStream` uses `encoder.encodeSection(fields, stream.streamId.id, pool)` instead
   of the static `QpackFieldSectionCodec.encode`. **Responses**: `readResponse` + `Http3Response.nextBodyChunk`
   use `decoder.decodeSection(buffer, streamId, pool)` (suspend; may block) instead of the static decode.
5. **peerSettings→capacity**: launch a task that awaits `peerSettings()` then `encoder.setCapacity(min(desired,
   peer.qpackMaxTableCapacity))`. Requests before that are static (capacity 0) — fine.
6. **Loopback E2E**: make `Http3LoopbackServer` symmetric by giving it its own `QpackEncoder`/`QpackDecoder`
   (reuse the same classes) so it dynamically compresses responses + decodes requests. Add a jvm+linuxX64
   test: repeat a request with a custom header twice and assert the 2nd is dynamically compressed (and the
   response likewise), decoding correctly both ways.

Note: the static `QpackFieldSectionCodec` (capacity-0 path) stays as-is; the dynamic encoder/decoder are
used once capacity > 0. RIC=0 sections decode identically through either.

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

## ✅ DONE — publishing + full target matrix + server push (branch `feat/http3-gaps`)

Three gaps closed this session (all green locally; not yet a PR):

- **Publishing + target matrix.** `:socket-http3` now applies `com.android.library` + the vanniktech
  `maven-publish` + `signing` plugins and a per-module `socket-http3/gradle.properties`
  (`artifactName=socket-http3`), mirroring `:socket-quic`. Targets expanded from `jvm+js+linuxX64` to
  the full host-gated matrix: **+androidTarget, +wasmJs, +linuxArm64**, and Apple (macos/ios/tvos/
  watchos) under `if (isMacOS)` — gated exactly like `:socket-quic` so the `api(project(":socket-quic"))`
  dependency exposes the SAME per-host target set (else resolution fails). `publishToMavenLocal`
  validated end-to-end: 7 publications (root KMP + android/jvm/js/wasm-js/linuxx64/linuxarm64), POM =
  "Socket HTTP/3", jvm variant depends on `socket-quic-jvm`. **CI:** the release pipeline auto-includes
  it (build-linux's root `publishToMavenLocal` stages it; publish-to-central zips the whole repo). Added
  `:socket-http3:{jvmTest,jsNodeTest,linuxX64Test,ktlintCheck}` to build-linux.yaml's curated test step
  so the published module is gatekept (NOT `gradle check` — that pulls in jsBrowser/wasmJsBrowser tests
  the runners can't host + the socket-quic flaky-suite handling). Android SDK present on the dev box, so
  androidTarget compile-verified locally; Apple is config-only (CI-verify on macOS).
- **Server push (RFC 9114 §4.6) — full client support.** `Http3Frame.PushPromise` (type 0x05) + codec
  (encode/decode/wireSize, 3 round-trip tests). New public API: `Http3ServerPush` + `Http3PromisedRequest`
  + `Http3Connection.pushes: Flow<Http3ServerPush>`, opt-in via `bootstrap(…, maxPushId)` /
  `withHttp3Connection(…, maxPushId)` (default -1 = disabled). Wiring: send MAX_PUSH_ID at bootstrap;
  PUSH_PROMISE handled on the request stream (in `readResponseHead`) AND interleaved in the response body
  (via a callback on `Http3Response`); a push-stream router branch (`handlePushStream`) reads the Push ID,
  validates it, reads the pushed response, and **parks until the response is closed** (`Http3Response.awaitClosed()`)
  so the body streams lazily — it owns the shared processor + stream lifetime, transferring processor-release
  ownership to the `Http3Response` once built (avoids the double-release `release()`=`processor.release()`
  would cause). Reorder-tolerant promise↔stream correlation via a `pushEntries` map keyed by Push ID.
  CANCEL_PUSH both directions (`Http3ServerPush.cancel()` sends + resets; server CANCEL_PUSH fails an
  awaiter). Push id over the advertised max, or any push when disabled, is H3_ID_ERROR (aborts at the
  source so it fires regardless of detection path). `Http3LoopbackServer` gained push-sending; E2E test
  `serverPushDeliversPromisedResponse` (jvm + linuxX64) + a scripted disabled-rejection test. **Behavior
  change:** a push stream when push is disabled now aborts with H3_ID_ERROR (was silently drained) — the
  existing scripted `peerSettings_resolvesWithQpackAndPushStreamsPresent` test was updated to enable push.
  *Not done (future tune):* re-issuing MAX_PUSH_ID to extend the credit window as pushes complete.
- **Closed issue #86** (the client; delivered by #123) with a completion summary.

Green: jvm + linuxX64 + jsNode test suites; wasmJs + android test sources compile. 180 linuxX64 / 179 jvm
tests, 0 fail.

## ✅ DONE — full server role + push-window tune + Apple/Android CI tests (branch `feat/http3-server-role`)

Stacked on `feat/http3-gaps`; green jvm + linuxX64 (183 linuxX64 tests, 0 fail) + android unit (173).

- **Full HTTP/3 server role (production).** New `withHttp3Server(port, tlsConfig, …, onRequest, block)` +
  `Http3ServerConnection` (per accepted QUIC connection: server control stream + SETTINGS, QPACK
  streams, routes client uni/bidi streams) + `Http3ServerRequest` (streaming body via
  `nextBodyChunk`/`readFullBody` + trailers) + `Http3ServerResponse` (`send()` one-shot, or streaming
  `sendHeaders`/`writeBody`/`sendTrailers`; auto-FIN + minimal 500 if the handler sends nothing) +
  `Http3ServerExchange`. Dynamic QPACK both directions when `qpackCapacity > 0`. Modeled on the proven
  `Http3LoopbackServer` mechanics (hand-rolled codecs, per the user — no buffer-codec/declarative). E2E
  tests `productionServerRole_getAndPostRoundTrip` + `…_dynamicQpackRoundTrip` (jvm + linuxX64) drive
  the real `withHttp3Connection` client against the production server. **Server-initiated push is NOT
  implemented** — a follow-up (inverse of the client push API).
- **Push-window tune (RFC 9114 §7.2.7).** The client now re-issues MAX_PUSH_ID upward as it observes
  push ids (`currentMaxPushId`, `maybeExtendMaxPushId`, window = initial maxPushId+1), so `maxPushId`
  is a rolling-credit window rather than a hard lifetime cap. `validatePushId` checks the live max.
  E2E `serverPushWindowRollsViaReIssuedMaxPushId` (maxPushId=0, server pushes >1 across requests).
- **Apple/Android CI tests.** `:socket-http3:testDebugUnitTest` added to build-linux.yaml (Android unit
  — codec/scripted + skip-guarded interop, validated locally) and `:socket-http3:macosArm64Test` to
  build-apple.yaml.
- **Deliberately deferred — smarter QPACK encoder eviction/blocking.** NOT minor: correctness-critical
  vs. the current correct-and-interop-proven never-evict strategy. Do it as its own adversarially-tested
  effort, not a tail-end bundle.

## NEXT (start here)

1. ✅ **In-process H3 loopback test — DONE** (see "DONE — in-process H3 loopback" above). Green on
   JVM + linuxX64; `Http3LoopbackServer` seeds the server role; server `openUniStream()` landed.
2. ✅ **RFC 9114 §8 error handling — DONE** (see "DONE — step 2" above): taxonomy + client validation
   enforcement + CONNECTION_CLOSE with the H3 code + RESET_STREAM/STOP_SENDING with the H3 code, all
   verified end-to-end on the jvm + linuxX64 loopback.
3. ✅ **Dynamic QPACK (RFC 9204), full bidirectional — DONE** (see "DONE — step 3" above): full
   dynamic table, encoder + decoder, instruction streams, blocked-stream waiting, wired into
   `Http3Connection`; verified by unit tests + a deterministic bidirectional loopback + the live
   interop GET decoding real servers' dynamic compression.
4. ✅ **Server push (RFC 9114 §4.6) — DONE**, and ✅ **full server role — DONE** (`withHttp3Server`;
   see "DONE — full server role" above). Remaining: **server-initiated push** (server side of §4.6),
   the Apple `reset()` + server-observed close-code follow-ups, and the deferred QPACK-eviction tune.
   (Encoder strategy note: the client encoder is deliberately conservative — never evicts, references
   only acknowledged entries — correct but not maximally compressing; a smarter strategy is a future tune.)
5. ✅ **maven-publish + Android/wasmJs/linuxArm64 — DONE.** Apple targets are config-only (declared under
   `if (isMacOS)`); verify on a macOS runner / CI. Optionally run Apple/Android *tests* in their CI workflows.
6. **WebTransport** (Phase 2): RFC 9220 Extended CONNECT (gate on `peerSettings().enableConnectProtocol`)
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
