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

## NEXT — step 4: request/response (RFC 9114 §4)

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

### ⚠️ Blocker to solve first: half-close (send FIN, keep reading)

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
