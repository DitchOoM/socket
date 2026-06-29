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
AddressSanitizer, so libFuzzer gets real edge coverage of quiche's Rust and ASAN catches the
heap-overflow / use-after-free class the glibc malloc-check lane only partially sees. Both targets start
from the **same** committed seed corpus.

| Target | Drives | Surface |
|--------|--------|---------|
| `header_info` | `quiche::Header::from_slice` | the public-header parse on every datagram (behind the `quiche_header_info` FFI the JVM target hits) |
| `conn_recv` | `quiche::accept` + `Connection::recv` | the **full server recv state machine** — packet-number decode, decryption attempt, frame parsing; the pre-auth bytes-from-anyone surface |

```bash
# default 60s (Gradle wrappers — reuse the seed corpus, write new inputs/repros under build/)
./gradlew :socket-quic:quicHeaderFuzzNative
./gradlew :socket-quic:quicConnRecvFuzzNative -PquicFuzzSeconds=600

# or invoke cargo-fuzz directly
cargo +nightly fuzz run --fuzz-dir socket-quic/fuzz/native conn_recv socket-quic/fuzz/corpus/header-info
```

- **Prereqs (opt-in, not auto-installed):** a nightly Rust toolchain and `cargo install cargo-fuzz`.
- **Coverage works here:** `header_info` grew the corpus 12→47 inputs (`cov: 162 ft: 288`); `conn_recv`
  reaches ~5× deeper (`cov: 835 ft: 1363`) and its discovered dictionary surfaces X.509 cert-DN strings —
  the recv path genuinely exercising TLS/certificate parsing. Both ran clean (no ASAN fault).
- **quiche is pinned** to the shipped version (`fuzz/native/Cargo.toml`, kept in sync with
  `gradle/libs.versions.toml`). `Cargo.lock` is committed for reproducible fuzz builds.
- **API surface:** the targets call only quiche's *public Rust API* — no new FFI or `socket-quic` surface.

### Optional: ASAN-instrument the vendored BoringSSL C (`-PquicFuzzBoringSslAsan`)

By default these targets ASAN-instrument quiche's **Rust** path. To also catch memory bugs in the
BoringSSL **C** crypto code that `conn_recv` exercises:

```bash
./gradlew :socket-quic:quicConnRecvFuzzNative -PquicFuzzBoringSslAsan
```

This sets `CC=clang CXX=clang++ CFLAGS=-fsanitize=address,fuzzer-no-link` for quiche's BoringSSL build.
It requires **clang specifically** — gcc's `libasan` won't coexist with the LLVM ASAN runtime cargo-fuzz
links for the Rust side. This option was **not verified in the session that added it** (no clang
available there), so it's kept opt-in and out of the gating CI lane; promote it once it's proven on a
clang host. The header parse is pure Rust, so `header_info` doesn't need it.
