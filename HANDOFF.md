# Handoff — socket-quic on all platforms (next session)

PR #48's harness + LinuxSAN work is fully green (7/7 CI jobs). The remaining open items collectively land under "socket-quic on all platforms"; this file is a starting brief for a clean session, since the work is genuinely deep enough to deserve a fresh context window.

Read this, then `TODO.md`, then start.

## State at handoff

- **Branch:** `feature/transport-codec-api`, all pushed to PR #48 (mergeable).
- **CI:** 7/7 green on the last run after `70d9408` (durability hardening).
- **Recent commits (this session):**
  - `52031b9` — RST sidecar; re-enabled `pendingReadDuringPeerReset`.
  - `6c7049c` — LinuxClientSocket SAN/hostname verification via `X509_VERIFY_PARAM`.
  - `2f8378f` — CI test reports artifact on failure + `testLogging` FULL.
  - `29d3508`, `e3e757c` — rst-sidecar topology iterations (settled on `network_mode: host`).
  - `0f0d816` — root cause of the rst-test "flake": `SimpleNio*SocketTests` mutating JVM-global flags.
  - `acf8eac` — BoringSSL `set1_host` `namelen=0` gotcha — pass `strlen()`.
  - `00b3114` — ARM64 native job: add `rst` to its hand-rolled `docker compose up` list.
  - `70d9408` — pin `maxParallelForks=1` (explicit, doc'd); replace `delay(100ms)` with reactive `while !readResult.isCompleted yield()` loop.

## What's left (priority order)

### 0. **Reproduce, don't fix** 🔴 (do this first, before any code changes)

The three quiche failures (#1 Linux SIGABRT, #3 macOS deadlock, and Windows-which-skips) might share a root cause or might be three separate issues. Find out before deciding between quiche-bump and shim-fix — and definitely before staging Windows JNI (which would just inherit whichever bug Linux has).

**Repro recipe:**

```bash
# Linux x64 JVM panic (currently continue-on-error in CI)
RUST_BACKTRACE=full ./gradlew :socket-quic:jvmTest 2>&1 | tee /tmp/quic-jvm.log

# Linux x64 K/Native (cinterop path — different code path, same quiche)
./gradlew :socket-quic:linuxX64Test 2>&1 | tee /tmp/quic-knative.log

# macOS JVM deadlock (only if you have a Mac handy)
./gradlew :socket-quic:jvmTest 2>&1 | tee /tmp/quic-macos.log
```

**Capture for each:**
- Which test method triggers it. (`--tests <FQN>` to narrow.)
- Whether it's deterministic — run 3×.
- For the panic: full Rust message, FFI line (we already know `ffi.rs:2059:14`), and what Kotlin caller in `socket-quic/src/jvmMain/.../QuicheDriver.kt` was invoking which JNI shim function when it fired.
- For the deadlock: thread dump (`jstack <pid>` while it's hung) — look for the JNI call frame.

Only with that brief in hand do steps #1–#3 below become well-defined.

### 1. `socket-quic:jvmTest` quiche-0.28 panic 🔴 (Linux x64 + Windows)

**Symptom:** SIGABRT (exit 134) from `quiche_conn_recv` at `quiche/src/ffi.rs:2059:14` — non-unwinding Rust panic. Currently masked:

- `.github/workflows/build-linux.yaml` has the `:socket-quic:jvmTest` step under `continue-on-error: true`.
- Windows job excludes `:socket-quic:jvmTest` entirely (`6e41ce7` from a prior session).

**Where:** the JNI shim is in `socket-quic/src/jvmJniShim/` (Rust). The Kotlin caller is `socket-quic/src/jvmMain/.../QuicheDriver.kt`. The panic is upstream in `quiche` itself when called with some precondition violated by the shim or the Kotlin caller.

**Two paths — pick based on the repro brief from #0:**

- **(a) Bump quiche** (only if the repro shows it's a known/fixed upstream bug):
  - Check quiche release notes (0.29, 0.30, 0.31) for `quiche_conn_recv` fixes.
  - The bump touches the Rust shim's `Cargo.toml`, possibly the JNI signatures. quiche's API has had churn historically.
  - `:socket-quic:linuxX64Test` (K/Native cinterop) also depends on quiche; bump must succeed there too (`socket-quic/src/nativeInterop/cinterop/Quiche.def`).
  - ⚠ **Ask before doing the bump** — version churn risks linuxX64 cinterop in ways the test suite won't catch immediately.
- **(b) Fix the shim** (if repro shows a precondition violated by our caller):
  - The panic line tells you which assertion in quiche fired; map it back to the Kotlin call site and patch the precondition. Slower than a bump but doesn't bring API churn.

**Don't just re-mask with `continue-on-error`.** That's how this got here. Remove the mask in `build-linux.yaml` once the fix lands.

### 2. Windows `quiche_jni.dll` packaging 🟢 (~1–2h, mechanical — but **only after #1**)

**Wait for #1.** Windows hits the same `quiche_conn_recv` code path. Staging the DLL into the JAR before fixing the panic would just expose the same bug on a new platform.

When #1 is fixed:

`build-linux.yaml` already cross-compiles `quiche_jni.dll` via MinGW — uploaded as part of `quiche-linux-natives` (verify the upload bundle includes it). The piece missing: stage it into the merged JAR's `META-INF/native/windows-x64/`.

**Fix:** extend `socket-quic/build.gradle.kts`'s `nativeLibsByPlatform` map to include `"windows-x64" to listOf("quiche.dll", "quiche_jni.dll")` (note: no `lib` prefix on Windows). The `stageQuicheNativeResources` task and the `jvmNativesJar*` registers loop over this map automatically.

**Then** `.github/workflows/validate.yaml` already downloads `quiche-linux-natives` + `quiche-macos-natives` and injects them into the merged JAR (see `validate-artifacts.yaml`). Make sure the Windows DLL ends up alongside the Linux `.so`s in that artifact.

**Final step:** drop the `excludeTasks` for `:socket-quic:jvmTest` on Windows in `.github/workflows/validate.yaml` once the DLL is bundled — see commit `69ef010` ("ci(validate): inject Linux quiche natives into JAR; drop windows-x64 check") for the pattern.

### 3. macOS deadlock 🟡 (might share a cause with #1, see #0)

`:socket-quic:jvmTest` deadlocks (rather than SIGABRTs) on macOS. Different failure *shape* but might be the same underlying bug — a deadlock can be what happens when the same precondition violation surfaces in the apple-arm64 quiche build instead of crashing. Currently excluded for `:socket-quic:jvmTest` via `6e41ce7`. The repro in #0 captures the macOS thread dump; if the deadlocked thread is in the same JNI shim function as the Linux panic, the fix from #1 likely covers both.

### 4. macOS harness coverage 🔵 (blocked on upstream)

`HARNESS_DISABLED=true` on macOS in `build-apple.yaml`. Three fixture paths failed previously (Path A homebrew, Path B Apple `container`, Path C Colima). Re-evaluate when one of those upstream pieces moves OR adopt a self-hosted Mac runner. **Not actionable in-repo right now.**

### 5. Windows JVM mapping gaps 🟢 (deferred per user instruction)

5 tests skip on `isWindowsJvm()` — `JvmExceptionMapping.kt` doesn't have the NIO2/Iocp error shapes mapped. User explicitly said to defer.

### 6. Other (carried)

- `socket-quic` pool ownership audit — see `[[socket_quic_recvbufpool_bug]]`; pool-sharing contract isn't formally documented.

## Pointers / gotchas

- `socket-quic/libs/quiche/<platform>/lib/` is the staging area. CI populates it from cached/built artifacts.
- The native-libs JARs (`jvmNativesJar<Classifier>`) are how publishing works post-merge. `validate.yaml` downloads each platform's artifact and injects into the merged JAR. Don't try to make a single platform's JAR self-sufficient — the merge is the contract.
- `RUST_BACKTRACE=1` only helps if the panic unwinds. quiche 0.28's panic at `ffi.rs:2059` is *non*-unwinding — you'll need to look at the panic message and the surrounding Rust source, plus the JNI shim's call site for state.
- For the quiche bump path: try `./gradlew :socket-quic:buildBoringssl*` and `:socket-quic:buildQuiche*` to confirm the Rust toolchain still produces the libs. The cargo-ndk path is involved (Android cross-compile).
- The repo's `socket-quic/DRIVER_REDESIGN.md` is the design doc for the reactive QUIC driver. Worth a skim — the redesign already happened; what's left is upstream-quiche issues.

## What's intentionally NOT in scope

- macOS Apple K/N harness — wait for upstream tooling.
- Windows JVM exception mapping — deferred by user.
- Anything in `TODO.md` already marked `[x]` — those are done.

## TL;DR

**Reproduce first (#0), commit nothing yet.** Capture the panic/deadlock state from Linux JVM, linuxX64 K/N, and macOS JVM. Only then decide #1 (bump vs shim-fix) — ask before bumping quiche. Windows JNI staging (#2) waits for #1 — staging the DLL into the JAR before fixing the panic would just expose the same bug on a new platform. macOS deadlock (#3) might fix itself once #1 lands. Don't touch #4 or #5. **Remove `continue-on-error` masks once panics are fixed — don't leave them hiding flakes.**
