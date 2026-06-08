# socket-quic fuzzing

Coverage-guided [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzzing of the QUIC recv
path. The target is `quiche_header_info` — the parse that runs on **every** datagram the server
receives, before any connection state exists, so an unchecked read here is a whole-process crash.

See `TESTING_STRATEGY.md` §8 for how this fits the broader robustness strategy (deterministic
malformed-packet floor → fuzzing → glibc malloc-check), and `src/jvmTest/.../fuzz/HeaderInfoFuzzer.kt`
for the target itself.

## Run it

```bash
# default 60s
./gradlew :socket-quic:quicHeaderFuzz

# longer campaign
./gradlew :socket-quic:quicHeaderFuzz -PquicFuzzSeconds=600
```

- **Seed corpus:** `fuzz/corpus/header-info/` (committed) — the binary forms of the deterministic
  malformed-packet vectors. libFuzzer reads these to start warm.
- **Output:** newly discovered inputs and `crash-*` / `oom-*` / `timeout-*` repros are written under
  `build/fuzz/header-info/` (gitignored). A crash file is a reproducer — replay it by adding it to the
  seed corpus or feeding it back through the harness.

### Caveat — the JVM lane has no coverage feedback

JVM Jazzer cannot instrument the native quiche parser, so libFuzzer's `cov:` counter plateaus almost
immediately. The JVM lane is therefore a **crash/robustness** fuzzer (libFuzzer's signal handlers turn a
native SIGSEGV/SIGABRT into a saved repro), not a coverage-maximizing one. For true coverage-guided
fuzzing of the parser, use the native lane below.

## Native lane — ASAN + coverage-guided (`cargo-fuzz`)

`fuzz/native/` is a cargo-fuzz crate that builds **quiche itself** with SanitizerCoverage +
AddressSanitizer, so libFuzzer gets real edge coverage of the Rust parser and ASAN catches the
heap-overflow / use-after-free class the glibc malloc-check lane only partially sees. The target
(`fuzz_targets/header_info.rs`) drives `quiche::Header::from_slice` — the Rust parser behind the same
`quiche_header_info` FFI the JVM target hits — and starts from the **same** committed seed corpus.

```bash
# default 60s (Gradle wrapper — reuses the seed corpus, writes new inputs/repros under build/)
./gradlew :socket-quic:quicHeaderFuzzNative
./gradlew :socket-quic:quicHeaderFuzzNative -PquicFuzzSeconds=600

# or invoke cargo-fuzz directly
cargo +nightly fuzz run --fuzz-dir socket-quic/fuzz/native header_info socket-quic/fuzz/corpus/header-info
```

- **Prereqs (opt-in, not auto-installed):** a nightly Rust toolchain and `cargo install cargo-fuzz`.
- **Coverage works here:** a 30s local run grew the corpus from the 12 committed seeds to ~47 inputs
  (`cov: 162 ft: 288`) at ~477k exec/s — the feedback the JVM lane can't produce.
- **quiche is pinned** to the shipped version (`fuzz/native/Cargo.toml`, kept in sync with
  `gradle/libs.versions.toml`). `Cargo.lock` is committed for reproducible fuzz builds.

> Note: this lane ASAN-instruments quiche's **Rust** path. The vendored BoringSSL C is not yet
> ASAN-built (it needs `-fsanitize=address` CFLAGS through quiche's `build.rs`); a future
> `quiche_conn_recv` target that exercises crypto would want that. The header parse is pure Rust, so
> the current target gets full sanitizer coverage of what it fuzzes.
