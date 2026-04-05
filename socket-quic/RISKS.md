# Risks & Trade-offs: Shared QUIC Driver Refactor

## Active Risks

### 1. `flushOutgoing()` is now suspend
**What**: The driver loop calls `flushOutgoing()` after every command. On Linux, this uses `IoUringManager.submitAndWait()` which is suspend. On JVM, NIO `channel.write()` was blocking but fast.
**Impact**: A slow network could stall command processing — no new commands are processed while waiting for the kernel to accept outgoing packets.
**Mitigation**: io_uring/NIO sends are kernel-buffered and return almost instantly. If this becomes a bottleneck under load, buffer outgoing packets and send in a separate coroutine (decouple flush from command processing).
**Likelihood**: Low. UDP sends are fire-and-forget at the kernel level.

### 2. Per-packet buffer allocation in UDP reader
**What**: The old Linux code reused one `udpBuf` for all receives. The new design allocates a fresh `PlatformBuffer` per received packet (ownership transferred to driver via `RecvPacket` command).
**Impact**: More GC/allocation pressure under high packet rates.
**Why necessary**: The old design had a data race — the buffer could be overwritten by the next `io_uring_prep_recv` before the driver processed the previous data. Fresh buffers guarantee correctness.
**Mitigation**: Add a buffer pool (`Channel<PlatformBuffer>`) that recycles freed buffers. The driver already frees packets after `connRecv()`, so recycling is straightforward. Defer until profiling shows it matters.

### 3. `StreamRecvResult` allocation per read
**What**: Each `connStreamRecv()` call creates a `StreamRecvResult` sealed class instance instead of returning a packed Long.
**Impact**: One small heap allocation per stream read.
**Why acceptable**: The command path already allocates a `CompletableDeferred<T>` per read (much heavier — involves `AtomicRef`, continuation, etc.). The `StreamRecvResult` allocation is negligible by comparison.

### 4. `CinteropQuicheApi` uses `memScoped` per call
**What**: K/N cinterop calls use `memScoped { alloc<BooleanVar>() }` for output parameters. This allocates/frees stack memory per call.
**Impact**: Very fast (stack allocation), but adds a small overhead vs. the old direct-call approach.
**Why necessary**: The `QuicheApi` interface returns Kotlin types (`StreamRecvResult`, `QuicStreamId?`, `Duration?`) — the implementation must decode C output parameters into these types, which requires `memScoped` for the temporaries.

### 5. `select { onTimeout }` precision
**What**: The driver uses `kotlinx.coroutines.selects.select` with `onTimeout` for quiche timer management. Coroutine timeout scheduling goes through the dispatcher.
**Impact**: Timeout precision is limited by the dispatcher's granularity (typically ~1ms on modern schedulers).
**Why acceptable**: QUIC timeouts are typically in the range of 25ms–5s (initial RTT, PTO, idle timeout). Sub-millisecond precision is not required by the protocol.

### 6. `CPointer` ↔ `Long` conversion on K/N
**What**: `CinteropQuicheApi` converts between K/N `CPointer<T>` and `Long` handles via `rawValue.toLong()` / `Long.toCPointer<T>()`.
**Impact**: If a pointer is incorrectly cast to the wrong type, the error manifests as a segfault, not a compile error. The value class wrappers (`QuicheConn`, `QuicheConfig`, etc.) prevent mixing different handle types, but within a single implementation the conversion is manual.
**Mitigation**: The conversion happens in exactly one place per handle type (the `CinteropQuicheApi` implementation). Review carefully once, then it's correct forever.

### 7. Stream ID overflow for very long-lived connections
**What**: `QuicStreamId` wraps a `Long`. Client bidi streams increment by 4: 0, 4, 8, ... The theoretical maximum is `Long.MAX_VALUE / 4 ≈ 2.3 × 10^18` streams.
**Impact**: None in practice. Even at 1 million streams/second, overflow takes ~73,000 years.

### 8. Apple stays on a separate architecture
**What**: Network.framework is callback-driven and fundamentally different from the quiche-based command loop. Apple QUIC does not use the shared `QuicheDriver`.
**Impact**: Apple doesn't benefit from the shared driver's testability or type-safety improvements (beyond the `@Volatile`/`AtomicLong` fixes). If Apple QUIC logic grows more complex, it will need its own unit test strategy.
**Mitigation**: Apple's implementation is thin (~100 lines of connection logic). Network.framework handles the complexity internally. The risk is that future features (server-push, 0-RTT, connection migration) may require more logic that won't be shared.

## Resolved/Accepted

- **Backward compatibility**: The `QuicheApi` interface changes are internal. No public API changes.
- **Build time**: Moving files between source sets doesn't affect compilation speed.
- **Test coverage**: `ReactiveDriverTests` + `StubQuicheApi` move to `commonTest`, so driver tests run on all platforms — strictly better than before.
