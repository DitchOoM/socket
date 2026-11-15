# Native comparison benchmarks

This library on Kotlin/Native against the native stacks a consumer would otherwise pick, with the same
workloads as `benchmarks/jvm` (JMH, ours vs Netty/Kwik/Ktor).

## Workloads

| id | shape |
|---|---|
| `qrr` | QUIC, one long-lived bidirectional stream: write 64 B, wait for the 64 B echo, repeat |
| `qspr` | QUIC, a new stream per request: open, write 64 B + FIN, read the echo to the server's FIN, close |
| `qbulk` | QUIC, one stream per op carries 16 MiB client to server in 64 KiB writes + FIN; the op ends at the server's FIN |
| `tcp` | TCP, 64 B written, 64 B echo awaited, repeat |

Every program runs client and server **in one process over 127.0.0.1** (as the JVM benchmark does), so CPU, RSS
and thread counts cover both ends. The server echoes a stream whose first byte is `E`; any other stream is drained
and answered with a FIN. QUIC settings are the JVM benchmark's: ALPN `bench`, peer verification off, idle timeout
10 min, max UDP payload 1350, flow control 64 MiB connection / 16 MiB per stream / 1,000,000 bidi streams,
migration off.

## Measurement (identical in every program)

- Timer: a monotonic clock around each operation (`Instant`, `time.Now`, `DispatchTime`, `TimeSource.Monotonic`),
  every sample stored in memory and sorted at the end (exact percentiles, no histogram bucketing).
- Warm-up: the first `NATIVE_BENCH_WARMUP` seconds (default 2) are discarded. The window is `NATIVE_BENCH_SECONDS`
  (default 10). One process per workload.
- CPU: the program reads `getrusage(RUSAGE_SELF)` (user + system, all threads) at both ends of the window; the
  scoreboard divides by operations (or by MiB for bulk). Peak RSS is `ru_maxrss` at the end of the run (whole run,
  warm-up included; it includes the sample vector, a few MiB). Threads are counted by `run.py` halfway through the
  window (`/proc/<pid>/task` on Linux, `ps -M` on macOS).
- `run.py` interleaves the stacks within each round, records `uptime` before every run, and reports median
  [min-max] over the rounds.

## Stacks and what is not like-for-like

| stack | what it is | threading model |
|---|---|---|
| `kotlin-native-ours` | this library, Kotlin/Native `-opt` (release) test binary: `NativeCompareBench` in `socket-quic-quiche/src/nativeTest`, built by `linkReleaseTest<Target>` | the connection driver runs on a `Dispatchers.Default.limitedParallelism(1)` lane; the caller is a coroutine on the test's `runBlocking` thread; the server handlers run on `Dispatchers.Default`; TCP is Network.framework on Apple and the Linux native TCP path (see `src/linuxMain`) on Linux |
| `quiche-floor` | quiche 0.30.0 Rust API directly, no framework (boring crate pinned to 4.22.0 as the repo does); same transport config incl. Cubic + HyStart, pacing enabled | one client thread (the app runs inline in its poll loop) and one server thread, each owning a nonblocking UDP socket and `poll(2)`; the send-time (pacing) hint is **ignored** and there is no thread hand-off, so it is a floor, not a deployable design |
| `rust-quinn` | quinn 0.11 + rustls 0.23 (ring) on tokio | tokio multi-thread runtime, a worker per core; quinn runs its endpoint driver as tasks; the server spawns a task per stream |
| `go-quic-go` | quic-go (version in `go/go.mod`) | goroutines on GOMAXPROCS threads; goroutine per stream; quic-go's own congestion control |
| `rust-tokio` | tokio TCP echo | multi-thread runtime, epoll/kqueue; echo runs on the I/O workers |
| `go-net` | Go `net` TCP echo | netpoller + goroutine per connection |
| `swift-network-framework` | Network.framework `NWConnection`/`NWListener`, the API our Apple TCP path sits on | libdispatch queues; the measuring thread blocks on a semaphore |
| `swift-nio` | SwiftNIO 2.99.0 | `MultiThreadedEventLoopGroup`, one loop per core; echo runs on the loop; measuring thread blocks on a semaphore |

Differences that matter when reading the numbers:

- **Congestion control / pacing**: ours and the floor are quiche Cubic with pacing on; quinn uses its default
  Cubic; quic-go uses its own implementation. On loopback with no loss this barely matters for 64 B round trips; it
  can for bulk.
- **TLS verification**: ours sets `verifyPeer = false`; the floor `verify_peer(false)`; quinn installs a verifier
  that accepts any certificate; quic-go sets `InsecureSkipVerify`. All handshake TLS 1.3 with the same RSA key.
  Ours loads `socket-quic-quiche/testcerts` (an X.509 v1 certificate); rustls rejects v1, so every reference uses a
  v3 certificate made from the same key by `gen-cert.sh`. The crypto library differs (BoringSSL for ours and the
  floor, ring for quinn, Go's crypto for quic-go), which only shows in bulk.
- **Hand-off**: quinn, quic-go, tokio, Go net and NIO run the echo on their I/O threads; ours hands each stream
  to a coroutine on another dispatcher; the floor runs it inline.
- **Caller**: Rust/Go/Swift measure from a blocking-style loop or a task; ours measures from a coroutine on a
  single-thread event loop (the JVM benchmark uses a `runBlocking` per op).
- **Bulk** latency columns are per 16 MiB transfer; the ops/s and MiB/s columns are derived from that.

## Build

Needs: Rust (cargo), Go, Swift/Xcode (macOS only), JDK 21 for the Gradle launcher, cmake and a C++ compiler (quiche's
BoringSSL build), `openssl`.

```sh
benchmarks/native-compare/gen-cert.sh /path/to/certs
(cd benchmarks/native-compare/go && go build -o bin/ ./cmd/...)
(cd benchmarks/native-compare/rust && cargo build --release)
(cd benchmarks/native-compare/swift && swift build -c release)          # macOS only
# Kotlin/Native release test binary (-opt, no debug info):
./gradlew :socket-quic-quiche:linkReleaseTestMacosArm64 --no-configuration-cache --no-daemon -PlinuxTargets=false
./gradlew :socket-quic-quiche:linkReleaseTestLinuxX64   --no-configuration-cache --no-daemon
```

The binary is `socket-quic-quiche/build/bin/<macosArm64|linuxX64>/releaseTest/test.kexe`. It runs nothing unless
`NATIVE_BENCH` is set, e.g. `NATIVE_BENCH=qrr test.kexe --ktest_filter='*NativeCompareBench*'`.

## Run

```sh
python3 -I benchmarks/native-compare/run.py --kexe <test.kexe> --certs /path/to/certs --rounds 5 --json out.json
# one stack / one workload:  --only rust-quinn --workloads qrr,qspr
# timed runs on a shared machine: --lock ~/socket-bench.lock
```

Tool versions are recorded with the results in the pull request.
