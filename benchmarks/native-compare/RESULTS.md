# Results (2026-11-12)

Three rounds of 8 s windows (2 s warm-up discarded) per stack and workload, stacks interleaved within each round,
on a quiet machine (the other project's CI was held idle). Cells are median [min-max] over the 3 rounds; where two
stacks' ranges do not overlap the difference is supported by this data, where they overlap it is not. Load (1-minute,
read before each run): Linux 0.84 to 2.02; Apple-silicon desktop 2.9 to 4.8 (its load was decaying from CI that had just ended).
Programs, tool versions and caveats (what is not like-for-like) are in README.md.

Machines: a quiet Linux x86_64 machine (WSL2, 24 cores; Kotlin/Native linuxX64 `-opt`, JDK 25.0.3 for the JVM row) and
an Apple-silicon desktop (M5 Ultra, 36 cores, macOS 27.0.1; Kotlin/Native macosArm64 `-opt`, Zulu JDK 25.0.4 for the JVM row).
`jvm-ours` is this library's JVM build (FFM backend) run by JMH in-process (`-f 0`), 5 s warm-up then one 8 s iteration,
default heap ergonomics, so its RSS includes the JVM heap and its CPU includes JIT/GC threads. Its bulk latency columns
are not measured (JMH throughput mode).

## Linux x86_64 (measured)

### QUIC long-lived stream, 64 B echo

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 452.4 [418.0-454.2] | 630.6 [624.6-677.4] | 2158 [2155-2329] | 480.0 [457.7-489.1] | 88 [87-124] | 17 [17-26] |
| quiche-floor | 108.4 [93.3-108.6] | 209.3 [191.1-230.1] | 9031 [8346-9795] | 19.2 [15.4-19.3] | 14 [14-14] | 2 [2-2] |
| rust-quinn | 135.6 [128.3-152.4] | 249.7 [230.7-260.0] | 7015 [6398-7610] | 41.7 [40.0-44.6] | 247 [247-247] | 25 [25-25] |
| go-quic-go | 138.1 [117.7-143.3] | 236.6 [201.1-250.6] | 7060 [6766-8175] | 72.7 [65.9-74.6] | 15 [14-15] | 12 [11-12] |
| jvm-ours | 247.0 [246.5-250.1] | 350.7 [338.4-369.7] | 4015 [3954-4016] | 466.5 [458.9-478.0] | 581 [577-591] | 52 [52-52] |

### QUIC stream per request, 64 B

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 652.7 [608.0-695.3] | 822.0 [809.6-926.4] | 1520 [1418-1619] | 917.6 [874.9-949.0] | 198 [177-214] | 17 [14-18] |
| quiche-floor | 90.9 [87.8-103.5] | 180.8 [151.1-217.5] | 10598 [9187-11090] | 16.5 [16.0-18.9] | 14 [14-14] | 2 [2-2] |
| rust-quinn | 144.4 [128.3-147.4] | 266.7 [231.4-295.7] | 6568 [6532-7673] | 54.0 [47.5-54.5] | 247 [247-247] | 25 [25-25] |
| go-quic-go | 172.5 [136.4-172.8] | 355.3 [322.8-381.2] | 5561 [5483-6724] | 146.6 [128.1-147.4] | 15 [15-15] | 14 [13-14] |
| jvm-ours | 368.6 [351.2-386.0] | 481.3 [480.8-578.6] | 2687 [2536-2809] | 782.9 [752.1-809.8] | 675 [673-683] | 52 [51-53] |

### QUIC bulk, 16 MiB per stream in 64 KiB writes

| stack | p50 us | p99 us | MiB/s | CPU us/MiB | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 721130.4 [717246.9-724385.6] | 791949.8 [742629.8-802793.2] | 22.0 [21.8-22.1] | 96504.0 [96313.8-96512.7] | 106 [80-109] | 21 [15-22] |
| quiche-floor | 88640.2 [88147.4-155023.2] | 163300.9 [116502.6-165350.8] | 178.5 [105.0-180.7] | 3875.4 [3837.3-5077.9] | 14 [14-14] | 2 [2-2] |
| rust-quinn | 76872.3 [68256.6-76922.4] | 78547.9 [70046.6-79925.1] | 209.5 [208.0-234.3] | 3297.8 [3107.6-3406.1] | 247 [247-247] | 25 [25-25] |
| go-quic-go | 81884.7 [73517.5-85480.0] | 99965.8 [75104.3-102876.8] | 195.5 [186.8-217.4] | 5937.1 [5498.7-6218.1] | 21 [19-23] | 14 [13-14] |
| jvm-ours | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 127.6 [121.9-143.5] | 32683.2 [29718.7-33172.7] | 614 [613-621] | 54 [53-61] |

### TCP 64 B echo

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 164.2 [155.6-175.0] | 283.8 [274.3-332.0] | 5927 [5514-6074] | 115.3 [114.8-115.4] | 63 [63-67] | 10 [10-12] |
| rust-tokio | 109.6 [103.3-136.4] | 216.0 [202.7-263.0] | 8862 [7221-9084] | 17.0 [16.5-17.4] | 14 [14-14] | 25 [25-25] |
| go-net | 108.7 [99.3-114.9] | 228.7 [187.9-232.2] | 9099 [8287-9675] | 23.0 [20.6-23.7] | 14 [14-14] | 6 [6-6] |
| jvm-ours | 162.6 [161.0-167.9] | 267.8 [236.0-281.6] | 5948 [5762-6088] | 156.4 [151.7-158.4] | 427 [423-431] | 75 [75-76] |

Cells are median [min-max] over the rounds. Bulk p50/p99 are per 16 MiB transfer, in us.

## Apple-silicon desktop, macOS arm64 (measured)

### QUIC long-lived stream, 64 B echo

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 110.6 [110.5-110.6] | 134.9 [134.3-135.9] | 8993 [8992-9001] | 236.6 [236.0-236.9] | 51 [51-51] | 16 [14-16] |
| quiche-floor | 23.8 [23.8-24.0] | 38.1 [38.0-38.5] | 37912 [37268-38647] | 21.2 [20.8-21.6] | 10 [10-10] | 2 [2-2] |
| rust-quinn | 35.5 [35.4-37.5] | 50.7 [50.5-52.5] | 27567 [25947-27672] | 32.9 [32.4-33.0] | 260 [260-260] | 37 [37-37] |
| go-quic-go | 39.2 [38.7-42.9] | 52.4 [51.8-56.0] | 25234 [23086-25524] | 51.4 [50.9-52.3] | 25 [24-26] | 15 [14-15] |
| jvm-ours | 82.9 [82.2-83.7] | 99.2 [98.7-99.3] | 11958 [11854-12098] | 232.9 [232.0-238.2] | 1360 [1355-1367] | 61 [61-61] |

### QUIC stream per request, 64 B

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 165.9 [165.3-166.0] | 196.4 [194.4-196.8] | 6008 [6007-6029] | 459.9 [458.6-461.2] | 816 [808-819] | 17 [15-17] |
| quiche-floor | 24.8 [24.5-29.2] | 38.6 [38.4-39.7] | 36134 [34011-36544] | 22.2 [22.1-23.6] | 23 [23-23] | 2 [2-2] |
| rust-quinn | 40.9 [40.5-41.1] | 56.5 [55.1-56.5] | 23973 [23934-24306] | 41.7 [41.4-42.0] | 350 [349-351] | 37 [37-37] |
| go-quic-go | 53.0 [53.0-54.4] | 148.8 [148.5-150.2] | 17150 [16873-17185] | 116.7 [115.7-117.8] | 23 [22-24] | 15 [15-15] |
| jvm-ours | 122.1 [120.8-122.4] | 145.7 [141.8-146.2] | 8090 [8068-8202] | 425.2 [422.9-427.8] | 1626 [1622-1633] | 61 [61-61] |

### QUIC bulk, 16 MiB per stream in 64 KiB writes

| stack | p50 us | p99 us | MiB/s | CPU us/MiB | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 328330.4 [327459.4-329235.1] | 332568.6 [329911.0-334241.0] | 48.7 [48.7-48.9] | 76846.0 [76752.7-76957.7] | 55 [53-55] | 16 [16-16] |
| quiche-floor | 85285.1 [84687.0-85584.4] | 90442.8 [90199.9-91635.6] | 187.1 [186.8-188.6] | 10278.3 [10223.0-10299.9] | 41 [41-42] | 2 [2-2] |
| rust-quinn | 63272.9 [59890.1-63320.9] | 64452.4 [61481.5-65071.6] | 252.8 [252.7-267.1] | 10781.2 [9553.8-10790.6] | 297 [297-298] | 37 [37-37] |
| go-quic-go | 77166.4 [77089.4-77337.9] | 79223.7 [78774.9-82253.8] | 207.1 [206.6-207.5] | 21372.9 [21351.8-21385.1] | 24 [24-24] | 17 [16-17] |
| jvm-ours | 0.0 [0.0-0.0] | 0.0 [0.0-0.0] | 110.1 [108.9-112.4] | 38271.7 [37787.8-38429.0] | 1365 [1360-1367] | 61 [61-62] |

### TCP 64 B echo

| stack | p50 us | p99 us | ops/s | CPU us/op | peak RSS MiB | threads |
|---|---|---|---|---|---|---|
| kotlin-native-ours | 51.6 [51.5-51.7] | 68.6 [66.6-69.1] | 19072 [18996-19082] | 90.8 [90.3-91.2] | 35 [34-35] | 9 [9-9] |
| rust-tokio | 21.1 [21.1-21.2] | 34.3 [33.7-34.6] | 46273 [45912-46722] | 15.3 [15.2-15.4] | 9 [9-9] | 37 [37-37] |
| go-net | 16.2 [16.1-16.2] | 27.1 [24.7-27.2] | 56654 [56431-57422] | 12.4 [12.3-12.4] | 21 [20-22] | 15 [14-15] |
| swift-network-framework | 45.2 [44.8-45.4] | 59.4 [55.1-59.5] | 22293 [22090-22351] | 49.7 [49.5-50.1] | 13 [13-14] | 4 [4-4] |
| swift-nio | 26.0 [25.8-26.1] | 37.5 [34.5-37.6] | 37696 [37345-37961] | 21.4 [21.3-21.6] | 15 [15-15] | 37 [37-37] |
| jvm-ours | 55.3 [55.2-55.4] | 80.9 [78.2-81.0] | 17647 [17630-17786] | 167.7 [165.6-167.8] | 1002 [1001-1007] | 108 [106-109] |

Cells are median [min-max] over the rounds. Bulk p50/p99 are per 16 MiB transfer, in us.

## Where our Kotlin/Native build spends its time (Apple-silicon desktop, Time Profiler, 20 s, optimized binary, in-process client+server)

Shares of non-idle samples, `scripts/native-perf/record.sh` buckets (one run each, not repeated):

| bucket | 64 B round trip | bulk |
|---|---|---|
| Kotlin: coroutines machinery | 20.2% | 18.9% |
| Network.framework / dispatch / objc (client UDP) | 19.3% | 20.2% |
| Kotlin: QUIC driver/stream | 12.5% | 8.8% |
| kernel send, server POSIX sendto | 8.9% | 12.4% |
| kernel send, client sendmsg (inside NW) | 7.4% | 9.2% |
| Kotlin: UDP channel glue | 7.4% | 7.6% |
| quiche + BoringSSL | 7.1% | 6.4% |
| Kotlin GC/alloc (+safepoint) | 10.1% | 8.4% |
| lock/wake hand-off | 2.0% | 2.7% |

Top frames: coroutines bucket `_tlv_get_addr` (thread-local lookups), `AtomicArray.checkBounds`, `CombinedContext.get`,
`LockFreeLinkedListNode.correctPrev`, `ContinuationInterceptor.get`, `beforeHeapRefUpdateSlowPath` (GC write barrier);
driver bucket `_tlv_get_addr`, `_platform_memset`, `AbstractList.checkElementIndex`, cinterop `toCPointer`/`rawValue`,
`ContinuationImpl.<init>`; Network.framework bucket `kevent_id`, `objc_release/retain`, `__bsdthread_ctl`.
quiche itself is about 7% of busy time. The profile shows where time goes, not why a given frame is slow.
No profile was taken on Linux (`perf` is not usable under WSL).
