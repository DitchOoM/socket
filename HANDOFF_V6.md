# v6 Redesign — Execution Handoff

Companion to **`MAJOR_API_REDESIGN.md`** (the spec: all decisions locked). This file is the *how to execute*, written for a fresh session.

## Repos & branches
- **socket**: `/home/rbehera/git/socket` — branch `redesign/major-api-v6` (this doc + the spec live here, committed locally, NOT pushed).
- **buffer**: `/mnt/wslg/distro/home/rbehera/git/buffer` — currently `main`. Create `redesign/v6` (or a worktree) here; the byte-layer types live in `buffer-flow`.

## The cross-repo train (the one hard sequencing constraint)
`ByteSource`/`ByteSink`/`ReadPolicy`/`WritePolicy` live in **buffer** (`buffer-flow`). Socket cannot compile against them until buffer publishes. So every loop is:

1. Edit buffer (`buffer-flow`, worktree).
2. `./gradlew publishToMavenLocal` in buffer → `6.0.0-SNAPSHOT`.
3. In socket: bump `gradle/libs.versions.toml` `buffer = "6.0.0-SNAPSHOT"` + add `mavenLocal()` to root repositories.
4. Validate socket. Iterate.
5. **Only at the very end (Phase 5):** release buffer 6.0 to Central, re-pin, drop `mavenLocal()`.

This is the established train (see memory `project_buffer_codec_migration`).

## Environment limits (this WSL2 box)
- ✅ Validatable here: **JVM**, **JS/Node**, **Linux-native** (linuxX64), **Android** (emulator + NDK), JVM **FFM** and **JNI** quiche backends.
- ❌ NOT here: **Apple** (macosX64/Arm64, ios*, tvos*, watchos*, `socket-quic-nw`/NWConnection) — needs macOS. Implement to compile; defer Apple validation to a Mac.
- Shut down Android emulators when done (`adb emu kill`).

## Phase checklist (gates are strict — each compiles + targeted tests before the next)

### Phase 0 — buffer 6.0 (foundation; design-sensitive — do NOT fan out)
- Add `ReadPolicy` (`Bounded(d)` | `UntilClosed`, `toDeadline()`) and `WritePolicy` (mirror, default `Bounded`).
- Reshape `buffer-flow/ByteStream.kt` → `ByteSource` (read + `readPolicy` val + no-arg `read()` default consulting policy) / `ByteSink` (write + `writePolicy`) / `ByteStream : ByteSource, ByteSink`. **No defaulted params.**
- Keep `Sender`/`Receiver`/`Connection`/`StreamMux` deadline-free (unchanged).
- Validate: buffer `./gradlew :buffer-flow:compileKotlinJvm jvmTest` → `publishToMavenLocal`.

### Phase 1 — socket-core (prototype milestone; CHECKPOINT here)
- Re-pin buffer SNAPSHOT + `mavenLocal()`.
- Collapse `Reader`/`Writer` → byte trichotomy; `ClientSocket : ByteStream`; delete `SocketConnection`, `PlatformSocketConfig`, binary `NetworkCapabilities`.
- Build `TransportConfig` (bufferFactory + readPolicy + writePolicy + connectTimeout + tls + io); unify TLS.
- **Adapter rule (propagate-not-clobber):** `CodecConnection.fillFromTransport` / `send` call the leaf's no-arg `read()`/`write()`; explicit deadline only on real override. Same for the socket connection.
- **Fan-out OK** for the mechanical call-site sweep once the shape compiles.
- Validate: `:socket` jvmTest + jsNodeTest + linuxX64Test; one `CodecConnection` + one WebTransport read green. **← stop for human review.**

### Phase 2 — engine split (fan-out per module)
- `QuicEngine` SPI in `:socket-quic` (no native dep, no default).
- Move quiche → `:socket-quic-quiche`; NWConnection → `:socket-quic-nw`; add `:socket-quic-default` bundle (`defaultQuicEngine` per target).
- KMP spike: per-target default resolution inside the bundle without core→backend coupling.
- Validate: JVM (JNI + FFM) + linuxX64 + Android. Apple compiles only (validate on Mac later).

### Phase 3 — http3 / webtransport + capability model
- WT streams → consolidated types; delete `WebTransportSendStream`/`WebTransportReceiveStream` (→ `ByteSink`/`ByteSource` + `Resettable`).
- Adapter rule on `WebTransportMux`.
- Type-gated capability model: `WebTransportSupport` sealed (+ native-only `Multiplexed`); per-session `connect(url)` in commonMain.
- Validate: `:socket-http3` jvmTest (`*WebTransport*`, `*Http3*`).

### Phase 4 — browser + websocket + generalized capabilities
- `:socket-webtransport` browser actual (native `WebTransport`); `:socket-websocket` (native framing + browser `WebSocket`).
- Generalize sealed-provider capabilities (datagrams, migration, 0-RTT); per-platform `NetworkCapabilities` set.
- Validate: JS/browser (wasmJs/js) compile + Node tests; JVM.

### Phase 5 — release
- buffer 6.0 → Central; re-pin; drop `mavenLocal()`; `docs/UPGRADING-6.0.md` (docs-only migration); socket 6.0.
- Optional follow-ups: `ktor-client-ditchoom` engine; `install`-style interceptors.

## Fan-out map (where parallel agents actually help)
- **Sequential (no fan-out):** Phase 0 (foundation), buffer→socket compile gate, each phase gate.
- **Fan-out within a phase:** Phase 1 call-site sweep; Phase 2 per-module engine extraction; Phase 3/4 per-stream-type + per-call-site edits. Always after the phase's API shape compiles once.

## Validation quick-reference
```
# buffer
./gradlew :buffer-flow:compileKotlinJvm :buffer-flow:jvmTest publishToMavenLocal
# socket (this box)
./gradlew :socket:jvmTest :socket:jsNodeTest :socket:linuxX64Test
./gradlew :socket-quic:jvmTest                       # JNI default
./gradlew :socket-quic:jvmTest -PquicheJvmBackend=ffm # FFM
./gradlew :socket-http3:jvmTest --tests "*WebTransport*"
./gradlew ktlintCheck
# Apple: deferred to macOS — do not attempt here
```
