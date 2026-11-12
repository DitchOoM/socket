# Native QUIC perf recording

Repeatable CPU breakdown of the Kotlin/Native QUIC data path (client and server in one process, loopback).

1. Build an optimized test binary (macOS arm64):
   `./gradlew :socket-quic-quiche:linkReleaseTestMacosArm64 --no-configuration-cache --no-daemon -PlinuxTargets=false`
   The binary is `socket-quic-quiche/build/bin/macosArm64/releaseTest/test.kexe` (dSYM beside it).
2. Run the workload (inert unless `QUIC_PERF` is set), e.g. for CPU and quiche's own datagram counters:
   `QUIC_PERF=rr QUIC_PERF_SECONDS=30 /usr/bin/time -l test.kexe --ktest_filter='*QuicNativePerfTrace*'`
   `rr` = 64-byte request/echo on one stream; `bulk` = 64 KiB writes on one stream. The test prints
   `PERF-END` (ops, bytes, rate) and `PERF-STATS client|server` (quiche sent/recv datagrams and bytes).
3. Trace it and print the bucket shares: `scripts/native-perf/record.sh test.kexe rr 30 /tmp/rr1`.

Notes: Time Profiler reports parked threads as Running, so `buckets.py` classifies idle by stack (worker waits,
`start_wqthread`, server `poll`); shares are of non-idle samples. `leaves.py <pkl> <bucket prefix>...` lists the top
leaf functions of a bucket. Prefer shares and interleaved A/B runs on a loaded machine; record `uptime`.
