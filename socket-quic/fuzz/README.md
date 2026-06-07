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

## Caveat — native parser, no coverage feedback

JVM Jazzer cannot instrument the native quiche parser, so libFuzzer's `cov:` counter plateaus almost
immediately. This is therefore a **crash/robustness** fuzzer (libFuzzer's signal handlers turn a native
SIGSEGV/SIGABRT into a saved repro), not a coverage-maximizing one. Deeper coverage-guided fuzzing of the
parser would need an ASAN libFuzzer build of quiche — a separate effort.
