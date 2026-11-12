# RFC — The execution model: who runs what, on which thread, and what a hand-off costs

**Status:** **Draft, for the owner's decision.** A design study with prototypes and measurements; no product code changes in this PR. Written against `main` at 843ef080 plus the diffs of #886, #887 and #888 (open when this was written; read with `gh pr diff`, not touched).
**Builds on:** `RFC_READ_TIMEOUT_CONTRACT.md` and `RFC_WRITE_TIMEOUT_CONTRACT.md` (the deadline contracts this model must keep), `RFC_DETERMINISTIC_SIMULATION.md` (the sim used for §6.5), and the owner's standing rules quoted in §1.2.
**Companion data:** the prototypes are local branches `proto/ack-hold` (everything: the lab, option E, option B on the real QUIC driver; based on `main` + #888) and `proto/exec-lab` (the earlier lab-only state). They are not part of this PR. §12 lists what each contains.

## 0. Summary

1. A consumer measures a library by what one operation costs them: wake-ups, CPU, bytes, threads, tail latency, and what happens to everyone else when their own handler misbehaves. Netty's advantage on a 64 B echo is not QUIC or quiche (both wrap the same library); it is that its handler runs **inside the read, on the event loop**, so one request costs one wake-up and one datagram carries the reply and the ACK. This library pays a hand-off in each direction, an extra loop wake, and an ACK-only datagram.
2. The gap is **not server-only**. A client pays the same two resumes (the write's result, the read's bytes) through its caller's dispatcher; on Android's main thread one of them is irreducible, the other is a design choice (§4).
3. Measured on this laptop with the real QUIC library (main + #888): wake-ups per 64 B echo 10.0 -> **4.0** when the handler runs on the connection's loop (option B), CPU per echo 130 -> **52 us** together with the ACK hold (option E), p50 69 -> **40 us**; Netty QUIC on the same machine minutes apart: 1.0 wake-up, 63 us, p50 51 us (§5.4). The four wake-ups that remain are two timers (#886 removes them) and the caller's two resumes (a non-suspending write, option I, removes one).
4. Running the handler on the loop is only acceptable if a handler that blocks cannot stall the others. Measured with a 100 ms blocking handler (§5.6): Netty's on-loop handler stalls its neighbour **106 ms**; the prototype loop that **hands the selector to a successor thread when a unit of work overruns 1 ms** stalls its neighbour **1.8 ms** and the off-loop paths 0.3 to 3.4 ms. That is a change to the letter of "the transport never runs user code on its loop" that keeps its purpose with a bounded (about 1 to 2 ms) rather than zero worst case. **It is the owner's decision (§10, D1).**
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
| (d) bulk | one stream, large writes | **not measured in this study**; PR #880 has the numbers |
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
- **Timings** (p50, p99, CPU) in §5 were taken on an Apple-silicon laptop whose 1-minute load average was 10 to 20 throughout (other work). They show ordering and magnitude; they are not a result. CPU is process CPU time over the window divided by operations, which a loaded machine inflates for everyone. A run on a quiet Linux x86_64 machine was queued; see §5.8 for what it yielded.

## 3. The baseline, decomposed

### 3.1 What a Netty echo is

[I] from `QuicheQuicChannel` and `SingleThreadEventExecutor` in 4.2.20: the handler runs inside the read on the event loop; the write and one `connectionSend` happen after the read batch, so the reply and the ACK leave in one datagram; quiche's timeout is a task on the same loop; a wake-up is skipped when one is already pending. Per echo: one wake-up (the caller's), no hand-off, no timer, no extra datagram.

### 3.2 What an echo is here, with the real library

[PR] before #886/#888: 13 wake-ups, 3 hand-offs to the pool, 4 timers armed, 8 loop turns, 14 `quiche_conn_send`, 4 datagrams, 23 KB. [M] on `main` + #888: **10.0 wake-ups, 2.0 pool dispatches, 20 KB** (§5.4); 4 datagrams [PR]. The ten, attributed by call stack [M]:

| wake-ups per echo | cause (call stack) | removed by |
|---|---|---|
| 2.0 | the benchmark caller's two `withTimeout` bounds arm kotlinx's `DefaultExecutor` (`Delay.invokeOnTimeout`) | #886 |
| 2.0 | the loop resumes the caller (`EventLoopImplBase.dispatch`): the write's result and the read's bytes | the write's result by option I; the read's bytes cannot go |
| 2.0 | the loop hands the handler's read to `Dispatchers.IO` (`signalBlockingWork`) | option B |
| 2.0 | pool workers waking other workers while the handler runs (`signalCpuWork`) | option B |
| 2.0 | the handler's own bounds arm the `ScheduledThreadPoolExecutor` queue | option B removed it incidentally (not traced to why); #886 removes it by design |

With B alone the count is 4.0 (the first two rows), measured; with #886 on top it would be 2 [I], and with option I 1, which is Netty's one. The traces are of the benchmark's `runBlocking` caller; a caller on another dispatcher moves the first two rows to that dispatcher's own wake and changes no count.

What does not go away with any of this is the real library's per-operation allocation: 13 to 20 KB against Netty's 2.3 KB, mostly coroutine state, `CompletableDeferred`s and per-operation commands [M for the totals, I for the split]. The lab's coroutine machinery on the loop allocates 45 B per echo with lean continuations (§6.8), so most of the 13 KB is the driver's command objects and the buffer handling, not coroutines as such. That is a different work item (§11, PR 1) and the RFC does not pretend the execution model fixes it.

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
- **Netty on the loop** pays 1.0 wake-up but 2.0 selector wake-ups and 2.25 reads, (it reads again at the end of a read batch); the lab's loop does 2 reads. Netty's off-loop handler (the like-for-like of this library's rule) pays **3.0 wake-ups and 4.0 selector wake-ups**, p50 56 us and CPU 59 us on the same machine minutes apart.
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

### 5.4 The real QUIC library, and Netty QUIC, same harness, same laptop

| row (laptop, 64 B stream echo) | wake-ups | selector wake-ups | pool dispatches | p50 us | p99 us | CPU us/op | bytes allocated/op | threads |
|---|---|---|---|---|---|---|---|---|
| this library, main + #888 | 10.0 | 1.8 | 2.0 | 69 | 98 | 130 | 20069 | 22 |
| + E (ACK hold 300 us) | 10.0 | 2.0 | 2.0 | 56 | 105 | 97 | 13602 | 22 |
| + B (handler on the loop) | 4.0 | 0.9 | 0.0 | 52 | 114 | 86 | 19157 | 18 |
| + B + E | 4.0 | 1.0 | 0.0 | 40 | 90 | 52 | 13301 | 18 |
| Netty QUIC, handler on the event loop | 1.0 | 1.3 | 0.0 | 51 | 129 | 63 | 2335 | 10 |
| Netty QUIC, handler on an `EventExecutorGroup` | 3.0 | 2.6 | 0.0 | 60 | 172 | 90 | 2989 | 11 |

[M] counts; timings indicative. **B** is a prototype change in `SharedQuicheServer.connections` that launches the handler on the connection's loop (`ExecPrototype.handlersOnLoop`); nothing else changed, so the handler's stream writes still reach the driver through its command channel and are served on its next turn. **E** is the ACK hold of §6.5 (300 us, clean path only). Both run the unmodified QUIC echo of the comparison benchmarks.

With B + E the real library's wake-ups, CPU and p50 are in Netty's range on this machine and its selector wake-ups are 1.0; the allocation is 5.7x Netty's. The two remaining caller resumes and the two timers are enumerated in §3.2.

Across callers (`blocking`, `default`, `main`) the counts are the same to the unit (the caller's own dispatch adds 0 to 2 pool dispatches); the full rows are in the prototype outputs.

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

### 5.8 Quiet-machine timings

A run of the same rows on a quiet Linux x86_64 machine was queued behind that machine's benchmark lock, which another job held for the whole of this study; no quiet-machine row was obtained. Every p50, p99 and CPU figure in this document is therefore from the loaded laptop and is indicative only. [U]

## 6. The options

Each option is stated, then measured in the lab or the real driver, then given a safety class: **by construction** (the property holds whatever the handler does), **by detection** (it holds after the fact, bounded), or **not at all**.

### 6.0 Summary

| option | what it does | measured effect (echo, wake-ups; lab unless noted) | cost / risk | does not apply | safety property |
|---|---|---|---|---|---|
| A | status quo after #886/#888 | 4.0 (lab); 10.0 real library | none | | by construction |
| B | handler as a coroutine on the connection's loop | 4.0 -> 2.0 lab; **10.0 -> 4.0 real QUIC** | a blocking handler stalls the loop | Native: lane is per connection, no loss | **not at all** alone; **by detection** with the hand-off watchdog (1.8 ms bound) |
| C | resume inline on the loop to the next suspension, then pool | 2.0 for an echo; 4.0 once the handler suspends | after the first suspension nothing is enforced | | not at all (the first run segment stalls the loop: 109.5 ms) |
| D | write executes on the caller's thread by CAS ownership | 4.0 -> 3.0, one selector wake and one dispatch fewer | a syscall on the caller's thread | any caller we do not own (Android main) | by construction **only on library-owned threads** |
| E | hold the ACK-only flush a short bounded time so the reply shares its datagram | **real: CPU 130 -> 97 us, p50 69 -> 56 us; sim: datagrams per echo 4.5 -> 3.6** | tail risk on lossy paths (measured: none beyond noise) | | by construction (bounded by the hold; clean path only) |
| F | owned handler threads, spin-then-park, one batched resume per loop turn | worse than the pool at low rate; 6.8 us/op and 72 B at 1024 connections | a lone owned thread stalls neighbours (109 ms) | | by construction only with several threads |
| G | kernel batching (`sendmmsg`/GSO/`recvmmsg`, `nw_connection_batch`) | not measured [U] | platform code | Linux io_uring and Apple only | n/a |
| H | lean continuations: no `select`, no `CompletableDeferred`, inline fast path | server alloc 341 -> 261 B/op at scale, 45 B on the loop | invariants at the cancellation edge | | by construction |
| I | write accepted into the send queue, returns without suspending | 4.0 -> 3.0 (pool path); client 2.0 -> 1.0 | semantic change: "accepted", not "sent"; a failure surfaces later | | by construction |

### 6.1 A — the status quo (baseline)

After #886 (timers) and #888 (turns) the real library is at 10.0 wake-ups here and, by the owner's figure, about 6 with #886 [PR/I]. The ten are attributed in §3.2. A is main-safe and loop-safe by construction and loses on every hand-off row.

### 6.2 B — the handler runs on the connection's loop, with enforcement

Measured twice. In the lab (TCP): 4.0 -> 2.0 wake-ups, p50 44 -> 25 us, 62 -> 25 us CPU. In the real QUIC driver (prototype: one line at `SharedQuicheServer.connections` plus an accessor): **10.0 -> 4.0 wake-ups, 2.0 -> 0 pool dispatches, CPU 130 -> 86 us, p50 69 -> 52 us**, nothing else changed. The remaining difference to Netty is the allocation and the caller's two resumes.

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

Mechanism (prototype, `proto/ack-hold`): on a turn that only received datagrams, handed bytes to a waiting reader, on an established, non-migrating connection whose `AckCadence` is `Relaxed` (no loss evidence), skip that turn's flush and arm a loop wake `holdUs` later; any command that arrives meanwhile (the handler's `StreamSend`) flushes, so the ACK, the flow-control credit and the reply leave in one datagram; at expiry the loop flushes. quiche has no ACK timer, so no other component is involved; the hold must stay below the advertised `max_ack_delay` (default 25 ms; RFC 9000 13.2.1) and RFC 9002 requires the receiver to report the real delay so the sender can subtract it from its RTT sample (quiche timestamps receipt, so the reported ack_delay includes the hold [I, not verified by a qlog capture]).

Real loopback [M]: CPU per echo 130 -> 97 us, p50 69 -> 56 us, bytes 20 -> 13.6 KB (an ACK-only datagram's whole send path is gone) with the handler on the pool; 86 -> 52 us with the handler on the loop. A hold of 100 us, 300 us, 1 ms and 3 ms on the laptop gave 123, 101, 125 and 92 us p50 (noise dominated; the loaded machine cannot rank them; see §5.8).

Deterministic sim [M], `withMigrationSim`, 100 seeds x 30 echoes per row, 64 B echoes on one stream, a handler that replies instantly (hd=0) or after 1 virtual ms (hd=1; the sim's `delay` cannot express less):

| path | hold | handler delay | datagrams per echo (handshake and close included) | echo p50 | echo p99 | runs that failed |
|---|---|---|---|---|---|---|
| clean, 20 ms one way | 0 | 0 | 4.53 | 40 ms | 40 ms | 0 |
| clean | 200 us | 0 | **3.57** | 40 ms | 40 ms | 0 |
| clean | 200 us | 1 ms | 4.53 (the hold expires, no merge) | 41 ms | 41 ms | 0 |
| clean | 2 ms | 1 ms | 3.57 | 41 ms | 41 ms | 0 |
| 10 % loss, 50 ms | 0 / 200 us | 0 | 5.18 / **4.69** | 100 / 100 ms | 724 / 724 ms | 0 / 0 |
| 30 % loss, 50 ms | 0 / 200 us | 0 | 7.51 / 7.35 | 226 / 228 ms | 6994 / 8034 ms | 1 / 1 |
| bursty 15 % x 5, 50 ms | 0 / 200 us | 0 | 5.63 / **4.80** | 100 / 100 ms | 2611 / 2511 ms | 0 / 0 |

Reading it: the hold removes about one datagram per echo on clean and bursty paths and nothing when the handler is slower than the hold (it then costs nothing but the wake). On the 30 % loss path `AckCadence` leaves `Relaxed` almost at once so the hold rarely applies (397 holds in 3000 echoes) and p99 moved from 6994 to 8034 ms, which is within what 3000 samples (30 in the tail) and a different draw per datagram can produce; the failed run is the same seed in every row. **No sweep showed a loss of delivery or a worse tail that the hold can be blamed for, and the previous finding that fewer ACKs cost under loss is respected because the hold is only taken while the cadence memory says the path is clean.** What this does not test: a real path with asymmetric delay, ACK compression, or a peer that is not quiche. [U]

### 6.6 F — a cheaper hand-off rather than fewer

An owned handler thread (spin 30 us then park) fed by one task per loop turn. Measured: at low concurrency it is **worse** than the shared pool (wake-ups 3.1 vs 4.0, but p50 87 vs 44 us and CPU 124 vs 62 us on this machine: the spin does not cover the gap between requests, then parks). At scale it is the cheapest way to keep a handler off the loop (§5.5: 6.8 us and 72 B per op at 1024 connections with D and lean continuations and batched resumes, against the pool's 18.7 us and 343 B and Netty's `EventExecutorGroup` 14.8 us and 471 B). **A lone owned thread is unsafe for blocking handlers (§5.6)**; as designed here it needs more than one thread plus the watchdog, and an adaptive spin. It must remain a default that a consumer can replace: the handler's `CoroutineDispatcher` stays theirs to supply.

### 6.7 G — batching across operations

Not prototyped. [I] Netty's NIO transport on macOS and Windows does none of it; its Linux epoll transport uses `recvmmsg` and GSO. The lab's "one resume per loop turn" (Fb, §5.5) is the coroutine-level analogue and is worth 392 -> 72 B per op at 1024 connections. For the kernel level, this library's Linux io_uring path can batch sends and receives per ring submit and Apple's C shim can use `nw_connection_batch`; the gain is a bulk and many-connection gain, not an echo one. It needs its own gate (the bulk rows of #880 and the migration sims: `perf/quic-bulk-batching-wip` showed flush batching breaking two migration sims, not root-caused).

### 6.8 H — what a suspension fundamentally costs, and how low it goes

Lab, per echo on the loop: **45 B** (B + H, identical to the callback floor), and 261 B with a pool hand-off (H) against 341 B without lean continuations (A). The three ingredients: `suspendCoroutineUninterceptedOrReturn` with a single-slot state machine instead of `suspendCancellableCoroutine` (saves the `CancellableContinuationImpl` per park), an inline fast path that returns the bytes without suspending when they are already there, and no `Result` boxing. A dispatch to another thread costs a `DispatchedContinuation` and a `Runnable` whatever you do. What this does **not** reach is the real library's 13 to 20 KB: that is commands, deferreds and buffer handling in the driver, outside the execution model.

### 6.9 I — a write that returns when accepted (not in the owner's list)

The write is queued into the connection's send queue and returns immediately unless the queue is full (backpressure suspends it). Measured: the status-quo pool path 4.0 -> 3.0 wake-ups; the client 2.0 -> 1.0, with the same single loop wake Netty's `writeAndFlush` pays. It removes the second hand-off for every caller, including the Android main thread, and costs no syscall on the caller's thread. **It changes a contract**: `RFC_WRITE_TIMEOUT_CONTRACT.md` defines `write(deadline)` as bounded until the bytes are accepted by the transport; "accepted into the send queue" is a weaker point on the same line, as it is for Ktor and for a Netty `write` without a listener. A failure surfaces on the next operation or at close. This is the owner's decision (D2).

## 7. Safety: the letter, the purpose, and the enforcement ladder

### 7.1 The rule, by its purpose

"The transport never runs user code on its loop" exists so that a consumer cannot stall the transport or other connections. It is satisfied *by construction* if user code is never on a thread that serves others. It is satisfied *in purpose* if user code may be on such a thread but a stall is detected and bounded by something other than the user's own good behaviour.

### 7.2 What each design guarantees (measured, §5.6)

By construction: A, a pool, an `EventExecutorGroup` of several threads, this library's TCP on `main`. Not at all: B, C, a lone owned thread, Netty's on-loop handler. By detection with a bound: B with the hand-off watchdog: the first overrun stalls others for about the watchdog's threshold (1 ms threshold, 1.8 ms measured neighbour worst case), then never again for that handler if it is demoted.

### 7.3 The hand-off watchdog, honestly

What it is: a loop whose state (selector, queue) is not confined to one thread for life, only to one thread per *generation*; the watchdog starts the next generation when a unit overruns. The existing `LoopGuard` already models ownership per turn, not per thread for life ("a coroutine may resume on another thread after every suspension"), so the confinement argument survives. What it costs: a second generation's thread for the duration of one overrun, and two volatile stores per unit. What it does not do: preempt the blocking thread (it cannot; the 100 ms block still takes 100 ms for *that connection*), or help when a handler blocks *every* thread it is moved to (each overrun spawns a successor: bound the number of generations per loop and demote the handler on the first). It is an invariant that **at most one thread selects at a time** and that a connection's QUIC state is only touched by the thread that owns the current turn; both must be model-checked (the repository has Lincheck lanes: `:lincheckTest`) before this ships, and the failure of that check is a reason to stay on the pool. Not tested on the real QUIC driver [U].

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
6. **ACK hold (E)** is automatic and bounded: `min(200 us, srtt / 8)` on a clean path only, never on a connection that has shown loss, never with migration enabled; no knob.
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
| (a) echo, wake-ups | **match** after #886 + B + I (1.0 vs 1.0); 2.0 without I | [M] 4.0 now with B; two caller resumes + two timers explained [M]; the rest [I] |
| (a) echo, CPU, p50 | **match** (inside the laptop's noise) | [M] 52 us vs Netty 63 us, p50 40 vs 51 us, minutes apart on the same loaded machine; needs the quiet machine |
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

**D1. May a handler run on the loop, with the watchdog bounding the stall? (the letter of the rule)** Recommendation: yes, only under `Auto` (measured, promoted/demoted) or an explicit `OnLoop`, only with the hand-off watchdog, and only after the model check of §7.3 passes. Evidence: the real driver goes 10.0 -> 4.0 wake-ups and 130 -> 86 us CPU with this change alone (§5.4); the watchdog bounds the neighbour's worst case to 1.8 ms against 110 ms (§5.6). If the answer is no, keep A with F + D + H + I; the lab says it reaches Netty's executor-group form but not its on-loop form.

**D2. Is "write returns when accepted into the send queue" acceptable as the write contract?** Recommendation: yes, with errors surfacing on the next operation and at close, and a `flush`/`sync`-style suspend for callers that need "sent"; Evidence: removes one caller resume everywhere including the Android main thread (client 2.0 -> 1.0 wake-ups) and one hop on the server. Cost: `RFC_WRITE_TIMEOUT_CONTRACT.md`'s statement of "bounded until accepted by the transport" is reinterpreted, not violated, but the timeout and error vocabulary should be restated.

**D3. Is the ACK hold on by default, with no knob?** Recommendation: yes, `min(200 us, srtt/8)`, clean path only. Evidence: sim datagrams per echo 4.53 -> 3.57 on a clean path with no failure or tail effect on any of the four paths; real loopback CPU -25 % (§6.5). The unknown is a real asymmetric path; stage it behind the nightly sim sweeps first.

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
| 2 | **E**, the ACK hold | sim sweep (clean, 10 %, 30 %, bursty): datagrams/echo down, p99 and failed runs unchanged; loopback CPU/echo; qlog check that reported ack delay includes the hold |
| 3 | **B + measured promotion/demotion** on the QUIC server and TCP loops, without the watchdog but `OffLoop` default and `OnLoop` explicit only | echo wake-ups 4.0 or fewer; handler-blocks-100-ms test shows no regression for `OffLoop` |
| 4 | **the hand-off watchdog** + model check (Lincheck) + the blocking-handler safety test | neighbour max latency <= 2 ms during a 100 ms block on the real QUIC driver and TCP loops; no violation of "one selecting thread" under the checker |
| 5 | handler executor (F + D, several threads, adaptive spin), `Auto` becomes the default | many-connection rows: server CPU/op and bytes within 1.5x of the on-loop row at 1024 connections; safety row unchanged |
| 6 | **I**, queued writes (if D2 = yes) | client wake-ups 2.0 -> 1.0; `RFC_WRITE_TIMEOUT_CONTRACT` conformance suite |
| 7 | Linux kernel batching (io_uring `sendmmsg`/GSO), Apple `nw_connection_batch` | bulk and many-connection rows; the two migration sims that flush batching broke stay green |
| 8 | Native/Android confirmation | a macOS release-binary run and an Android device or emulator run of the same rows; StrictMode main-thread test |

Rationale for the order: 0 first because every later gate needs it; 1 and 2 are independent, safe by construction and reduce cost for everyone; 3 and 4 are the contentious change and are gated twice (the second gate removes the objection to the first); 5 depends on 4's executor decisions; 6 is the semantic change and waits on D2; 7 and 8 are platform work.

## 12. What is measured, what is inferred, what is unknown, and where the prototypes are

**Measured** here: all counts in §3.2 and §5 (laptop, JFR); the safety, many-connection and idle tables (laptop); the real-library effect of B and E (laptop); the sim sweeps for E; Netty QUIC and TCP in the same harness.

**Taken from PR bodies, not re-measured:** the 13 -> 10 -> about 6 wake-up arc of #886/#887/#888, the 211 vs 65 us CPU and 251 vs 186 us p50 Linux numbers, #887's TCP counts.

**Inferred:** the ten-to-one wake-up path once #886 and I are applied; Netty's behaviour on Linux (epoll, GSO); Native and Apple behaviour from reading `DriverPlacement.native.kt` and `NWSocketWrapper`; quiche's reported ACK delay.

**Unknown:** quiet-machine p50/p99 and CPU (see §5.8); Netty QUIC at many connections; idle QUIC; Android StrictMode behaviour of a non-blocking socket write; Native numbers; the cost of the watchdog on the real QUIC driver; whether the Lincheck model of §7.3 holds; kernel batching.

**Prototypes** (local branches in the main checkout's repository, not pushed, not part of this PR): `proto/ack-hold` contains the lab (`benchmarks/jvm/src/main/kotlin/com/ditchoom/socket/benchmark/lab/`, run through `LabMain`), the Netty off-loop hook in `NettyQuicBenchmarks.kt`, option E in `QuicheDriver` (`AckHoldPrototype`, a hold in `afterCommand` and the loop wait), option B on the real driver (`ExecPrototype.handlersOnLoop` in `SharedQuicheServer`), the sim sweep `AckHoldSimSweepTest`, on top of `main` + the diff of #888; `proto/exec-lab` is the lab alone at its first state. A git bundle of both was kept with the study's scratch outputs (the counts, the JFR files and the sweeps' raw rows).
