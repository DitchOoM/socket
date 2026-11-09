# Owned-loop spike: results (measurement only, never merge)

Branch `perf/owned-loop-spike`, based on `main` at f3b2742a (#802 merged). M-series Mac, 18 cores
(6P+12E), JDK 21.0.9, FFM backend, loopback, both ends in one JVM. Android: temporary API 34 arm64
emulator with 2 cores, R8 release test APK, JNI backend (deleted after the run).

## Setups (`-Dquic.spike.loop=`)

| Mode | What runs where |
|---|---|
| `shared` | today: drivers on `Dispatchers.Default`, server loop/reader/handlers on `Dispatchers.IO`, each NIO channel parks in its own `select()` via `runInterruptible(Dispatchers.IO)` |
| `one` | one owned single-thread `ScheduledExecutorService` for every driver, the server loop/reader and the handlers. Channels still `select()` on IO and resume onto the loop. |
| `oneSel` | one `SpikeSelectorLoop`: a thread that owns ONE `Selector` multiplexing every connection's UDP socket, plus its timers and tasks (Netty-style). No per-channel selector, no IO hop. |
| `group` / `groupSel` | N = availableProcessors loops of the kind above; each connection (client and server side independently) pinned round-robin. |
| `limited1` | `Dispatchers.Default.limitedParallelism(1)` as the single loop. Still the shared scheduler, separate variant. |

The application side (the JMH thread's `runBlocking`) is outside the loop in every mode, as an app would be.

## 1. Hops per 64-byte echo (both ends), counted

async-profiler instrumenting each method with interval=1, divided by echoes completed in the window.

| Per echo | shared (default pool) | shared (pool=2) | one | oneSel |
|---|---|---|---|---|
| `CoroutineScheduler.dispatch` (shared pool) | 22.2 | 22.8 | 4.0 (IO select hops) | **0** |
| of which went to the global queue | 8.8 | 9.1 | 4.0 | 0 |
| owned-loop dispatches (same-thread queue) | 0 | 0 | 19.6 | 17.7 |
| `runBlocking` event-loop enqueues (app boundary) | 4.5 | — | 5.0 | 5.0 |
| `LockSupport.unpark` calls | **37.5** | 18.5 | 16.9 | **6.0** |
| `Selector.wakeup` | 0 | 0 | 0 | 1.15 |

The shared-pool hop chain for one echo (from the attributed call sites): app write → driver
(Default) → app; app read → driver → app; server reader `select()` on IO → reader resumes → rendezvous
→ server receive loop → driver → handler signalled → handler `StreamRecv` → driver → handler →
handler write → driver → handler; client reader `select()` on IO → reader resumes → driver → app
signalled → app `StreamRecv` → driver → app. Every arrow is a dispatch; 2.4 of the unparks per echo
wake the deadline-timer thread (a timer armed per loop turn and per datagram).

With `oneSel` the only cross-thread hand-offs left are the six at the application boundary (the
JMH thread's `runBlocking`), plus ~1 selector wakeup when the app hands the loop work.

## 2. Latency and bulk (JMH, interleaved rounds of 1 fork × 4 warm-up × 6 × 1 s; median of 3 rounds)

### Default pool (`-Dkotlinx.coroutines.scheduler.core.pool.size` unset, 18 cores)

| Benchmark | shared | one | oneSel |
|---|---|---|---|
| RT 64 B p50 / p99 / p99.9 / max µs | 129.8 / 212.7 / 281.3 / 1954 | 101.9 / 323.1 / 498.5 / 9110 | **66.0 / 149.0 / 236.3 / 2077** |
| RT 1 KiB p50 / p99 / p99.9 / max µs | 130.6 / 263.6 / 337.9 / 1942 | 91.6 / 209.4 / 320.0 / 11338 | **69.2 / 97.8 / 164.6 / 5636** |
| Stream-per-request p50 / p99 / p99.9 / max µs | 181.5 / 346.1 / 414.7 / 6021 | 123.9 / 197.1 / 317.8 / 6881 | **99.7** / 933.3 / 1603.6 / 5087 |
| Bulk MiB/s | 55.6 | **9.1** | 53.8 |
| RT 64 B beside a handler that blocks 5 ms, p50 / p99 µs | 124.5 / 212.0 | **7856 / 10714** | **7782 / 8605** |

A second clean pair of rounds (one vs oneSel only) gave RT 64 B p50 66.5 / p99 83.5 / p99.9 111 µs for oneSel.

### `-Dkotlinx.coroutines.scheduler.core.pool.size=2` (the emulator's scheduler)

| Benchmark | shared | one | oneSel |
|---|---|---|---|
| RT 64 B p50 / p99 / p99.9 / max µs | 92.8 / 206.8 / 298.6 / 1966 | 90.2 / 126.0 / 221.5 / 9994 | **70.9 / 92.0 / 186.6 / 1772** |
| RT 1 KiB | 90.6 / 205.8 / 297.0 / 1925 | 90.5 / 127.5 / 216.6 / 10502 | **69.6 / 85.4 / 158.2 / 1731** |
| Stream-per-request | 138.0 / 343.0 / 456.2 / 5923 | 121.2 / 156.2 / 229.3 / 6627 | **98.3 / 131.6** / 1593 / 4735 |
| Bulk MiB/s | 50.8 | **3.8** | 55.8 |
| Beside a blocking handler, p50 | 86.1 | 7791 | 7856 |

Library TCP round trip on the same machine: ~59 µs.

## 3. Many connections (`MultiConnectionLoad`, 2 s warm-up + 5 s, default pool)

Aggregate round trips/s; per-connection p50 / p99 (median over connections); process CPU in cores.

| M | shared | one | oneSel | group (18) | groupSel (18) | limited1 |
|---|---|---|---|---|---|---|
| 1 | 7.7k; 128/225 µs; 3.7 | 11.8k; 84/107; 1.9 | 15.3k; **63/84**; 1.3 | 10.8k; 92/115; 2.0 | 13.7k; 72/91; 1.5 | 12.2k; 79/138; 2.3 |
| 4 | 19.8k; 197/337; 7.0 | 12.6k; 315/392; 1.7 | 19.0k; 197/237; 1.5 | 17.4k; 222/419; 6.4 | **28.4k; 137/216; 5.1** | 16.3k; 244/306; 2.5 |
| 16 | 22.3k; 697/1399; 10.9 | 13.4k; 1173/1652; 1.8 | 19.3k; 786/931; 1.5 | 20.7k; 755/1300; 10.1 | **32.9k; 496/901; 6.4** | 16.6k; 960/1110; 2.4 |
| 64 | **stalled: every connection timed out (30 s)** | 12.9k; 4916/6068; 1.7 | 17.1k; 3680/4616; 1.5 | 21.8k; 2905/4182; 11.4 | **32.6k; 1794/3879; 7.4** | 17.1k; 3715/4093; 2.1 |

- One loop of either kind saturates at about one core: ~13k (executor) / ~19k (selector) round trips/s,
  flat from M=4 on.
- The selector loop group scales past today's shared pools at every M (33k vs 22k rt/s at M=16) on
  60% of the CPU.
- **The shared pools stall outright at 64 connections.** Each NIO channel parks a `Dispatchers.IO`
  thread in `select()`, and IO has 64 threads: 64 client readers + the server reader exhaust it.
  Confirmed both ways: M=60 completes, M=64 stalls, and M=64 completes with
  `-Dkotlinx.coroutines.io.parallelism=256`. That is a production defect on every JVM/Android app with
  more than ~63 concurrent QUIC connections per process (fewer if the app also uses IO). The selector
  loops have no per-channel thread and did not stall.
- Bulk with M ≥ 4 is poor and erratic in every mode on this `main` baseline (and timed out with M=64
  for one/group/limited1); it is not a dispatch signal. The bulk fixes in `perf/quic-bulk-throughput`
  are not in this spike.

Threads alive during the RT phase (harness threads excluded; the remaining `kotlinx-pool` threads are
the harness's own `runBlocking(Dispatchers.IO)` holders): shared M=1: 24 pool workers + the deadline
timer; oneSel M=1: 1 selector loop + 4 pool; shared M=64: 79 pool workers; groupSel M=64: 18 loops.

## 4. Android (2-core emulator, R8 on, JNI; Microbenchmark median of medians over 3 interleaved runs)

Microbenchmark reports min/median/max of batched measurements, not percentiles, so there is no p99 here.

| Benchmark (µs) | shared | one | oneSel |
|---|---|---|---|
| RT 64 B median (min–max) | 336 (249–539) | 440 (356–700) | **310 (242–447)** |
| RT 1 KiB | 394 (261–1390) | 425 (383–673) | **340 (280–436)** |
| Stream-per-request | 645 (421–808) | 593 (528–686) | **488 (426–573)** |
| Bulk 1 MiB, ms per MiB | 23.8 (≈42 MiB/s) | 90 (≈11 MiB/s) | **14.8 (≈68 MiB/s)** |

## Answer

- The cross-pool hand-offs do explain most of the gap. The selector loop removes ~60–64 µs of the
  ~71 µs QUIC-vs-TCP gap at p50 (130 → 66 µs against TCP's 59) and cuts p99 from 213 to 84–149 µs and
  p99.9 from 281 to 111–236 µs at 64 B. With the scheduler at 2 workers the shared pools are already
  faster (93 µs) and the selector loop still takes 22 µs off p50 and half the p99 (207 → 92).
- An owned executor that still sends each channel's `select()` to IO (`one`) only gets halfway and
  collapses bulk (9 MiB/s; 4 MiB/s on 2 workers; 11 on Android): the IO → loop resume per datagram is
  the cost. The loop has to own the selector.
- Bulk: unchanged with one selector loop for one connection; the selector group raised single-
  connection bulk to 97 MiB/s (both ends on separate loops) against 78 MiB/s shared.
- Regression, shown: a handler that blocks freezes its loop. Every connection on that loop waits
  (p50 7.8 ms beside a 5 ms blocking handler, against 124 µs today). So user handlers must not run on
  the loop: run them on the caller's dispatcher, as the loop-group design proposes, and keep only the
  driver, the sockets and the timers on the loop.
- Single loop saturates at ~1 core (~19k rt/s); a group of N loops scales past today and avoids the
  64-connection IO starvation.
- Open in the spike: oneSel stream-per-request p99/p99.9 (0.9–1.6 ms) is worse than today and was
  not root-caused; the max of the executor loop is a steady ~10 ms (not root-caused either).
