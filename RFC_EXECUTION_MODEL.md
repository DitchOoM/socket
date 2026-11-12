# RFC — The execution model: who runs what, on which thread, and what a hand-off costs

**Status:** **Draft, for the owner's decision.** A design study with prototypes and measurements; no product code changes in this PR. Revision 3 adds the quiet-machine numbers of §5.4 and §5.8 and **reverses revision 2's headline** (below). Revision 2 was written against `main` at f8adf8ab (which contains #886, the timers) plus the diff of #888 (still open); #887 (TCP) is measured separately on its own branch (§5.7). Revision 1 had been measured before #886 merged; its 10.0 wake-ups per echo are now 6.0 (§3.2).
**Builds on:** `RFC_READ_TIMEOUT_CONTRACT.md` and `RFC_WRITE_TIMEOUT_CONTRACT.md` (the deadline contracts this model must keep), `RFC_DETERMINISTIC_SIMULATION.md` (the sim used for §6.5), and the owner's standing rules quoted in §1.2.
**Companion data:** the prototypes are local branches `proto/ack-hold` (everything: the lab, option E, option B on the real QUIC driver; based on `main` + #888) and `proto/exec-lab` (the earlier lab-only state). They are not part of this PR. §12 lists what each contains.

## 0. Summary

### 0.1 What revision 2 changed (read this first)

**Revision 3 headline, stated first: the quiet-machine numbers reverse revision 2's laptop-based claim.** On a quiet Linux machine the real library with the handler on the loop is p50 211 us / CPU 131 us against Netty's on-loop 134 us / 66 us (57 % and 2x worse), and the by-construction path (no handler on the loop) is 224 us / 140 us against Netty's `EventExecutorGroup` form 157 us / 101 us (43 % and 39 % worse; the owned pool's spin accounts for about 25 us of the CPU, not most of it). The ACK hold cuts CPU 15 % but raises p50 11 % there. Revision 2's statement that B + E is "within about 10 % of Netty" was a laptop (load 6 to 8) result and does not hold; see §5.4 and §5.8. The remaining gap is dominated by the library's own per-echo work, not by hand-offs.

Headline changes of revision 2 (still true where they are counts, checked or arithmetic):

1. **The baseline moved.** #886 (timers) merged. The real library's echo is **6.0 wake-ups, not 10.0**; with the handler on the loop (B) **2.0** (Netty: 1.0 on the loop, 3.0 on an `EventExecutorGroup`) [counts, laptop]. The laptop timings that suggested near-parity with Netty are superseded by the quiet rows above.
2. **The by-construction path was measured on the real library** (§6.10, answer to the owner's question c): `main` + #886 + #888 + E + four owned handler threads, handler off the loop: **3.0 wake-ups, equal to Netty's `EventExecutorGroup` form**, p50 about 15 % above it and CPU about 50 % above it (the spin burns CPU). It cannot approach Netty's on-loop form, and two of the options it was thought to include (D, I) do not apply to QUIC without a copy.
3. **The ACK-hold formula `min(200 us, srtt/8)` is withdrawn** (§6.5, question a). The measured 300 us hold was really one loop timer tick (about 1 ms: the loop rounds timeouts up to milliseconds), so the formula would have "worked" on loopback only by that accident. Replacement: no timer when the handler is on the loop, one tick with hit-rate gating off the loop. The inference that quiche reports the real ack delay is **now verified** in the deterministic sim (peer RTT sample unchanged by a 2 ms hold).
4. **The watchdog's invariant was restated** (§7.3, question b): "at most one thread inside a connection's turn, and a library turn is never handed off", with the hand-off specified for the real driver and an expected neighbour stall per handler-hour for a handler that blocks once in a thousand resumes.
5. **TCP** (§5.7.1, question d): #887's selector-loops path cuts threads 5x and server CPU per operation 4x at 1024 connections; its default NIO2 path does not. This argues for shipping the loops backend as the first TCP step and for flipping the default only after the conformance, TLS and tail-latency checks listed there.
6. **Decision D2 is larger than revision 1 said**: queued writes need a copy or buffer ownership on QUIC (§6.10).


1. A consumer measures a library by what one operation costs them: wake-ups, CPU, bytes, threads, tail latency, and what happens to everyone else when their own handler misbehaves. Netty's advantage on a 64 B echo is not QUIC or quiche (both wrap the same library); it is that its handler runs **inside the read, on the event loop**, so one request costs one wake-up and one datagram carries the reply and the ACK. This library pays a hand-off in each direction, an extra loop wake, and an ACK-only datagram.
2. The gap is **not server-only**. A client pays the same two resumes (the write's result, the read's bytes) through its caller's dispatcher; on Android's main thread one of them is irreducible, the other is a design choice (§4).
3. **Quiet-machine result (revision 3), real QUIC library, 64 B echo, p50 / CPU per echo:** `main` + #888 203 us / 187 us; handler on the loop (B) 211 us / 131 us; by-construction (E + owned threads, spin off) 224 us / 140 us; Netty on its loop **134 us / 66 us**; Netty on an `EventExecutorGroup` 157 us / 101 us (§5.4). On the laptop (wake-up counts, which do not depend on the machine) the baseline is 6.0 wake-ups after #886, B 2.0, Netty 1.0 on its loop and 3.0 on an executor group. **So, on Linux, neither the on-loop design nor the by-construction design reaches Netty's best case, and the by-construction design trails Netty's matching (executor-group) form by about 40 % in CPU.** The laptop's apparent near-parity (revision 2) was not real. The gap that remains is the library's own work per echo (18 KB allocated against 2.2 KB, driver turns, datagrams), which the execution model does not touch.
4. Running the handler on the loop is only acceptable if a handler that blocks cannot stall the others. Measured with a 100 ms blocking handler (§5.6): Netty's on-loop handler stalls its neighbour **106 ms**, and the real QUIC driver with handlers moved onto its loop turns a 5 ms blocking handler into a **6.5 ms** round trip for a neighbour stream (140 us before; §5.6, §5.8); the prototype loop that **hands the selector to a successor thread when a unit of work overruns 1 ms** stalls its neighbour **1.8 ms** and the off-loop paths 0.3 to 3.4 ms. That is a change to the letter of "the transport never runs user code on its loop" that keeps its purpose with a bounded (about 1 to 2 ms) rather than zero worst case. **It is the owner's decision (§10, D1).**
5. Recommended design (§8): keep selectors on dedicated platform loops and the public API suspend-only; run a handler **off the loop by default**, promote it to **on the loop automatically once its measured time-per-resume proves it short**, demote it on the first overrun, with the **hand-off watchdog** bounding any stall; per-route override in both directions; **ACK hold (E)** automatic on a clean path; **queued writes (I)** as the write contract if the owner accepts "returns when accepted"; **lean continuations (H)** everywhere; **caller-runs (D) and batched resumes (F) only on threads the library owns**; kernel batching (G) on Linux and Apple as a later, separately gated step.
6. Honest rows (§9): a suspend-based, main-safe API **cannot** beat an on-loop callback API on the write-and-wait round trip by more than the caller's own resume, and it pays for coroutine state in allocation (13 KB per QUIC echo today against Netty's 2.3 KB; the lab floor for the coroutine machinery alone is 45 B). What the consumer gets in exchange is that blocking in a handler is survivable by construction and the main thread is never asked to block.
7. Not obtained: quiet-machine timings (§5.8), Native and Android numbers, Netty QUIC at many connections, kernel batching. They are marked unknown where they matter.

## 1. Goal and the scoreboard

### 1.1 What "beats Netty from a consumer standpoint" means

A consumer does not run Netty's best case. They run a handler that waits on a database, a client that issues sequential requests from a coroutine, a server with thousands of mostly idle connections, and sometimes a handler that blocks. So the scoreboard has six workloads and eight axes, and a design counts as beating Netty on a row only if it wins that row's axes on the workload as a consumer would write it, with Netty used the way its own documentation tells you to use it for that workload (handler on the event loop when it does not block; on an `EventExecutorGroup` when it does).

| workload | how a consumer writes it | Netty form measured |
|---|---|---|
| (a) trivial non-blocking handler (echo) | handler returns without waiting | handler on the event loop (Netty's best case) |
| (b) handler that waits or blocks | awaits a service, or blocks a thread | (b1) completion callback that writes from the service thread; (b2) handler on an `EventExecutorGroup` that blocks on the service |
| (c) many connections / many streams | hundreds to thousands of lock-step conversations | on-loop and `EventExecutorGroup` |
| (d) bulk | one stream, large writes | not re-measured; PR #880 has the Netty numbers. Only the prototypes' effect on this library's bulk is measured (§6.5) |
| (e) client from a coroutine | sequential requests, caller on a background dispatcher or on a single-threaded "main-like" dispatcher | listener bridged to a coroutine |
| (f) idle | N open connections, nothing happening | idle channels |

Axes: p50 and p99 latency, CPU per operation, bytes allocated per operation, resident memory per connection, threads, syscalls per operation, wake-ups per operation (the cost a hand-off really has), and a **safety column**: when the handler blocks for 100 ms, who else stalls.

### 1.2 The rules this design is held to

From the owner's notes (`api-must-be-main-safe`, `no-selectors-on-virtual-threads`, `perf-goal-lightweight-validated-by-traces`, `project-defaults-design-bar`, `design-no-impossible-states`, `server-0rtt-safe-default-fast-when-known`): the public API is suspend-only and never blocks its caller, so it is safe on the Android main thread and a desktop UI thread; the transport never runs user code on its I/O loop; selectors live on dedicated platform threads, never virtual threads; no `kotlin.ByteArray` in production code; unrepresentable impossible states; performance claims need real traces from optimized builds; and for a fast-versus-safe trade-off, the pattern the owner approved for 0-RTT: **safe by default, fast automatically when known safe, per-stream/route override, no global switch.**

The second rule is read here by its purpose: *a consumer cannot stall the transport or other connections.* §7 separates the letter from the purpose and says which options keep which.

## 2. How this was measured, and what each mark means

Marks used below: **[M]** measured in this study; **[PR]** taken from a PR body (#880, #886, #887, #888), not re-measured; **[I]** inferred from measurements or from reading code, not tested; **[U]** unknown.

Instruments:

- **The real library**, JMH-free driver in the prototype branch (`LabMain`, scenario `rt`), `stack=oursQuic | oursTcp | nettyQuic`, JDK 25, FFM backend, loopback, same `QuicLoopback` and `NettyQuicLoopback` the comparison benchmarks of #880 use. Counts per operation come from JFR method timing on `LockSupport.unpark`, the selector's `wakeup`/`doSelect`, `SocketDispatcher.read/write` and `CoroutineScheduler.dispatch`, divided by the operations run (warm-up included). **Counts do not depend on load.** Stack attribution of the remaining wake-ups is from JFR method tracing with call stacks.
- **The lab**, a 600-line selector loop that is also a `CoroutineDispatcher`, with connections whose `read`/`write` are suspend functions, so options A to I can be compared on one harness over loopback TCP. It is a floor for the execution model, not the library: no TLS, no QUIC, no buffer allocation (a connection hands out two reusable direct buffers), one outstanding request per connection. Where it and the real library are compared the text says so. Netty is measured in the same process with the same drivers.
- **The deterministic sim** (`withMigrationSim`, virtual time, seeded loss) for option E.
- **Timings** (p50, p99, CPU) are of two kinds and each is labelled. **Quiet-machine** numbers (§5.4's first table, §5.8, §6.10) come from a quiet Linux x86_64 machine, JDK 25, our own `libquiche`. **Every other timing** (the lab tables, the hold sweep, TCP, many connections, idle, safety, bulk, and B + E) was taken on an Apple-silicon laptop whose 1-minute load average was 6 to 20 (other work); they show ordering and magnitude, they are not a result, and revision 3 shows that for the real-library echo the laptop's ordering of the library against Netty was wrong on Linux. CPU is process CPU time over the window divided by operations.

## 3. The baseline, decomposed

### 3.1 What a Netty echo is

[I] from `QuicheQuicChannel` and `SingleThreadEventExecutor` in 4.2.20: the handler runs inside the read on the event loop; the write and one `connectionSend` happen after the read batch, so the reply and the ACK leave in one datagram; quiche's timeout is a task on the same loop; a wake-up is skipped when one is already pending. Per echo: one wake-up (the caller's), no hand-off, no timer, no extra datagram.

### 3.2 What an echo is here, with the real library

[PR] before #886/#888: 13 wake-ups, 3 hand-offs to the pool, 4 timers armed, 8 loop turns, 14 `quiche_conn_send`, 4 datagrams, 23 KB. [M] on `main` (with #886) + #888: **6.0 wake-ups, 2.0 pool dispatches, 1.7 selector wake-ups, 19.9 KB**; 4 datagrams [PR]. The six wake-ups, attributed by call stack [M, JFR method tracing of `LockSupport.unpark`, `runBlocking` caller]:

| wake-ups per echo | cause (call stack) | removed by |
|---|---|---|
| 2.0 | the loop resumes the caller (`EventLoopImplBase.dispatch`): the write's result and the read's bytes | the write's result by option I; the read's bytes cannot go |
| 2.0 | the loop hands the handler's read to `Dispatchers.IO` (`signalBlockingWork`) | option B (handler on the loop) or an owned handler executor |
| 2.0 | pool workers waking other workers while the handler runs (`signalCpuWork`) | option B or an owned handler executor |

#886 removed the other four, as designed: revision 1's tree showed two more rows, the caller's two `withTimeout` bounds arming kotlinx's `DefaultExecutor` and the handler's bounds arming the timer queue (10.0 wake-ups). With B the count is **2.0** (the first row), measured; with option I as well 1.0, which is Netty's one. The trace is of the benchmark's `runBlocking` caller; a caller on another dispatcher moves the first row to that dispatcher's own wake and changes no count.

What does not go away with any of this is the real library's per-operation allocation: 13 to 20 KB against Netty's 2.2 KB, mostly coroutine state, `CompletableDeferred`s and per-operation commands [M for the totals, I for the split]. The lab's coroutine machinery on the loop allocates 45 B per echo with lean continuations (§6.8), so most of the 13 KB is the driver's command objects and the buffer handling, not coroutines as such. That is a different work item (§11, PR 1) and the RFC does not pretend the execution model fixes it.

## 4. Question 1 — is the gap server-only?

No. The same two things happen on a client, and a third is added by where the caller lives.

**Where a client's continuation resumes.** `read()` parks a continuation; the loop completes it; kotlinx resumes it through the caller's `ContinuationInterceptor`. For a caller on `Dispatchers.Default`/`IO` that is a pool dispatch and, when no worker is awake, a wake-up. For a caller on a single-threaded "main-like" dispatcher it is a post to that thread's queue and one wake of it. For `runBlocking` it is an unpark. The write is the same: `write()` submits to the loop (one loop wake), the caller suspends, and the loop resumes it (a second resume) when the bytes are accepted. So a client echo costs, in the lab, against the cheapest possible server (§5.3):

| client (lab) against an inline server | write | wake-ups | selector wake-ups | pool dispatches | CPU us/op |
|---|---|---|---|---|---|
| caller `blocking` | submit | 2.0 | 1.0 | 0.0 | 24 |
| caller `blocking` | queued | 1.0 | 1.0 | 0.0 | 20 |
| caller `default` | submit | 2.0 | 1.0 | 2.0 | 27 |
| caller `default` | queued | 1.0 | 1.0 | 1.0 | 23 |
| caller `main` | submit | 2.0 | 1.0 | 0.0 | 26 |
| caller `main` | queued | 1.0 | 1.0 | 0.0 | 21 |
| caller `loop` | submit | 0.0 | 0.0 | 0.0 | 14 |
| caller `blocking`, against the status-quo server | submit | 4.0 | 2.0 | 2.0 | 37 |
| caller `blocking`, against the status-quo server | queued | 3.0 | 2.0 | 2.0 | 37 |
| caller `default`, against the status-quo server | submit | 4.0 | 2.0 | 4.0 | 41 |
| caller `default`, against the status-quo server | queued | 3.0 | 2.0 | 3.0 | 38 |
| caller `main`, against the status-quo server | submit | 4.0 | 2.0 | 2.0 | 39 |
| caller `main`, against the status-quo server | queued | 3.0 | 2.0 | 2.0 | 40 |

[M] counts. Reading the table:

- **The minimum for any suspend API is one caller resume per read.** Even a client whose code runs on the loop itself (`loop`) is not an option on Android's main thread or a UI thread; it is the shape of library-internal code and of this library's own request/response helpers.
- **A suspending write costs a second caller resume.** On Android's main thread this is a second `Handler` post and a second main-looper wake per request. Making the write non-suspending (option I) removes it: 2.0 wake-ups become 1.0, which with the loop wake from the submit is the same two wake-ups a Netty client pays (`writeAndFlush` from a non-loop thread wakes the loop; the listener or bridge wakes the caller).
- **What a client on the Android main thread necessarily pays:** one post to the main looper per read result (a `MessageQueue.enqueueMessage` and, if the looper sleeps, a native wake), one `Selector.wakeup` (an eventfd or pipe write, not a socket write) per write, and no blocking. A direct socket `write()` on the main thread (option D) is excluded for callers we do not own: Android's `StrictMode` network-on-main detection hooks libcore's socket calls (`BlockGuard`); whether a non-blocking NIO write trips it is **[U]** (needs a device or emulator; none were allowed), and the owner's main-safe rule makes the question moot. [I]
- **A client on a background dispatcher** pays the same plus a pool wake when the pool is idle (the `default` rows: 1 more dispatch). Using the caller's dispatcher is correct (the consumer chose it); the library's job is to make exactly one resume per awaited event.
- **Kotlin/Native** [I from code, not measured]: a connection's driver runs on `Dispatchers.Default.limitedParallelism(1)` (`DriverPlacement.native.kt`), a view of the shared pool, not a thread; `Dispatchers.Default` is also where server handlers run. So Native already has "no thread of its own per connection": every resume is a pool dispatch, and a handler placed on the connection's lane (option B) costs **no extra hop and no safety loss** beyond the pool thread it occupies, because each connection has its own lane. On Apple's TCP path, `NWConnection` callbacks run on the connection's dispatch queue and complete a `CompletableDeferred` (`NWSocketWrapper.readRaw`, one `Mutex.withLock`, `withTimeout` and a deferred per read): the hop is queue thread -> pool, and the allocation per read is the larger problem than the hop. Neither was measured.

## 5. Results

All counts are per operation, [M]. Timings are from the loaded laptop (§2) and are indicative.

### 5.1 (a) Trivial handler, server-side placement (lab, TCP)

| option (lab, TCP echo, 64 B, blocking caller) | wake-ups of parked threads | selector wake-ups | pool dispatches | p50 us | CPU us/op | peak threads |
|---|---|---|---|---|---|---|
| A status quo: handler on shared pool, write submitted to loop | 4.0 | 2.0 | 2.0 | 44 | 62 | 13 |
| H lean continuations (A + reusable continuation, inline fast path) | 4.0 | 2.0 | 2.0 | 57 | 61 | 13 |
| I queued write (A + non-suspending write) | 3.0 | 2.0 | 1.0 | 34 | 40 | 13 |
| B handler on the loop (coroutine on the loop dispatcher) | 2.0 | 1.0 | 0.0 | 25 | 25 | 11 |
| B + H | 2.0 | 1.0 | 0.0 | 25 | 26 | 11 |
| B + H + hand-off watchdog | 2.0 | 1.0 | 0.0 | 26 | 29 | 12 |
| C run-until-first-suspension (then pool) | 2.0 | 1.0 | 0.0 | 28 | 36 | 13 |
| D caller-runs write on the handler thread | 3.0 | 1.0 | 1.0 | 33 | 40 | 13 |
| F owned single handler thread, spin-then-park | 3.1 | 2.0 | 0.0 | 87 | 124 | 12 |
| F + D | 2.8 | 1.0 | 0.0 | 50 | 82 | 12 |
| F + D + H | 2.8 | 1.0 | 0.0 | 44 | 76 | 12 |
| F + D + H + one batched resume per loop turn | 2.9 | 1.0 | 0.0 | 45 | 78 | 12 |
| F + I | 3.0 | 2.0 | 0.0 | 75 | 102 | 12 |
| floor: callback echo on the loop, no coroutine | 2.0 | 1.0 | 0.0 | 30 | 34 | 11 |
| Netty, handler on the event loop | 1.0 | 2.0 | 0.0 | 44 | 42 | 10 |
| Netty, handler on an `EventExecutorGroup` | 3.0 | 4.0 | 0.0 | 56 | 59 | 11 |

What the rows say:

- **The cost is the hand-offs, not the coroutines.** B (handler on the loop) halves the wake-ups of A (4.0 -> 2.0), removes both pool dispatches and halves p50 and CPU, and costs nothing over the callback floor once continuations are lean (B + H p50 25, 26 us CPU against the floor's 30 and 34: the floor is the same code without a coroutine; the difference is noise).
- **Netty on the loop** pays 1.0 wake-up but 2.0 selector wake-ups and 2.25 reads (it reads again at the end of a read batch); the lab's loop does 2 reads. Netty's off-loop handler (the like-for-like of this library's rule) pays **3.0 wake-ups and 4.0 selector wake-ups**, p50 56 us and CPU 59 us on the same machine minutes apart.
- **C (run until the first suspension)** equals B for an echo (the handler never suspends past its read) and gives up the benefit as soon as the handler does suspend (next table).
- **F (an owned single handler thread, spin-then-park)** is worse than the shared pool at low concurrency (p50 87 us, CPU 124 us): the 30 us spin is shorter than the gap between requests, so it burns CPU and then parks anyway. It earns its place only under load (§5.5), and must be adaptive or absent at low rates.
- **D (caller-runs)** saves one selector wake-up and one dispatch (3.0/1.0/1.0) with no change in p50 beyond noise at a single connection; its value shows at scale (§5.5).
- **I (queued write)** takes 4.0 -> 3.0 wake-ups on the pool path.

### 5.2 (b) A handler that waits on something asynchronous

| option (lab, TCP, handler awaits a service thread before replying) | wake-ups | selector wake-ups | pool dispatches | p50 us | CPU us/op |
|---|---|---|---|---|---|
| A status quo: handler on shared pool, write submitted to loop | 5.9 | 2.0 | 3.0 | 56 | 65 |
| H lean continuations (A + reusable continuation, inline fast path) | 6.0 | 2.0 | 3.0 | 44 | 54 |
| I queued write (A + non-suspending write) | 5.0 | 2.0 | 2.0 | 62 | 68 |
| B handler on the loop (coroutine on the loop dispatcher) | 3.0 | 2.0 | 0.0 | 37 | 42 |
| B + H | 3.0 | 2.0 | 0.0 | 35 | 38 |
| B + H + hand-off watchdog | 3.0 | 2.0 | 0.0 | 33 | 33 |
| C run-until-first-suspension (then pool) | 4.0 | 2.0 | 1.0 | 35 | 36 |
| D caller-runs write on the handler thread | 5.0 | 1.0 | 2.0 | 37 | 43 |
| F owned single handler thread, spin-then-park | 4.0 | 2.0 | 0.0 | 85 | 118 |
| F + D | 3.9 | 1.0 | 0.0 | 61 | 95 |
| F + D + H | 3.8 | 1.0 | 0.0 | 54 | 94 |
| F + D + H + one batched resume per loop turn | 3.8 | 1.0 | 0.0 | 52 | 93 |
| F + I | 3.8 | 2.0 | 0.0 | 45 | 92 |
| floor: callback echo on the loop, no coroutine | 2.0 | 1.0 | 0.0 | 53 | 50 |
| Netty, completion callback on the event loop | 2.0 | 4.0 | 0.0 | 48 | 50 |
| Netty, handler on `EventExecutorGroup` blocks on the service | 5.0 | 4.0 | 0.0 | 110 | 104 |

The handler awaits a one-thread "service" that completes it from another thread. Netty's two honest forms: a callback that writes from the service thread (2.0 wake-ups and 3.95 selector wake-ups, p50 48 us; the consumer writes callbacks) and a blocking handler on an `EventExecutorGroup` (5.0 and 4.0, p50 110 us: the cost of a thread-per-wait style). With the handler on the loop (B) and a suspend `await`, the coroutine form costs 3.0 wake-ups and 2.0 selector wake-ups, p50 35 us: the same total as Netty's callback form, in straight-line code. **C loses here**: after the first suspension the completion resumes on the pool (4.0 wake-ups, 1 dispatch).

### 5.3 (e) Clients

See §4 for the table.

### 5.4 The real QUIC library, and Netty QUIC, same harness

**Quiet machine** (a quiet Linux x86_64 machine, JDK 25, our own `libquiche` built from `main`; 4 interleaved rounds, each run about 20 000 operations after 5 000 of warm-up, each started with foreign load under 0.2 cores; counts are from the laptop):

| row (quiet Linux x86_64 machine, JDK 25, loopback, 64 B echo, `runBlocking` caller; median of 4 interleaved rounds, min-max in brackets; machine idle at every start: foreign busy cores under 0.2) | wake-ups (laptop JFR) | p50 us | p99 us | CPU us/op (process CPU time) | CPU us/op, longer separate run (80k or 300k ops) | bytes/op |
|---|---|---|---|---|---|---|
| this library, `main` (with #886) + #888 | 5.98 | 203 (200-226) | 342 | 187 (184-198) | not run | 19524 |
| + E (ACK hold, adaptive rule) | 6.00 | 226 (207-236) | 403 | 159 (148-168) | not run | 13704 |
| by-construction: + E + 4 owned handler threads (spin 30 us) | 3.01 | 217 (202-217) | 397 | 165 (160-178) | 156 | 13621 |
| by-construction, owned threads park immediately (spin off) | - | 224 (198-228) | 425 | 140 (134-142) | 113 | 13522 |
| + B (handler on the loop), no hold | 2.00 | 211 (184-218) | 353 | 131 (118-138) | 132 | 18206 |
| Netty QUIC, handler on the event loop | 1.00 | 134 (128-149) | 254 | 66 (64-69) | 66 | 2151 |
| Netty QUIC, handler on an `EventExecutorGroup` | 3.00 | 157 (147-168) | 294 | 101 (87-103) | not run | 3036 |

The `jdk.CPUTimeSample` profiled runs produced no samples (the event was not enabled by the option form used), so the CPU column in the profiled position is instead the process CPU of a longer separate run, which agrees with the rounds. B + E was not part of the quiet block (it was cut to keep the machine hold short), so it has laptop numbers only, below.

**Laptop** (load 6 to 8, one run each; counts are load-proof, timings are not):

| row (laptop, load 6 to 8, one run each; counts are load-proof, timings are not) | wake-ups | selector wake-ups | pool dispatches | p50 us | p99 us | CPU us/op | bytes/op |
|---|---|---|---|---|---|---|---|
| this library, `main` (with #886) + #888 | 6.0 | 1.7 | 2.0 | 62 | 94 | 102 | 19945 |
| + E (ACK hold) | 6.0 | 2.0 | 2.0 | 52 | 104 | 73 | 13879 |
| + B (handler on the loop) | 2.0 | 0.9 | 0.0 | 50 | 105 | 72 | 19827 |
| + B + E | 2.0 | 1.0 | 0.0 | 42 | 100 | 51 | 13815 |
| + E + 4 owned handler threads (no B): the by-construction path | 3.0 | 2.0 | 0.0 | 49 | 111 | 95 | 13686 |
| Netty QUIC, handler on the event loop | 1.0 | 1.6 | 0.0 | 40 | 118 | 47 | 2235 |
| Netty QUIC, handler on an `EventExecutorGroup` | 3.0 | 2.4 | 0.0 | 43 | 85 | 63 | 3300 |

[M] counts (laptop, JFR); timings and CPU as the table's header says. **B** is a prototype change in `SharedQuicheServer.connections` that launches the handler on the connection's loop (`ExecPrototype.handlersOnLoop`); the handler's stream writes still reach the driver through its command channel and are served on its next turn. **E** is the ACK hold of §6.5 (a fixed 300 us setting; see §6.5 for what that setting really is). **E + owned executor** is the purely by-construction path of §6.10: the handler stays off the loop, on four owned spin-then-park threads (round-robin per connection), with E. Counts per echo are the same for every caller (`blocking`, `default`, `main`) to within 0 to 2 pool dispatches.

### 5.5 (c) Many connections

| 64 B echo, N connections, 1 server loop/thread (laptop) | N | ops/s | server CPU us/op | server bytes alloc/op | threads |
|---|---|---|---|---|---|
| A status quo | 256 | 65,551 | 30.0 | 341 | 30 |
| H | 256 | 77,055 | 27.6 | 261 | 30 |
| B + H | 256 | 213,330 | 4.7 | 45 | 12 |
| B + H + watchdog | 256 | 197,620 | 5.1 | 45 | 13 |
| D | 256 | 131,268 | 18.9 | 125 | 30 |
| F | 256 | 183,164 | 10.8 | 389 | 13 |
| F + D | 256 | 172,357 | 11.1 | 153 | 13 |
| F + D + H + batched resume | 256 | 186,719 | 9.9 | 74 | 13 |
| F + I | 256 | 202,677 | 6.9 | 117 | 13 |
| floor (callback on loop) | 256 | 222,165 | 4.5 | 45 | 12 |
| Netty on-loop | 256 | 241,842 | 4.2 | 184 | 11 |
| Netty `EventExecutorGroup`(4) | 256 | 151,157 | 13.1 | 469 | 15 |
| this library TCP (main, NIO2) | 256 | 78,255 | 201.5 | 2182 | 337 |
| A status quo | 1024 | 115,997 | 18.7 | 343 | 30 |
| H | 1024 | 117,934 | 18.4 | 263 | 30 |
| B + H | 1024 | 259,648 | 3.8 | 47 | 12 |
| B + H + watchdog | 1024 | 252,871 | 4.0 | 47 | 13 |
| D | 1024 | 187,170 | 13.5 | 127 | 30 |
| F | 1024 | 244,064 | 8.2 | 392 | 13 |
| F + D | 1024 | 290,711 | 6.9 | 152 | 13 |
| F + D + H + batched resume | 1024 | 289,636 | 6.8 | 72 | 13 |
| F + I | 1024 | 236,833 | 5.7 | 119 | 13 |
| floor (callback on loop) | 1024 | 252,900 | 3.9 | 47 | 12 |
| Netty on-loop | 1024 | 233,840 | 4.3 | 186 | 11 |
| Netty `EventExecutorGroup`(4) | 1024 | 126,791 | 14.8 | 471 | 15 |
| this library TCP (main, NIO2) | 1024 | 86,356 | 177.6 | 2159 | 440 |

[M] on the laptop; ops/s are loaded-machine numbers, the CPU and allocation columns are what to read. The load generator is the same in every row (four loops of callback clients), and "server CPU" subtracts the generator's thread CPU from the process CPU.

- **On the loop wins at scale** (B + H: 3.8 us and 47 B per op at 1024 connections against A's 18.7 us and 343 B, and Netty's 4.3 us and 186 B).
- **Off the loop is where an owned executor with batched resumes and caller-runs earns its keep** (F + D + H + batched resume: 6.8 us, 72 B at 1024 against Netty's `EventExecutorGroup` 14.8 us, 471 B, and A's 18.7 us, 343 B). Batching one resume per loop turn moves the allocation from 392 B (F) to 72 B.
- **This library's TCP on `main`** (NIO2 default): 178 to 202 us and 2.2 KB per operation, **337 to 440 threads** at 256 to 1024 connections. This is the case #887's loops backend addresses ([PR]: 17 threads, 2.8 KB at one connection); it is the most expensive row in the document and a different problem from the handler model.
- The four-loop rows are in the prototype outputs; one batched-resume row crashed in the lab (a shared batch list across loops, a lab bug) and is not reported.
- **Netty QUIC with many connections and streams was not measured** [U]: the benchmark module has no multi-connection Netty QUIC shape and building one fairly (per-connection groups would not be representative) was outside this study. This library's own `QuicMultiConnectionLoad` exists.

### 5.6 The safety column

| handler blocks 100 ms (lab/Netty/this library, TCP) | neighbour on the same loop/thread: max latency | neighbour pings completed during the block (steady: ~97 per 100 ms) |
|---|---|---|
| A: handler on the shared pool | 0.3 ms | 70 |
| B: handler on the loop | 109.6 ms | 1 |
| B + watchdog hand-off | 1.8 ms | 70 |
| C: run until first suspension | 109.5 ms | 1 |
| F: one owned handler thread | 108.9 ms | 1 |
| F + D | 105.3 ms | 1 |
| F + I | 107.4 ms | 1 |
| Netty, handler on the event loop | 106.2 ms | 1 |
| Netty, `EventExecutorGroup` of 4 | 3.4 ms | 68 |
| this library TCP (main) | 0.4 ms | 64 |

[M]. A 100 ms blocking handler on one of two connections that share a loop (or a handler thread); the neighbour pings every millisecond.

- **Every design that runs a handler on a thread that also serves others stalls the neighbour for the whole block**: B, C, a single owned thread (F, F+D, F+I) and Netty's on-loop handler (106 to 110 ms). The owned single-thread executor is therefore **not a safe default for handlers**; it must not be a lone thread.
- **On the real QUIC driver** [M], two streams on one connection, one served by a handler that blocks 5 ms per request, the other measured at 64 B round trips: **140 us p50 (p99 305 us, max 0.6 ms) with the handler on `Dispatchers.IO` as today; 6463 us p50 (p99 6.9 ms, max 13.9 ms) with the handler on the loop** (prototype B, no watchdog). With the ACK hold on top the same (6434 us). The existing benchmark `QuicRoundTripBesideBlockingHandler` is the regression gate that B must keep passing.
- **A pool, an `EventExecutorGroup` of 4, and this library's TCP on `main` do not stall the neighbour** (0.3 to 3.4 ms).
- **The watchdog hand-off** (B + watchdog): the loop's watchdog notices a unit of work that has run longer than 1 ms and starts a successor thread that continues to select, run tasks and serve the other connections; the blocked thread retires when its handler returns. The neighbour's worst case was **1.8 ms** instead of 110 ms. The cost in the steady state is two volatile stores per unit of work and a watchdog thread that ticks every millisecond *only while the loop is busy* and parks when it is idle: about 7 percent of throughput at 256 connections (197.6k against 213.3k ops/s, server CPU 5.1 against 4.7 us/op) and under 3 percent at 1024 [M, noisy]. It is a prototype; its failure modes (a handler that blocks again on the successor, thread accumulation) are §7.3.

### 5.7 (f) Idle

| 1000 idle connections (laptop) | threads | RSS per connection | idle CPU ms/s |
|---|---|---|---|
| lab, handler on pool | 26 | 44 KB | 0.16 |
| lab, handler on loop | 9 | 28 KB | 0.11 |
| lab, owned handler thread | 10 | 29 KB | 0.10 |
| Netty on-loop | 8 | 21 KB | 0.15 |
| Netty `EventExecutorGroup`(4) | 12 | 26 KB | 0.25 |
| this library TCP (main, NIO2) | 195 | 572 KB | 0.65 |

[M]. RSS includes heap growth and is a delta from before the server started. This library's TCP on `main` holds **195 threads and 572 KB per idle connection**; the lab and Netty hold 8 to 12 threads and 21 to 44 KB. The cost is not the handler model (a coroutine parked in a read is a few hundred bytes); it is the NIO2 path's per-connection buffers and groups, and #887 addresses part of it [PR]. The idle QUIC case (N connections) was not measured [U].

#### 5.7.1 The TCP backends (this library, `main` against #887)

| this library TCP (laptop) | threads, 1000 idle | RSS per idle connection | threads, 1024 busy | server CPU us/op, 1024 busy | ops/s, 1024 busy | p50 us, 1 connection | CPU us/op, 1 connection |
|---|---|---|---|---|---|---|---|
| `main` (NIO2, default) | 203 | 578 KB | 549 | 227.7 | 66277 | 76 | 220 |
| #887, default NIO2 path | 176 | 568 KB | 616 | 191.6 | 72286 | 56 | 118 |
| #887, selector loops | 92 | 554 KB | 108 | 57.0 | 154739 | 31 | 46 |

[M]. `main` is the default NIO2 backend. #887 is `perf/tcp-fewer-handoffs` at the time of writing; "887 nio2" is its default path, "887 loops" is its selector-loops path (`useAsyncChannels = false`). Thread counts and RSS are load-independent; the CPU and throughput columns carry the machine's noise (§5.8 has quiet-machine rows where obtained).

What it says. The default NIO2 path is the largest gap in this document and #887's *default* path does not close it: threads 203 -> 176 at 1000 idle connections, CPU per operation at scale about the same (213 and 192 us against 164 and 228). **The loops path does**: threads 549 -> 108 at 1024 connections, server CPU per operation 228 -> 57 us, throughput 66k -> 155k ops/s, single-connection p50 76 -> 31 us. It is still about 13x Netty's on-loop server CPU (4.3 us) and its idle memory is unchanged (about 550 KB per connection in every backend, 20 to 40 KB in Netty and the lab), so the memory is not the backend: heap in use is 15 to 23 MB for 1000 connections, so it is native memory (direct buffers) per connection. [I: cause not isolated; `NativeMemoryTracking` would show it.]

**Does this argue for changing the default JVM TCP backend?** The evidence argues for it on CPU, threads and latency; it does not yet argue for it on safety of the change. What would have to be shown first: (1) the three timeout contracts (`RFC_READ_TIMEOUT_CONTRACT`, `RFC_WRITE_TIMEOUT_CONTRACT`) and the TCP conformance suites green on the loops path for the platforms that would switch (JVM 11 to 25), not only the platforms that already use it (Android below API 26); (2) TLS (`SSLClientSocket` over non-blocking channels), half-close and close races, IPv6 and Happy Eyeballs on it; (3) tail behaviour (p99 and p99.9 with 1000 connections and a slow consumer: one slow connection must not stall a loop, which is the handler-placement question again for TCP); (4) the per-connection native memory explained and, if it is the read buffer, bounded; (5) no regression for the 1-connection and 2-connection cases that NIO2 serves today. Until (1) and (3) are shown, recommendation: **keep NIO2 the default and make the loops backend the first thing the TCP execution-model work ships**, then flip the default in a release of its own.

### 5.8 Quiet-machine timings

A quiet window was arranged. The job took the lock once for the whole block (verified trap, released at the end), started with the machine idle (foreign busy cores 0.00 to 0.18, load 1 to 2 from the previous job's decay), ran 4 interleaved rounds of the seven rows, then profiled long runs, and stopped itself at a round boundary when a foreign process appeared (busy 2.0) before the last Netty run. Per-step foreign busy cores before every timed run: 0.00 to 0.18. Everything in §5.4's quiet table, and only that, is quiet-machine data. What the block did **not** contain, so is still laptop data: every wake-up count; B + E; Netty's `jdk.CPUTimeSample` profile; bulk; safety; idle; many connections; TCP; the hold-setting sweep.

**What the quiet numbers changed** (they are the headline of revision 3):
1. **The near-parity seen on the laptop does not hold on Linux.** The handler-on-the-loop row (B, no hold) is p50 211 us and CPU 131 us against Netty's on-loop form at 134 us and 66 us: **57 % slower at p50 and twice the CPU**. Against Netty's `EventExecutorGroup` form (157 us, 101 us) it is 34 % and 30 % worse. The `main` baseline is 203 us and 187 us. So B alone moves the baseline from 203 to 211 us at p50 (no gain on this machine) and from 187 to 131 us in CPU (-30 %).
2. **E (the ACK hold, adaptive rule) lowers CPU 15 % (187 -> 159 us) and raises p50 11 % (203 -> 226 us)** on this machine, with a 99.99 % hit rate. The laptop showed a gain in both. Why p50 rises is not known [U]; candidates are the flush now happening after the handler's turnaround rather than before it, and a different wake-up pattern under Linux's eventfd. D3's recommendation is weakened accordingly (§10).
3. **The by-construction path (E + four owned threads)**: p50 217 us, CPU 165 us. With the owned threads' spin turned off (park immediately) CPU drops to **140 us** (-15 %) and p50 stays 224 us: the spin accounted for about 25 us of the gap, not most of it. Without the spin artifact the by-construction path is **39 % more CPU and 43 % more p50 than Netty's `EventExecutorGroup` form** (140 vs 101 us, 224 vs 157 us) and 2.1x Netty's on-loop CPU.
4. **Netty is much faster than the laptop suggested**: on-loop 134 us and 66 us, bytes per op 2.2 KB. The remaining gap is therefore not primarily wake-ups: the library's B row has 2.0 wake-ups against Netty's 1.0 but costs 2x the CPU. What is left is the library's own work per echo (the allocation of 18 KB against 2.2 KB; the driver's turns and `quiche_conn_send` calls that #888 reduced but Netty does not pay; 4 datagrams against Netty's 3.6 [PR]), none of which the execution model addresses. [I, not profiled: the CPU samples were not obtained.]


## 6. The options

Each option is stated, then measured in the lab or the real driver, then given a safety class: **by construction** (the property holds whatever the handler does), **by detection** (it holds after the fact, bounded), or **not at all**.

### 6.0 Summary

| option | what it does | measured effect (echo, wake-ups; lab unless noted) | cost / risk | does not apply | safety property |
|---|---|---|---|---|---|
| A | status quo after #886/#888 | 4.0 (lab); 6.0 real library (after #886) | none | | by construction |
| B | handler as a coroutine on the connection's loop | 4.0 -> 2.0 lab; **6.0 -> 2.0 real QUIC** | a blocking handler stalls the loop (real QUIC: neighbour round trip 140 us -> 6.5 ms) | Native: lane is per connection, no loss | **not at all** alone; **by detection** with the hand-off watchdog (1.8 ms bound) |
| C | resume inline on the loop to the next suspension, then pool | 2.0 for an echo; 4.0 once the handler suspends | after the first suspension nothing is enforced | | not at all (the first run segment stalls the loop: 109.5 ms) |
| D | write executes on the caller's thread by CAS ownership | 4.0 -> 3.0, one selector wake and one dispatch fewer | a syscall on the caller's thread | any caller we do not own (Android main) | by construction **only on library-owned threads** |
| E | hold the ACK-only flush a short bounded time so the reply shares its datagram | **real, quiet Linux: CPU 187 -> 159 us (-15 %) but p50 203 -> 226 us (+11 %); laptop: CPU 102 -> 73, p50 62 -> 52; sim: datagrams per echo 4.5 -> 3.6** | tail risk on lossy paths (measured: none beyond noise) | | by construction (bounded by the hold; clean path only) |
| F | owned handler threads, spin-then-park, one batched resume per loop turn | worse than the pool at low rate; 6.8 us/op and 72 B at 1024 connections | a lone owned thread stalls neighbours (109 ms) | | by construction only with several threads |
| G | kernel batching (`sendmmsg`/GSO/`recvmmsg`, `nw_connection_batch`) | not measured [U] | platform code | Linux io_uring and Apple only | n/a |
| H | lean continuations: no `select`, no `CompletableDeferred`, inline fast path | server alloc 341 -> 261 B/op at scale, 45 B on the loop | invariants at the cancellation edge | | by construction |
| I | write accepted into the send queue, returns without suspending | 4.0 -> 3.0 (pool path); client 2.0 -> 1.0 | semantic change: "accepted", not "sent"; a failure surfaces later | | by construction |

### 6.1 A — the status quo (baseline)

After #886 (timers) and #888 (turns) the real library is at 6.0 wake-ups, 2.0 pool dispatches and 19.9 KB per echo [M]; before #886 it was 10.0 [M, revision 1]. The six are attributed in §3.2. A is main-safe and loop-safe by construction and loses on every hand-off row.

### 6.2 B — the handler runs on the connection's loop, with enforcement

Measured twice. In the lab (TCP): 4.0 -> 2.0 wake-ups, p50 44 -> 25 us, 62 -> 25 us CPU. In the real QUIC driver (prototype: one line at `SharedQuicheServer.connections` plus an accessor): **6.0 -> 2.0 wake-ups, 2.0 -> 0 pool dispatches, laptop CPU 102 -> 72 us, p50 62 -> 50 us**, nothing else changed (revision 1, before #886: 10.0 -> 4.0). The remaining difference to Netty is the allocation and the caller's second resume.

The loop already is a dispatcher in this codebase (the driver is a coroutine on it), so B is not a new mechanism; it is a different `launch` context. What it breaks is the loop rule, so it needs enforcement, ordered by strength:

1. **Debug `LoopGuard`** (exists): asserts connection state is touched only in the loop's turn. It checks confinement, not time. Keep it.
2. **A time measure per resume** [prototype in the lab]: two `nanoTime` reads around each task the loop runs. Gives the data for promotion and demotion (§8) and costs two volatile stores per unit.
3. **The hand-off watchdog** [prototype]: bounds the damage of a handler that blocks. Detailed in §5.6 and §7.3. This is the only item that preserves the purpose of the rule for a handler that blocks *the first time*; the other two only learn from it.
4. **Demotion**: a handler that overran its budget once is resumed on the pool from then on (until a probation passes).

### 6.3 C — run until the first suspension

An echo handler never suspends past its read, so C = B for echo (2.0 wake-ups). The moment the handler awaits anything, C hands the continuation to the pool; for the async handler C costs 4.0 wake-ups and a dispatch against B's 3.0 and 0. C's first segment still runs on the loop, so a blocking first segment stalls it (109.5 ms). **C has B's risk and half of B's benefit; reject.**

### 6.4 D — caller-runs / flat combining for writes

The caller takes write ownership of the connection by CAS and performs the write syscall on its own thread when uncontended. In the lab with the handler on the pool: 4.0 -> 3.0 wake-ups (one selector wake-up and one dispatch fewer); on the client side, `callerruns` removes the loop wake entirely (client echo: 1.0 wake-ups, 0 selector wake-ups). For quiche (a single-threaded connection object) this is **not** available for the QUIC data path: `quiche_conn_send` and the stream sends mutate the connection and must stay on the loop; D applies to TCP and to UDP socket writes only. [I from the driver: all quiche calls are confined by `LoopGuard`.] On Android main it is a network syscall on the UI thread; excluded. Fairness: a thread that keeps winning the CAS starves the loop's own writes; bound the number of consecutive caller-runs. Native: same as JVM for TCP. **Restricted to threads the library owns (the handler executor), D is safe by construction; applied to arbitrary callers it is not, and cannot be made so because the library cannot tell which dispatcher is a UI one** (it can only identify `Dispatchers.Main` and the desktop EDT).

### 6.5 E — the piggybacked ACK

**Mechanism** (prototype, `proto/v2`): on a turn that only received datagrams, handed bytes to a waiting reader, on an established, non-migrating connection whose `AckCadence` is `Relaxed` (no loss evidence), skip that turn's flush and arm a loop wake; any command that arrives meanwhile (the handler's `StreamSend`) flushes, so the ACK, the flow-control credit and the reply leave in one datagram; at expiry the loop flushes. quiche has no ACK timer; the cadence is ours.

**What the 300 us setting really was (correction of revision 1).** The hold's expiry is a loop wake armed through `select(timeout)`, whose timeout the loop rounds **up to whole milliseconds** (`ceilMillis` in `DatagramEventLoop`). Holds of 100, 200, 300 us and of `srtt/8` therefore all behaved as roughly 1 ms [I from the code, consistent with M: hit rates and results are identical across them]. The measured gain was real, but it was never a gain of a 200 us hold. The consequence for the formula `min(200 us, srtt/8)` of revision 1: on loopback and a LAN it would have worked **only because of the rounding**, which is an accident to design around, not on.

**On the quiet machine the hold's effect differs from the laptop's:** CPU per echo 187 -> 159 us (-15 %) and p50 203 -> 226 us (+11 %), with a 99.99 % hit rate (§5.8 item 2). **Real loopback, hold settings** (laptop, load 6 to 8, two rounds; counts of holds that ended by a command (hits) against by the timer (expired), 80 000 holds per row):

| hold setting (real library, laptop, 2 rounds) | p50 us | CPU us/op | holds | hits | expired |
|---|---|---|---|---|---|
| hold0 | 62 | 113 | 0 | 0 | 0 |
| fixed100 | 57 | 84 | 79990 | 79988 | 2 |
| fixed200 | 59 | 88 | 79996 | 79994 | 2 |
| fixed300 | 66 | 95 | 79994 | 79992 | 2 |
| fixed1000 | 82 | 110 | 79965 | 79963 | 2 |
| rttdiv8cap200 | 68 | 91 | 79987 | 79754 | 233 |
| adaptive300 | 57 | 85 | 79933 | 79931 | 2 |
| E300+owned4 | 50 | 95 | 79995 | 79993 | 2 |
| owned4only | 60 | 125 | 0 | 0 | 0 |
| E300+B | 42 | 52 | 79984 | 79982 | 2 |
| netty-on | 41 | 50 | 0 | 0 | 0 |
| netty-off | 43 | 62 | 0 | 0 | 0 |

Hit rate is 99.99 % for every fixed setting and 99.6 to 99.9 % for `srtt/8` capped at 200 us, i.e. on this path the handler always replied inside one timer tick. The throughput and CPU differences between settings in the table are inside the machine's noise (the round-2 rows were slower for every setting).

**What bounds the cost when the handler does not reply.** A held ACK costs, per held turn that the timer ends: one loop wake (the timer), and an ACK delayed by the hold. Three bounds, in increasing strength:
1. *The delay is small against the protocol's allowance and the sender corrects for it.* The hold is at most one timer tick (1 ms), against `max_ack_delay` 25 ms (RFC 9000 13.2.1) and a PTO that already includes it (RFC 9002). **Verified in the sim** (virtual time, 20 ms one way, handler replying after 5 ms so every hold expired, 39 expiries): the client's smoothed RTT, min RTT and RTT variation are identical with and without a 2 ms hold (40 ms, 40 ms, 112 ns), so quiche reports the real ack delay and the sender subtracts it; the hold does not inflate the peer's RTT sample [M, sim]. This replaces revision 1's "inferred".
2. *The hold stops when it stops paying* (prototype `adaptive`): after two consecutive expiries the connection stops holding for 256 eligible turns, doubling to 4096, and any hit resets it. In the sim above (handler slower than the hold) this caps the wasted wakes at the first two per backoff window instead of one per request.
3. *Pure upload.* In a bulk upload the receiver's turns are pure-receive turns that hand bytes to a parked reader, so the prototype holds on every one and its expiry is fixed by the first held turn. Measured: no regression (113 -> 120 MiB/s, CPU per MiB down); the production rule is stricter: hold only when no further datagram is queued in this pass, so a flight is ACKed on its last datagram, never delayed behind its own successors.

**A formula that works at low RTT.** `srtt/8` is the wrong shape: the handler's turnaround is set by the application and the scheduler, not by the path. The proposal, in the order it should be built:
- *With the handler on the loop (B):* **no timer at all.** Flush at the end of the loop pass in which the bytes were handed over, after the queued handler tasks have run (Netty's rule: one `connectionSend` after the read batch). The ACK waits for no clock, only for the handler's turn in the same pass; an overrunning handler is bounded by the watchdog, not by a timer.
- *With the handler off the loop:* a timer hold of **one loop timer tick (about 1 ms), gated by the hit rate** (item 2). `clamp(2 x EWMA of the observed hold-to-reply time, 100 us, 1 ms)` is the refinement once the loop has a sub-millisecond timer; the JDK selector cannot wait less than a millisecond, so below it the choice is a spin, which the design does not want.
- Never while loss evidence is present (the existing `AckCadence` memory), never with migration enabled, never in the handshake.

**Sim sweep** (unchanged from revision 1; `withMigrationSim`, 100 seeds x 30 echoes per row, handler replying instantly or after 1 virtual ms; a hold of 200 us and of 2 ms; in the sim the timer is exact, there is no millisecond rounding):

| path | hold | handler delay | datagrams per echo (handshake and close included) | echo p50 | echo p99 | failed runs |
|---|---|---|---|---|---|---|
| clean, 20 ms one way | 0 | 0 | 4.53 | 40 ms | 40 ms | 0 |
| clean | 200 us | 0 | **3.57** | 40 ms | 40 ms | 0 |
| clean | 200 us | 1 ms | 4.53 (the hold expires, no merge) | 41 ms | 41 ms | 0 |
| clean | 2 ms | 1 ms | 3.57 | 41 ms | 41 ms | 0 |
| 10 % loss, 50 ms | 0 / 200 us | 0 | 5.18 / **4.69** | 100 / 100 ms | 724 / 724 ms | 0 / 0 |
| 30 % loss, 50 ms | 0 / 200 us | 0 | 7.51 / 7.35 | 226 / 228 ms | 6994 / 8034 ms | 1 / 1 |
| bursty 15 % x 5, 50 ms | 0 / 200 us | 0 | 5.63 / **4.80** | 100 / 100 ms | 2611 / 2511 ms | 0 / 0 |

The hold removes about one datagram per echo on clean and bursty paths and nothing when the handler is slower than the hold. On the 30 % loss path `AckCadence` leaves `Relaxed` almost at once, so the hold rarely applies (397 holds in 3000 echoes); the p99 movement there is within what 30 tail samples can produce, and the failed run is the same seed in every row. Not tested: a real asymmetric path, ACK compression, a peer that is not quiche. [U]

**Bulk** [M, laptop, noisy]: 16 MiB per stream in 64 KiB writes, median of 10 streams: see §5.8 for the quiet-machine rows.

### 6.6 F — a cheaper hand-off rather than fewer

An owned handler thread (spin 30 us then park) fed by one task per loop turn. Measured: at low concurrency it is **worse** than the shared pool (wake-ups 3.1 vs 4.0, but p50 87 vs 44 us and CPU 124 vs 62 us on this machine: the spin does not cover the gap between requests, then parks). At scale it is the cheapest way to keep a handler off the loop (§5.5: 6.8 us and 72 B per op at 1024 connections with D and lean continuations and batched resumes, against the pool's 18.7 us and 343 B and Netty's `EventExecutorGroup` 14.8 us and 471 B). **A lone owned thread is unsafe for blocking handlers (§5.6)**; as designed here it needs more than one thread plus the watchdog, and an adaptive spin. It must remain a default that a consumer can replace: the handler's `CoroutineDispatcher` stays theirs to supply.

### 6.7 G — batching across operations

Not prototyped. [I] Netty's NIO transport on macOS and Windows does none of it; its Linux epoll transport uses `recvmmsg` and GSO. The lab's "one resume per loop turn" (Fb, §5.5) is the coroutine-level analogue and is worth 392 -> 72 B per op at 1024 connections. For the kernel level, this library's Linux io_uring path can batch sends and receives per ring submit and Apple's C shim can use `nw_connection_batch`; the gain is a bulk and many-connection gain, not an echo one. It needs its own gate (the bulk rows of #880 and the migration sims: `perf/quic-bulk-batching-wip` showed flush batching breaking two migration sims, not root-caused).

### 6.8 H — what a suspension fundamentally costs, and how low it goes

Lab, per echo on the loop: **45 B** (B + H, identical to the callback floor), and 261 B with a pool hand-off (H) against 341 B without lean continuations (A). The three ingredients: `suspendCoroutineUninterceptedOrReturn` with a single-slot state machine instead of `suspendCancellableCoroutine` (saves the `CancellableContinuationImpl` per park), an inline fast path that returns the bytes without suspending when they are already there, and no `Result` boxing. A dispatch to another thread costs a `DispatchedContinuation` and a `Runnable` whatever you do. What this does **not** reach is the real library's 13 to 20 KB: that is commands, deferreds and buffer handling in the driver, outside the execution model.

### 6.9 I — a write that returns when accepted (not in the owner's list)

The write is queued into the connection's send queue and returns immediately unless the queue is full (backpressure suspends it). Measured: the status-quo pool path 4.0 -> 3.0 wake-ups; the client 2.0 -> 1.0, with the same single loop wake Netty's `writeAndFlush` pays. It removes the second hand-off for every caller, including the Android main thread, and costs no syscall on the caller's thread. **It changes a contract**: `RFC_WRITE_TIMEOUT_CONTRACT.md` defines `write(deadline)` as bounded until the bytes are accepted by the transport; "accepted into the send queue" is a weaker point on the same line, as it is for Ktor and for a Netty `write` without a listener. A failure surfaces on the next operation or at close. This is the owner's decision (D2).

### 6.10 What the off-loop path reaches on the real library, without B

The owner's question before deciding D1 (the by-construction design: the handler never runs on a thread that serves others). On the real QUIC library, `main` (with #886) + #888 + E + an owned handler executor, no B [M, laptop counts; §5.4 has the timings]:

| real library, handler off the loop | wake-ups | pool dispatches | what it is |
|---|---|---|---|
| `main` + #888 (`Dispatchers.IO`) | 6.0 | 2.0 | the baseline |
| + E | 6.0 | 2.0 | the ACK hold alone |
| + E + 4 owned spin-then-park handler threads | **3.0** | 0.0 | **equals Netty's `EventExecutorGroup` form (3.0)** |
| + B + E (handler on the loop) | 2.0 | 0.0 | the on-loop design |
| Netty, `EventExecutorGroup` / on the loop | 3.0 / 1.0 | 0 | |

What this path **cannot** include on the real library, and why:
- **D (caller-runs writes) does not apply to QUIC**: quiche is single-threaded and every stream send is a command the loop executes (§6.4).
- **I (queued writes) does not apply without a copy.** The QUIC write is zero-copy: `streamWrite` hands the caller's native buffer to the driver, and its own comment says the caller frees the buffer the instant the call returns, which is why cancellation must wait for the in-flight send. A write that returns before the loop has read the buffer would need the library to copy the bytes (into library-owned native memory) or to take ownership of the buffer; either is a contract change beyond D2, and the copy has a cost the lab's queued write (which reuses the buffer) did not pay. On TCP the same applies to the lab's numbers. So D2 is a bigger decision than revision 1 said.
- **H (lean continuations)** is not implemented on the real driver; its effect there is [I].
- **Batched resumes** (F's per-loop-turn release) are not wired into the real server; wake-ups would not change for one connection, and the allocation effect appears only at many connections.

Result on the real library at one stream, **quiet machine** (§5.4): E + four owned threads is p50 217 us and CPU 165 us; with the spin turned off (park immediately) p50 224 us and CPU 140 us. Netty's `EventExecutorGroup` form is 157 us and 101 us, its on-loop form 134 us and 66 us; the pool-based baseline is 203 us and 187 us. The spin explains about 25 us of the CPU gap, **not most of it**: the by-construction path without the artifact still costs 39 % more CPU and 43 % more p50 than Netty's matching form, and 2.1x Netty's on-loop CPU. Its wake-up count (3.0, laptop JFR) equals Netty's executor-group form (3.0), so the remainder is the library's own per-echo work (§5.8 item 4), not hand-offs. (The laptop run had suggested p50 within 15 % and CPU 95 vs 63 us; superseded.)

## 7. Safety: the letter, the purpose, and the enforcement ladder

### 7.1 The rule, by its purpose

"The transport never runs user code on its loop" exists so that a consumer cannot stall the transport or other connections. It is satisfied *by construction* if user code is never on a thread that serves others. It is satisfied *in purpose* if user code may be on such a thread but a stall is detected and bounded by something other than the user's own good behaviour.

### 7.2 What each design guarantees (measured, §5.6)

By construction: A, a pool, an `EventExecutorGroup` of several threads, this library's TCP on `main`. Not at all: B, C, a lone owned thread, Netty's on-loop handler. By detection with a bound: B with the hand-off watchdog: the first overrun stalls others for about the watchdog's threshold (1 ms threshold, 1.8 ms measured neighbour worst case), then never again for that handler if it is demoted.

### 7.3 The hand-off watchdog on the real QUIC driver

Revision 1 said "at most one thread selects". That is not the invariant that matters. The invariant is: **at most one thread is inside a connection's turn, and a library turn is never interrupted by a hand-off.** What follows is from reading the driver and the loop (`QuicheDriver.run`, `DatagramEventLoop.dispatch`, `LoopGuard`); it is a design, not a measurement, and the lab's watchdog (TCP) does not exercise any of it.

**Where a handler actually runs.** Under B the handler is a *separate coroutine task* launched on the loop, not code inside the driver's turn. The driver is one coroutine; its turn (`afterCommand` and the commands it dispatches) is atomic with respect to every other task on the loop, because the loop runs one task at a time, and `DatagramEventLoop.dispatch` always queues (it never runs a resume inline), so a handler is never entered from inside a driver turn. A handler touches connection state only through channels and commands (`driver.commands.trySend`, a slot's signals), which are thread-safe and are what handlers on `Dispatchers.IO` use today. So the handler task is the only thing a watchdog needs to preempt, and library code inside the handler's call tree (`DriverStreamAdapter`) is already thread-agnostic.

**The hand-off.** The watchdog times *handler tasks only* (the dispatcher wrapper that launches handlers on the loop brackets each task with a start stamp). When a handler task has run longer than the threshold:
1. A successor thread becomes the loop's owner (new generation): it owns the selector, the task queue and the timer heap from now on. The queue and heap are shared across generations, not per thread; the loop's `isLoopThread()` identity is re-pointed to the successor.
2. **Which thread may touch the quiche connection, the stream slots and the driver's turn state?** Only the loop's current owner thread, running a driver turn. The blocked old thread is inside a *handler* task, so it holds none of that state. It may call thread-safe channel operations and nothing else; if it submits a task (`dispatch`), the loop sees a thread that is not the owner, takes the off-loop path and wakes the successor (correct, as for any off-loop caller).
3. **When the blocked handler returns on the old thread.** Its coroutine suspends at its next library call (a `read`), control returns to the old thread's loop run method, which checks its generation against the loop's, finds it stale and **exits without running any task**. It never "resumes into library code that believes it owns the turn", because the old thread owns nothing and the only code it runs after the handler is the handler's own continuation up to the next suspension (which resumes through the dispatcher, i.e. through the queue and the new owner).
4. **A library turn that overruns is not handed off.** If the overrunning task is the driver's own (a bug: library code blocked), a successor would run another task of the same connection while the old thread is still inside its turn, which violates the invariant; `LoopGuard` already reports that class as a violation. The watchdog does not act on library tasks; it reports.

**What still has to be proved** (before D1 can be answered yes): (a) the dispatcher wrapper cannot be bypassed (a handler that launches `Dispatchers.Unconfined` children or uses `withContext(loop)` re-entry); (b) generation hand-over is race-free against a handler that unblocks at the same instant (a Lincheck model of {owner, successor, blocked thread}, in `:lincheckTest`); (c) bounded generations per loop and a handler that blocks again on every thread it is moved to; (d) the timer heap and `LoopLifecycle` (a loop whose thread "exits a second after its last connection ends") under a stale generation. The lab implements (1) to (3) for TCP and passed one scenario (neighbour 1.8 ms); that is not a proof.

**A handler that is fast a thousand times and then blocks once** (a cache miss). By promotion-by-history it is on the loop; the first slow resume stalls every neighbour for the threshold (1 ms configured, 1.8 ms measured) and demotes it. After the probation it is promoted again and, if the cache miss recurs, stalls the neighbours again. Expected stall frequency: with probation doubling from `P0` to a cap `Pmax`, a handler that blocks on a fraction of its resumes causes at most `log2(Pmax/P0) + T/Pmax` stalls per hour, i.e. with `P0` = 30 s and `Pmax` = 15 min about 6 stalls in the first half hour and 4 per hour after, each costing every neighbour about 2 ms once: **roughly 10 ms of neighbour stall per handler-hour**, against 100 ms for each occurrence with no watchdog. [I, arithmetic from the proposed constants, not measured.] Whether demotion should be permanent: **permanent for the connection's handler instance is too harsh** (a connection lives for hours and the first slow call may be a start-up miss) and **never permanent is wasteful for a route that always misses**. Recommendation: demote per handler instance with the doubling probation above, and demote *per route* (every new instance of that route starts off the loop) once three of its instances have been demoted within the process's lifetime; a route override (`OffLoop`) is how a consumer states it earlier. The thresholds (budget, ceiling, `P0`, `Pmax`, three instances) are constants to tune from the resume-span telemetry the library keeps, not consumer knobs.

## 8. The recommended design

One design, built in an order (§11). Names are proposals.

1. **Loops stay platform threads, one per core, each with its own selector** (exists; no change), and the public API stays suspend-only. The loop group owns the timers (#886), so a connection arms none.
2. **Handler placement is `Auto` by default** and decided per handler (per accepted connection's handler coroutine, and per stream handler where the API has one), from what is known:
   - **Library handlers** (HTTP/3 static routes, relays, `pump`-style forwarders, the interop echo) are on the loop **by construction**: they are our code.
   - **User handlers start off the loop** on the handler executor (safe default), with the library measuring the time between each resume and the next suspension.
   - **A handler whose last N resumes were each under a budget (20 us proposed) is promoted to the loop**; the first resume that exceeds a ceiling (200 us proposed) demotes it, with exponential probation before it can be promoted again. This is the 0-RTT pattern: safe by default, fast when known to be safe, per-route override.
   - **The hand-off watchdog** (§7.3) bounds the first overrun of a promoted handler.
   - **Override** per route/stream in both directions: `HandlerPlacement.OffLoop` (never promoted; for handlers known to block) and `HandlerPlacement.OnLoop` (never demoted below a hard ceiling; for trusted handlers; still under the watchdog).
3. **The handler executor** is owned by the loop group: **several** threads (never one), adaptive spin, one batched resume per loop turn (F), caller-runs writes on its own threads (D). A consumer may pass their own `CoroutineDispatcher`; then nothing is promoted: a coroutine the consumer placed on their dispatcher stays there (`Pooled(dispatcher)`).
4. **Writes** are queued (I) *if the owner accepts the contract change*; otherwise they stay suspending, and the library issues the result resume only when it must.
5. **Reads** use lean continuations and an inline fast path (H).
6. **ACK hold (E)** is automatic and bounded, with no knob, on a clean path only (never while loss evidence is present, never with migration, never in the handshake): with the handler on the loop **no timer at all** (flush at the end of the loop pass, after the handler's task has run); with the handler off the loop a timer hold of one loop timer tick (about 1 ms), gated by its hit rate (two consecutive expiries stop it, with doubling backoff). Revision 1's `min(200 us, srtt/8)` is withdrawn (§6.5): it worked on loopback only through the loop's millisecond rounding.
7. **Kernel batching (G)** on Linux io_uring and Apple after the rest, gated on the bulk and many-connection rows.
8. **Clients** keep resuming on the caller's dispatcher (one resume per awaited event); the client request/response helpers inside the library run on the loop.

Public API surface (proposal, reuses existing types): `ReadPolicy`/`WritePolicy` unchanged; one new sealed interface used where a handler is installed:

```kotlin
sealed interface HandlerPlacement {
    data object Auto : HandlerPlacement                    // default
    data object OffLoop : HandlerPlacement                 // never promoted
    data object OnLoop : HandlerPlacement                  // trusted; still watchdogged
    class Pooled(val dispatcher: CoroutineDispatcher) : HandlerPlacement  // consumer's own dispatcher; nothing is moved
}
```

No global policy switch; no per-process setting; `SocketOptions` and `QuicOptions` do not change.

## 9. Per row: beat, match or trail

Expected, given the design, marked by evidence. "Beat/match/trail" is against Netty's best form for the row. Timing claims are contingent on §5.8.

| row | expectation | evidence and reason |
|---|---|---|
| (a) echo, wake-ups | **match after B + I (1.0 vs 1.0); trail by one without I (2.0 measured with B, after #886)** | [M] 4.0 now with B; two caller resumes + two timers explained [M]; the rest [I] |
| (a) echo, CPU, p50 (design with B) | **trail Netty's on-loop form (p50 +57 %, CPU 2x) and its `EventExecutorGroup` form (+34 %, +30 %)** | [M, quiet machine] B: 211 us / 131 us; Netty on loop 134 / 66, executor group 157 / 101. B + E has laptop numbers only (p50 42 vs 40 us, CPU 51 vs 47 us at load 6 to 8), which the quiet machine contradicts for B alone and which is therefore not relied on |
| (a) echo (the by-construction design, no B: E + owned executor) | **match Netty's `EventExecutorGroup` form on wake-ups (3.0 vs 3.0); trail it on p50 (224 vs 157 us, +43 %) and CPU (140 vs 101 us, +39 %, spin off); trail the on-loop form (2.1x CPU)** | [M, quiet machine] §6.10; wake-ups [M, laptop] |
| (a) echo, bytes allocated | **trail** (13 KB vs 2.3 KB) | [M] the execution model does not fix it; §3.2 |
| (b) waiting handler | **beat Netty's off-loop form**, match its callback form | [M] lab: coroutine on loop 3.0 wake-ups p50 35 vs Netty callback 2.0, p50 48; vs `EventExecutorGroup` blocking 5.0, p50 110 |
| (b) blocking handler, safety | **beat the on-loop form (110 ms stall -> 1.8 ms), match the `EventExecutorGroup` form (0.3-3.4 ms)** | [M] §5.6, with the watchdog prototype only in the lab |
| (c) many connections, CPU/op, bytes | **match Netty's on-loop form** (B), **beat its `EventExecutorGroup` form** (F+D+H+batch) | [M] lab: 3.8 us / 47 B vs 4.3 us / 186 B; 6.8 us / 72 B vs 14.8 us / 471 B |
| (c) many connections, QUIC | **unknown** | [U] |
| (d) bulk | **unknown**; Netty on Linux has `recvmmsg`/GSO, we do not yet | G; [PR] #880 for today's numbers |
| (e) client, background dispatcher | **match** (2 wake-ups with I; 3 without) | [M] lab |
| (e) client, main-like dispatcher | **match** with I; **trail by one resume** without | [M] lab; the one resume is the write result, which a suspending write must make |
| (f) idle threads, memory | **beat** the status quo, **match** Netty for the loops path; TCP on `main` **trails** (195 threads, 572 KB/conn) until #887 or equivalent | [M] |
| safety column | **beat** Netty on-loop; **match** its executor group | [M] |
| p99 | **unknown** | [U] timings were taken on a loaded machine |

**The rows where a suspend-based, main-safe API cannot beat an on-loop callback API**, and what the consumer gets for it:

1. *Bytes allocated per operation on the write-and-wait round trip.* A suspension is a state machine and a continuation; the lab shows the floor is small (45 B) when the resume is on the loop with lean continuations, but a consumer who `await`s on a non-loop dispatcher pays a dispatch object and a task per resume that a callback does not. In exchange the consumer writes straight-line code, cancellation propagates, and no callback runs on the loop.
2. *The caller's own resume.* An API that never blocks the caller must resume it through the caller's dispatcher after the loop has the answer; Netty's best case never leaves the loop. A library cannot remove that hop; it can ensure it is the only one. In exchange the main thread is never blocked and never runs library code.
3. *Zero worst-case stall.* Netty's on-loop handler stalls others for the whole block; ours stalls them for about 1 to 2 ms the first time and never again for that handler; a pool design stalls them not at all and costs the hand-offs. A pure callback API has no way to buy that bound.

## 10. Open decisions for the owner

**D1. May a handler run on the loop, with the watchdog bounding the stall? (the letter of the rule)** Recommendation: yes, only under `Auto` (measured, promoted/demoted) or an explicit `OnLoop`, only with the hand-off watchdog, and only after the model check of §7.3 passes. Evidence: the real driver goes 6.0 -> 2.0 wake-ups and, on the laptop, 102 -> 72 us CPU with this change alone (§5.4), **and without a watchdog it turns a 5 ms blocking handler into a 6.5 ms round trip for a neighbour stream (140 us before)**, so B alone is not shippable; and on the quiet machine B is 211 us / 131 us against Netty's 134 us / 66 us, so B buys CPU (-30 % against `main`) but not p50 there, and it does not by itself bring the library to Netty (§5.8); the watchdog bounds the neighbour's worst case to 1.8 ms against 110 ms in the lab (§5.6), and has not been built on the real driver. If the answer is no, keep A with F + D + H + I; the lab says it reaches Netty's executor-group form but not its on-loop form.

**D2. Is "write returns when accepted into the send queue" acceptable as the write contract?** (Larger than revision 1 said: on QUIC and on zero-copy TCP it needs the library to copy or own the caller's buffer, §6.10.) Recommendation: yes, with errors surfacing on the next operation and at close, and a `flush`/`sync`-style suspend for callers that need "sent"; Evidence: removes one caller resume everywhere including the Android main thread (client 2.0 -> 1.0 wake-ups) and one hop on the server. Cost: `RFC_WRITE_TIMEOUT_CONTRACT.md`'s statement of "bounded until accepted by the transport" is reinterpreted, not violated, but the timeout and error vocabulary should be restated.

**D3. Is the ACK hold on by default, with no knob?** Recommendation: yes, in the form of §6.5 (no timer under B; one tick with hit-rate gating off the loop). Evidence: sim datagrams per echo 4.53 -> 3.57 on a clean path with no failure or tail effect on any of the four paths; the peer's RTT sample is unaffected by the hold (sim: 40 ms with and without a 2 ms hold); quiet-machine CPU -15 % (187 -> 159 us) **but p50 +11 % (203 -> 226 us)** (§5.8 item 2, cause unknown). Because E raises p50 on Linux, I now recommend shipping it behind the end-of-pass form under B (no timer, no added delay) and not in the timer form until the p50 rise is explained. Unknowns: a real asymmetric path and a non-quiche peer; stage it behind the nightly sim sweeps first.

**D4. Does the library own a handler executor, and how many threads?** Recommendation: yes, a small adaptive pool owned by the loop group, not a lone thread (§5.6), default a function of cores, replaceable per server by `HandlerPlacement.Pooled`. Evidence: §5.5, §5.6.

**D5. Promotion budget and ceiling** (20 us / 200 us proposed). They are guesses to be replaced by the measured resume-span distribution of real handlers (HTTP/3 routes, the interop echo, a JSON handler). Recommendation: ship them as constants with the telemetry to tune; no consumer knob.

**D6. Android and Native.** Recommendation: measure on a device and on a macOS release binary before shipping PR 5 (§11). On Native, B is natural (the lane is per connection); the open question is the per-read allocation on Apple's TCP path.

**D7. `HandlerPlacement` as the public surface** (§8) versus keeping everything internal and automatic. Recommendation: the sealed interface above, because a blocking handler needs a way to say so, and `Pooled` is how a consumer brings their own dispatcher.

## 11. Build plan

Each PR is gated by a measurement that must be committed as a test or a benchmark row; the gate is what the PR must not regress after it lands.

| PR | content | gate |
|---|---|---|
| 0 | measurement infrastructure: the benchmark shapes of this study as `benchmarks/` rows (Netty off-loop handler, waiting/blocking handler, many-connection, idle, client callers incl. a single-threaded main-like one) and a **hop-budget test** (wake-ups, dispatches, datagrams, `quiche_conn_send` per echo, extending `QuicDriverWorkPerEchoTest`) | the table in §5 reproduces; the budget test fails on a regression |
| 1 | allocation: lean continuations (H), no `CompletableDeferred`/`select` per stream operation, inline fast paths | bytes per echo (13 KB today) halved without new wake-ups; allocation row of the scoreboard |
| 2 | **E**, the ACK hold | sim sweep (clean, 10 %, 30 %, bursty): datagrams/echo down, p99 and failed runs unchanged; loopback CPU/echo; the sim's RTT-sample check (done, §6.5) kept as a test; the bulk rows |
| 3 | **B + measured promotion/demotion** on the QUIC server and TCP loops, without the watchdog but `OffLoop` default and `OnLoop` explicit only | echo wake-ups 4.0 or fewer; handler-blocks-100-ms test shows no regression for `OffLoop` |
| 4 | **the hand-off watchdog** + model check (Lincheck) + the blocking-handler safety test | neighbour max latency <= 2 ms during a 100 ms block on the real QUIC driver and TCP loops; no violation of "one selecting thread" under the checker |
| 5 | handler executor (F + D, several threads, adaptive spin), `Auto` becomes the default | many-connection rows: server CPU/op and bytes within 1.5x of the on-loop row at 1024 connections; safety row unchanged |
| 6 | **I**, queued writes (if D2 = yes) | client wake-ups 2.0 -> 1.0; `RFC_WRITE_TIMEOUT_CONTRACT` conformance suite |
| 7 | Linux kernel batching (io_uring `sendmmsg`/GSO), Apple `nw_connection_batch` | bulk and many-connection rows; the two migration sims that flush batching broke stay green |
| 8 | Native/Android confirmation | a macOS release-binary run and an Android device or emulator run of the same rows; StrictMode main-thread test |

Rationale for the order: 0 first because every later gate needs it; 1 and 2 are independent, safe by construction and reduce cost for everyone; 3 and 4 are the contentious change and are gated twice (the second gate removes the objection to the first); 5 depends on 4's executor decisions; 6 is the semantic change and waits on D2; 7 and 8 are platform work.

## 12. What is measured, what is inferred, what is unknown, and where the prototypes are

**Measured** here: all counts in §3.2 and §5 (laptop, JFR); the safety, many-connection and idle tables (laptop); the real-library effect of B and E (laptop); the sim sweeps for E; Netty QUIC and TCP in the same harness.

**Taken from PR bodies, not re-measured:** the 13 -> 10 wake-up arc to the state before #886 (the 6.0 after it is measured here), the 211 vs 65 us CPU and 251 vs 186 us p50 Linux numbers, #887's TCP counts (re-measured here in §5.7.1).

**Inferred:** the ten-to-one wake-up path once #886 and I are applied; Netty's behaviour on Linux (epoll, GSO); Native and Apple behaviour from reading `DriverPlacement.native.kt` and `NWSocketWrapper`; quiche's reported ACK delay.

**Unknown:** whatever §5.8 does not report from the quiet machine; Netty QUIC at many connections; idle QUIC; Android StrictMode behaviour of a non-blocking socket write; Native numbers; the cost of the watchdog on the real QUIC driver; whether the Lincheck model of §7.3 holds; kernel batching.

**Prototypes** (local branches in the repository of the main checkout, not pushed, not part of this PR): `proto/v2` is current (`main` at f8adf8ab + the diff of #888): the lab (`benchmarks/jvm/src/main/kotlin/com/ditchoom/socket/benchmark/lab/`, run through `LabMain`), the Netty off-loop hook in `NettyQuicBenchmarks.kt`, option E (`AckHoldPrototype`: fixed, `srtt`-divisor and adaptive modes; a hold in `afterCommand` and the loop wait), option B on the real driver (`ExecPrototype.handlersOnLoop`) and an owned handler executor on the real server (`ExecPrototype.handlerDispatcherFactory`), the sim sweeps `AckHoldSimSweepTest` (`sweep`, `ackDelayReport`), and the real-QUIC scenarios `qbulk`, `qsafety`, `nbulk`. `proto/v887` is #887's branch with the lab overlaid (backend chosen by `-Dlab.tcpbackend=nio2|nio`). `proto/ack-hold` and `proto/exec-lab` are revision 1's states (before #886). The raw rows, counts and JFR files of the study are kept with its scratch outputs.
