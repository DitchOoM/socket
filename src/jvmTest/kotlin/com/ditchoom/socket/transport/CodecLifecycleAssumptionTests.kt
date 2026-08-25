package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Measures the assumptions a lifecycle redesign of [CodecConnection] would rest on, instead of
 * asserting them from documentation or recollection.
 *
 * ## Why this file exists
 *
 * [CodecConnection] carries two hand-rolled lifecycle flags — `@Volatile var closed` and
 * `@Volatile var receiving` — each read-then-written without atomicity:
 * ```
 * override suspend fun close() {
 *     if (closed) return      // two callers can both pass
 *     closed = true
 *     ...                      // ...and both run the whole teardown
 * }
 * ```
 * The proposed fix is *not* a stronger atomic. It is to delete the flags and let the primitives this
 * class already owns carry the state: the `outbound` [Channel], whose `close()` returns `true` for
 * exactly the caller that closed it, and a lazily-started teardown [Job], whose `start()` is
 * once-only and whose `join()` is idempotent.
 *
 * That proposal is only sound if those primitives actually behave that way **at the versions this
 * project pins**, and if the defect it removes is real. Neither is assumed here. Part A runs the
 * kotlinx primitives directly and reports what they do; Part B runs the real [CodecConnection] on
 * real threads and reports whether the race is reachable.
 *
 * ## Why JVM, and why not `runTest`
 *
 * Every other test in this family runs under `runTest`, whose scheduler is single-threaded virtual
 * time. That is the right tool for asserting ordering deterministically and is **structurally blind**
 * to a two-thread check-then-act race: a single-threaded scheduler cannot interleave two threads
 * between a volatile read and the write that follows it. These tests therefore use real threads on
 * [Dispatchers.Default], synchronised with a [CyclicBarrier] so the contending callers are released
 * at the same instant rather than staggered by start-up cost.
 *
 * ## Honest statement of what a green run here means
 *
 * Part A is deterministic: those assertions hold on every run or the redesign's foundation is wrong.
 *
 * Part B is a **race prober**, not a proof of absence. It reports the observed rate over many
 * attempts. A non-zero count proves the race is reachable. A zero count does **not** prove it is
 * unreachable — it means this harness did not hit a window a few instructions wide on this machine,
 * and the finding would be "latent" rather than "absent". Each Part B test asserts on the direction
 * that is safe to assert and prints the measured rate either way, so a future reader gets the number
 * rather than a bare pass.
 */
class CodecLifecycleAssumptionTests {
    private companion object {
        /** Contending callers per race attempt. Above the core count on purpose, to force preemption. */
        const val RACERS = 8

        /**
         * Attempts per Part A primitive race. Each attempt spawns [RACERS] real platform threads
         * (see `raceOnThreads` for why coroutines are not usable here), so this is deliberately far
         * lower than a coroutine-based loop would need. The properties under test are exact — "exactly
         * one winner" — so contention, not iteration count, is what makes them meaningful.
         */
        const val ATTEMPTS = 200

        /**
         * Attempts for the Part B race probers. Lower than [ATTEMPTS] because each attempt spawns real
         * platform threads and observes them, rather than running coroutines on a shared pool.
         */
        const val RACE_ATTEMPTS = 300

        /** How long a prober waits to see both racers inside the guarded region before giving up. */
        val RACE_OBSERVATION_WINDOW = 20.milliseconds

        /**
         * Budget for establishing a race window that MUST exist before the attempt means anything.
         * Generous on purpose: this is not the thing being measured, and a machine under load must
         * not be able to turn a real prober into a vacuous one.
         */
        val WINDOW_SETUP_BUDGET = 5.seconds

        const val THREAD_JOIN_MILLIS = 10_000L

        /** Chunks queued in the stream processor before teardown, so its iteration has a window. */
        const val SEEDED_CHUNKS = 64

        /**
         * Chunks the dripping collector queues before teardown races it — far more than
         * [SEEDED_CHUNKS], and that is the point.
         *
         * `release()` walks the deque, so the length of that walk IS the width of the window an
         * append can land in. At 64 chunks the walk is short enough that on a loaded machine the
         * collector is usually parked for the whole of it, and the prober stopped discriminating
         * in-suite while still catching the defect 116/300 when run alone. Making the walk long
         * removes the dependence on how much CPU the collector happens to get.
         */
        const val DRIP_CHUNKS = 3_000

        /** Reads the dripping collector still serves after close(), holding the window open. */
        const val READS_AFTER_CLOSE = 400

        val TEST_TIMEOUT = 120.seconds
    }

    // ── Part A: the kotlinx primitives, measured ────────────────────────────────────────────────

    /**
     * **Assumption 1: `SendChannel.close()` is a once-only latch.**
     *
     * The redesign replaces `if (closed) return; closed = true` with the return value of
     * `outbound.close()`, which [CodecConnection] currently calls and discards. That only works if
     * exactly one of many concurrent closers is told it won.
     */
    @Test
    fun channelCloseReturnsTrueForExactlyOneOfManyConcurrentClosers() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var totalWinners = 0
                repeat(ATTEMPTS) {
                    val channel = Channel<Int>(capacity = 8)
                    val winners = raceOnThreads(RACERS) { channel.close() }.count { it }
                    assertEquals(
                        1,
                        winners,
                        "Channel.close() must return true for exactly one concurrent caller; " +
                            "$winners of $RACERS were told they closed it. The redesign uses this " +
                            "return value as the teardown latch, so anything other than 1 invalidates it.",
                    )
                    totalWinners += winners
                }
                assertEquals(ATTEMPTS, totalWinners, "one winner per attempt, across $ATTEMPTS attempts")
            }
        }

    /**
     * **Assumption 2: a `LAZY` job's `start()` is a once-only latch, and its body runs exactly once.**
     *
     * The teardown-job idiom is `teardown.start(); teardown.join()` from every `close()` caller. It
     * needs both halves: exactly one starter, and exactly one execution of the body.
     */
    @Test
    fun lazyJobStartsExactlyOnceUnderConcurrentStarters() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                repeat(ATTEMPTS) {
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val bodyRuns = AtomicInteger(0)
                    val teardown = scope.launch(start = CoroutineStart.LAZY) { bodyRuns.incrementAndGet() }
                    val starters =
                        raceOnThreads(RACERS) {
                            val started = teardown.start()
                            teardown.join()
                            started
                        }.count { it }
                    assertEquals(1, starters, "exactly one caller may be told it started the teardown job")
                    assertEquals(1, bodyRuns.get(), "the teardown body must run exactly once")
                    scope.cancel()
                }
            }
        }

    /**
     * **Assumption 3: the caller that *lost* the start race still waits for the body.**
     *
     * This is the property that makes `close()` honest — every caller must observe teardown as
     * *finished* when `close()` returns, not merely as *begun by somebody else*. A latch that let the
     * loser return early would be strictly worse than today's racy flag, which at least runs the work.
     */
    @Test
    fun theLoserOfTheStartRaceStillWaitsForTeardownToFinish() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                repeat(500) {
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val teardownFinished = AtomicInteger(0)
                    val teardown =
                        scope.launch(start = CoroutineStart.LAZY) {
                            // Real work, not an empty body: an instant body could let a loser's join()
                            // return "after" the body purely because the body was already over.
                            Thread.sleep(2)
                            teardownFinished.set(1)
                        }
                    val observations =
                        raceOnThreads(RACERS) {
                            teardown.start()
                            teardown.join()
                            // Read AFTER join returns. Every caller — winner and losers alike —
                            // must see the completed teardown.
                            teardownFinished.get()
                        }
                    assertTrue(
                        observations.all { it == 1 },
                        "every close() caller must observe teardown as finished once join() returns; " +
                            "saw $observations",
                    )
                    scope.cancel()
                }
            }
        }

    /**
     * **Assumption 4 — the one that can sink the design: a teardown job on a CANCELLED scope.**
     *
     * [CodecConnection]'s writer lives on a **caller-supplied** scope. If the teardown job lives there
     * too, then a caller whose scope is already cancelled — the ordinary shape when `close()` runs
     * from a `finally` after a failure — would call `start()`, get nothing, and `join()` would return
     * against a job that never ran. Teardown includes `streamProcessor.release()` and
     * `bufferPool.clear()`, so silently skipping it leaks pooled native memory for the process's life.
     *
     * This test does not assert that the naive idiom is safe. It measures what it actually does, so
     * the redesign is built on the answer rather than on hope.
     */
    @Test
    fun aLazyTeardownJobOnAnAlreadyCancelledScopeNeverRunsItsBody() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val bodyRuns = AtomicInteger(0)
                scope.cancel()
                val teardown = scope.launch(start = CoroutineStart.LAZY) { bodyRuns.incrementAndGet() }
                val started = teardown.start()
                teardown.join()
                assertFalse(
                    started,
                    "a job launched into a cancelled scope reports it did not start — recording the " +
                        "observed behaviour, not endorsing it",
                )
                assertEquals(
                    0,
                    bodyRuns.get(),
                    "MEASURED HAZARD: teardown on a cancelled caller scope never runs. A naive " +
                        "`scope.launch(LAZY)` teardown job therefore leaks the buffer pool whenever " +
                        "close() is reached from a failure path that cancelled the scope. The " +
                        "redesign must host teardown somewhere cancellation cannot reach it.",
                )
            }
        }

    /**
     * **Assumption 5: `CompletableDeferred.complete()` is a once-only latch.**
     *
     * This is the latch the revised design actually uses, in preference to the `outbound` channel's
     * own `close()` return value. The channel is **not safe as a teardown latch**: `CodecConnection`'s
     * writer closes `outbound` from its own `finally` block on failure or scope cancellation, so a
     * legitimate `close()` caller can arrive to find the channel already closed, be told it lost a
     * race it never entered, and skip teardown entirely. A latch only `close()` touches has no such
     * interference.
     */
    @Test
    fun completableDeferredCompletesForExactlyOneOfManyConcurrentCallers() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                repeat(ATTEMPTS) {
                    val latch = CompletableDeferred<Unit>()
                    val winners = raceOnThreads(RACERS) { latch.complete(Unit) }.count { it }
                    assertEquals(
                        1,
                        winners,
                        "CompletableDeferred.complete() must return true for exactly one concurrent " +
                            "caller; $winners of $RACERS were told they won. The redesign uses this as " +
                            "the teardown latch, so anything other than 1 invalidates it.",
                    )
                }
            }
        }

    /**
     * **Assumption 6: `Mutex.tryLock()` admits exactly one of many concurrent callers.**
     *
     * `receiving` is not a lifecycle — it is a mutual-exclusion latch, and the redesign says so by
     * using a [kotlinx.coroutines.sync.Mutex] rather than promoting it into a sealed state. Unlocking
     * in a `finally` preserves the documented "sequential collection is allowed" behaviour, while
     * `tryLock` makes the concurrent case deterministic rather than 24% likely to be admitted.
     */
    @Test
    fun mutexTryLockAdmitsExactlyOneOfManyConcurrentCallers() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                repeat(ATTEMPTS) {
                    val mutex = Mutex()
                    val admitted = raceOnThreads(RACERS) { mutex.tryLock() }.count { it }
                    assertEquals(1, admitted, "exactly one concurrent collector may hold the lock")
                    mutex.unlock()
                    assertTrue(mutex.tryLock(), "unlocking in a finally must re-admit a later collector")
                }
            }
        }

    // ── Part B: is the defect reachable on the real class? ──────────────────────────────────────

    /**
     * **Defect 1: the concurrent-collector guard in `receive()` does not guard.**
     *
     * `check(!receiving); receiving = true` is read-then-write with nothing between, so two collectors
     * can both pass the check before either sets the flag — which is precisely the state the check
     * exists to prevent, and which corrupts the shared `StreamProcessor`.
     *
     * ## The witness, and the false positive it replaces
     *
     * The first version of this test used a stream that returned [ReadResult.End] immediately and
     * counted collectors that completed without throwing. It reported 1819/2000 — and it was **wrong**.
     * With an instantly-completing flow the two collectors almost always ran *sequentially*: the first
     * set `receiving = true`, finished, and reset it to `false` before the second arrived. Sequential
     * collection is explicitly permitted ("handshake then streaming"), so that test would have passed
     * against perfectly correct code. It measured the design working, and called it a defect.
     *
     * The witness here cannot make that mistake. [GatedStream] **parks** inside `read()` until the test
     * releases it, so any collector admitted past the guard stays inside the flow rather than passing
     * through it. The stream records the peak number of readers simultaneously inside, which is an
     * observation only genuine concurrency can produce, taken outside [CodecConnection] and without
     * consulting the flag under test.
     */
    @Test
    fun concurrentCollectorsAreNeverBothAdmitted() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsWithTwoInside = 0
                var rejections = 0
                repeat(RACE_ATTEMPTS) {
                    val stream = GatedStream()
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val connection = connectionOver(stream, scope)
                    val rejected = AtomicInteger(0)
                    val gate = AtomicBoolean(false)
                    val collectors =
                        (1..2).map {
                            thread(name = "collector-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    try {
                                        connection.receive().collect { }
                                    } catch (_: IllegalStateException) {
                                        rejected.incrementAndGet()
                                    }
                                }
                            }
                        }
                    // Both threads are already inside runBlocking and spinning, so releasing the gate
                    // lands them on the guard within nanoseconds of each other — far tighter than
                    // park/unpark, which would let one collector finish before the other woke.
                    Thread.sleep(1)
                    gate.set(true)
                    stream.awaitReadersOrTimeout(expected = 2, timeout = RACE_OBSERVATION_WINDOW)
                    if (stream.peakReadersInside.get() > 1) attemptsWithTwoInside++
                    if (rejected.get() > 0) rejections++
                    stream.release()
                    collectors.forEach { it.join(THREAD_JOIN_MILLIS) }
                    connection.close()
                    scope.cancel()
                }
                println(
                    "[assumption] concurrent receive(): $attemptsWithTwoInside/$RACE_ATTEMPTS attempts " +
                        "had TWO collectors simultaneously inside the flow; $rejections/$RACE_ATTEMPTS " +
                        "attempts saw the guard reject one",
                )
                assertEquals(
                    0,
                    attemptsWithTwoInside,
                    "two collectors were simultaneously inside the flow — the Mutex must admit exactly " +
                        "one, and the stream processor must never be shared",
                )
                assertTrue(
                    rejections > 0,
                    "no attempt saw the guard reject a collector, so this harness never actually " +
                        "contended and its zero above would be vacuous. Before the fix this same " +
                        "harness admitted two collectors in 140/300 attempts.",
                )
            }
        }

    /**
     * **Defect 2: concurrent `close()` can run the whole teardown more than once.**
     *
     * Same shape, observed through the stream: teardown calls `stream.close()` exactly once per pass,
     * so a stream counting its own closes is an independent witness that never consults the `closed`
     * flag driving the behaviour under test.
     *
     * Unlike the guard above, this window is only a couple of instructions wide and has **no
     * suspension point inside it**, so a collector cannot be held in it the way [GatedStream] holds a
     * reader. The gate is therefore a spin-wait rather than a barrier, and every racer enters
     * `runBlocking` *before* the gate so that only the call itself remains between the release and the
     * volatile read.
     *
     * See the class KDoc: a zero count here is a statement about this harness, not about the JMM.
     */
    @Test
    fun concurrentCloseRunsTeardownExactlyOnce() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsWithDoubleTeardown = 0
                var worstObserved = 0
                var fewestObserved = Int.MAX_VALUE
                repeat(RACE_ATTEMPTS) {
                    val stream = GatedStream()
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val connection = connectionOver(stream, scope)
                    val gate = AtomicBoolean(false)
                    val closers =
                        (1..RACERS).map {
                            thread(name = "closer-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    connection.close()
                                }
                            }
                        }
                    Thread.sleep(1)
                    gate.set(true)
                    closers.forEach { it.join(THREAD_JOIN_MILLIS) }
                    val closes = stream.closeCount.get()
                    if (closes > 1) attemptsWithDoubleTeardown++
                    if (closes > worstObserved) worstObserved = closes
                    // Tracked as well as the max: asserting only the worst case would let an attempt
                    // that ran teardown ZERO times pass, so a latch that made every caller skip
                    // teardown would look identical to one that ran it exactly once.
                    if (closes < fewestObserved) fewestObserved = closes
                    stream.release()
                    scope.cancel()
                }
                println(
                    "[assumption] concurrent close(): $attemptsWithDoubleTeardown/$RACE_ATTEMPTS " +
                        "attempts ran teardown more than once (teardown passes per attempt: " +
                        "min=$fewestObserved max=$worstObserved)",
                )
                assertEquals(
                    0,
                    attemptsWithDoubleTeardown,
                    "$RACERS concurrent closers must run teardown exactly once between them; before " +
                        "the fix this harness saw a double run in 223/300 attempts, worst case all " +
                        "$RACERS closers running the whole teardown",
                )
                assertEquals(1, worstObserved, "teardown must never run more than once")
                assertEquals(
                    1,
                    fewestObserved,
                    "teardown must never run ZERO times either — a latch that let every caller skip " +
                        "teardown would report no double runs and would pass a max-only assertion",
                )
            }
        }

    /**
     * **The guard works when the collectors are staggered** — the deterministic half of Defect 1.
     *
     * This one does not race. It holds the first collector provably inside the flow (parked in
     * [GatedStream.read]) and only then admits the second, which must be rejected. It is here so that
     * a fix which deletes the flag is held to keeping the behaviour that flag got *right*: a second
     * collector arriving while a first is genuinely mid-collection must not silently share the
     * decoder.
     *
     * If the redesign changes this to "the two collectors split the stream" rather than "the second is
     * rejected", this test is the one that must be consciously rewritten — and that rewrite is a
     * public behaviour change, not a detail.
     */
    @Test
    fun aSecondCollectorArrivingWhileTheFirstIsInsideIsRejected() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                val stream = GatedStream()
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val connection = connectionOver(stream, scope)
                val first =
                    thread(name = "first-collector") {
                        runBlocking { runCatching { connection.receive().collect { } } }
                    }
                stream.awaitReadersOrTimeout(expected = 1, timeout = TEST_TIMEOUT)
                assertEquals(
                    1,
                    stream.readersInside.get(),
                    "precondition: the first collector must be parked inside the flow before the " +
                        "second arrives, otherwise this test is measuring sequential collection",
                )
                assertFailsWith<IllegalStateException>(
                    "a second collector arriving while the first is mid-collection must be rejected",
                ) { connection.receive().collect { } }
                stream.release()
                first.join(THREAD_JOIN_MILLIS)
                connection.close()
                scope.cancel()
            }
        }

    /**
     * **Defect 3 — the harm the double teardown actually causes.**
     *
     * [concurrentCloseCanRunTeardownMoreThanOnce] proves the race is reachable; it does **not** prove
     * anything bad follows, and the obvious guess was wrong. Double-*free* is defused on the default
     * configuration: pooled buffers latch their own free (`PooledBuffer.sharedFreed.exchange(1)`) and
     * `LockFreeBufferPool.clear()` pops through a CAS, so neither can hand the same buffer out twice.
     *
     * The real failure is a **`ConcurrentModificationException`**. `DefaultStreamProcessor.release()`
     * iterates a `kotlin.collections.ArrayDeque` and then calls `clear()` on it; `ArrayDeque` does not
     * override `iterator()`, so the iteration is `java.util.AbstractList.Itr` with a `modCount` check,
     * and one caller reaching `clear()` while another is mid-iteration throws. That exception
     * propagates out of `close()` — **before `bufferPool.clear()` on the next line** — so the encode
     * pool is never drained. On K/N that is a `malloc` leak; on the JVM it retains direct buffers
     * toward the `OutOfMemoryError: Cannot reserve … direct buffer memory` that `ReadBufferSource`'s
     * own KDoc warns about. It also means `close()` throws from inside a `finally`, masking whatever
     * sent the caller there.
     *
     * ## Why this needs its own prober
     *
     * The other close prober uses a stream that yields no data, so the processor's chunk deque is
     * **empty** at teardown and the iteration has no window to lose. That harness cannot observe this
     * failure however many times it hits the race. Here the processor is deliberately loaded via
     * [CodecConnection.preSeed] — chunks with no complete frame among them, so nothing drains them —
     * which is what opens the window.
     */
    @Test
    fun concurrentCloseNeverThrowsFromTeardown() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsThatThrew = 0
                val seen = linkedSetOf<String>()
                repeat(RACE_ATTEMPTS) {
                    val stream = GatedStream()
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val connection = connectionOver(stream, scope)
                    // Each seed is a lone 0xFF byte, so the first two declare a 65535-byte frame that
                    // never completes and every chunk is still queued at close. (0x00 would declare a
                    // zero-length frame — completable, and drainable by anyone collecting.)
                    repeat(SEEDED_CHUNKS) {
                        val buffer = BufferFactory.Default.allocate(1)
                        buffer.writeByte(0xFF.toByte())
                        buffer.resetForRead()
                        connection.preSeed(buffer)
                    }
                    val failures = ConcurrentLinkedQueue<Throwable>()
                    val gate = AtomicBoolean(false)
                    val closers =
                        (1..RACERS).map {
                            thread(name = "closer-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    try {
                                        connection.close()
                                    } catch (t: Throwable) {
                                        failures += t
                                    }
                                }
                            }
                        }
                    Thread.sleep(1)
                    gate.set(true)
                    closers.forEach { it.join(THREAD_JOIN_MILLIS) }
                    stream.release()
                    if (failures.isNotEmpty()) {
                        attemptsThatThrew++
                        failures.forEach { seen += it::class.simpleName ?: "?" }
                    }
                    scope.cancel()
                }
                println(
                    "[assumption] concurrent close() teardown: $attemptsThatThrew/$RACE_ATTEMPTS " +
                        "attempts threw out of close(); exception types observed: $seen",
                )
                assertEquals(
                    0,
                    attemptsThatThrew,
                    "close() must not throw out of teardown; before the fix this harness saw " +
                        "ConcurrentModificationException and NoSuchElementException in 82/300 attempts, " +
                        "each of which skipped bufferPool.clear() and leaked the encode pool. Observed " +
                        "here: $seen",
                )
            }
        }

    /**
     * **A SINGLE `close()` racing a live collector must not throw** — and read the caveat below
     * before trusting a green run of this one.
     *
     * ## ⚠️ This prober discriminates ONLY when run in isolation
     *
     * Measured against unfixed code, same machine, same commit:
     *
     * | How it was run | Attempts that caught the defect |
     * |---|---|
     * | alone (`--tests "*aSingleCloseRacingALiveCollectorNeverThrows"`) | **300/300** |
     * | as part of this class | **0/300** |
     *
     * The window is the microseconds `release()` spends walking the deque, and it only opens if the
     * collector thread is actually scheduled during that walk. Alone, the machine is idle and it
     * always is. In-suite it never was — so **a green run of this test inside the full class is not
     * evidence of anything**, and a regression here would be caught by
     * [concurrentCloseNeverThrowsFromTeardown] (85/300 in-suite against unfixed code), which exercises
     * the same `release()`-racing-another-walker path without depending on scheduling luck.
     *
     * Kept anyway, because it is the only coverage of the *single-closer* shape — the finding that a
     * fix to the close latch alone would not have been enough — and because whoever next changes this
     * area should run it alone and watch it fail before the fix.
     *
     * The same discipline as [CodecConnectionThreadedPoolTests], which likewise records that its
     * harness does not reproduce the defect it was written for rather than implying that it does.
     *
     * **Defect 4 — a SINGLE `close()` is enough, if anyone is still collecting.**
     *
     * `close()` never stops a live `receive()` collector. The collector's `fillFromTransport()` calls
     * `streamProcessor.append()`, which is `chunks.addLast` and bumps the same `modCount` that
     * `release()`'s iteration is checking. So the corruption above does not actually require two
     * closers — one closer racing one collector reaches it too, which widens the defect well beyond
     * the concurrent-close shape and means fixing only the `closed` flag would not close it.
     */
    @Test
    fun aSingleCloseRacingALiveCollectorNeverThrows() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsThatThrew = 0
                val seen = linkedSetOf<String>()
                repeat(RACE_ATTEMPTS) {
                    val stream = DrippingStream()
                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val connection = connectionOver(stream, scope)
                    val collector =
                        thread(name = "collector") {
                            runBlocking { runCatching { connection.receive().collect { } } }
                        }
                    // Let the collector get into the read loop and start appending chunks, so the
                    // deque is non-empty and actively mutating when teardown iterates it.
                    // Wait until the collector has PROVABLY queued chunks, with a budget generous
                    // enough that a loaded machine cannot skip the wait. A 20ms window worked when
                    // this test ran alone (116/300 caught the defect) and silently stopped
                    // discriminating in-suite, where earlier tests leave runnable coroutines
                    // competing for cores: close() then reached the processor before the collector
                    // had appended anything, so there was no deque to corrupt and the prober passed
                    // against broken code. The precondition below turns that into a failure.
                    stream.awaitReadsOrTimeout(minimum = DRIP_CHUNKS, timeout = WINDOW_SETUP_BUDGET)
                    check(stream.reads.get() >= DRIP_CHUNKS) {
                        "harness precondition failed: the collector queued only ${stream.reads.get()} " +
                            "of $DRIP_CHUNKS chunks within $WINDOW_SETUP_BUDGET, so teardown would " +
                            "not have raced a populated processor and this attempt would measure " +
                            "nothing. Failing loudly rather than passing vacuously."
                    }
                    // [DrippingStream] keeps the collector alive for a bounded number of reads AFTER
                    // its close() is observed, which is what holds the window open deterministically.
                    // Two timing-based attempts failed here and both failed SILENTLY, by passing
                    // against unfixed code: shortening the drain budget, and stopping the drip on a
                    // 5ms timer. Both ended the collector before teardown reached the processor. The
                    // read counter does not depend on how long a dispatch happens to take.
                    val failure = runCatching { connection.close() }.exceptionOrNull()
                    stream.stop()
                    collector.join(THREAD_JOIN_MILLIS)
                    if (failure != null) {
                        attemptsThatThrew++
                        seen += failure::class.simpleName ?: "?"
                    }
                    scope.cancel()
                }
                println(
                    "[assumption] single close() vs live collector: $attemptsThatThrew/$RACE_ATTEMPTS " +
                        "attempts threw out of close(); exception types observed: $seen",
                )
                assertEquals(
                    0,
                    attemptsThatThrew,
                    "one close() racing one live collector must not throw; before the fix this harness " +
                        "saw ConcurrentModificationException in 73/300 attempts — note this needs only " +
                        "ONE closer, so fixing the concurrent-close latch alone would not close it. " +
                        "Observed here: $seen",
                )
            }
        }

    /**
     * **Teardown survives a cancelled caller.**
     *
     * `close()` is a `suspend` function whose canonical call site is `finally { connection.close() }`,
     * and the usual reason control reached that `finally` is that the caller was cancelled. Before the
     * fix the sequence was: latch, then `withTimeoutOrNull(...) { writerJob.join() }` — the first
     * suspension point — throws `CancellationException` in a cancelled coroutine, and every step after
     * it is skipped. `stream.close()`, `streamProcessor.release()` and `bufferPool.clear()` never ran.
     * `TypedMuxView.kt` wraps its close in `runCatching`, so that leak was entirely silent.
     *
     * The teardown body now runs inside `withContext(NonCancellable)` — the same treatment
     * `FallbackTransport` already gives stream closes for the same reason.
     *
     * ## The synchronisation this test needs, and why
     *
     * An earlier version launched the caller and cancelled it immediately. That is a race: `launch`
     * is asynchronous, so `cancelAndJoin()` can cancel the coroutine **before its body starts**, in
     * which case the `try` is never entered, the `finally` never runs, and `close()` is never called
     * at all. The test then observed an unclosed transport and blamed the connection for a leak its
     * own harness had caused — it passed for the wrong reason against fixed code. [entered] closes
     * that race: the caller is only cancelled once it is provably inside the `try`.
     */
    @Test
    fun closeFromACancelledCallerStillCompletesTeardown() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                val stream = GatedStream()
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val connection = connectionOver(stream, scope)
                stream.release()

                val entered = CompletableDeferred<Unit>()
                var thrown: Throwable? = null
                val caller =
                    launch(Dispatchers.Default) {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            // runCatching stands in for TypedMuxView's, which swallows this identically.
                            thrown = runCatching { connection.close() }.exceptionOrNull()
                        }
                    }
                entered.await()
                caller.cancelAndJoin()

                assertNull(
                    thrown,
                    "close() from a cancelled caller must complete rather than abort at its first " +
                        "suspension point",
                )
                assertEquals(
                    1,
                    stream.closeCount.get(),
                    "the transport must be closed exactly once even though the caller was cancelled — " +
                        "this is the leak the NonCancellable teardown exists to prevent",
                )
                scope.cancel()
            }
        }

    // ── Part C: the sibling classes carry the same flags ────────────────────────────────────────

    /**
     * **`CodecSender` had the identical `closed` check-then-act**, and the same fix applies.
     *
     * Its teardown is smaller — `sink.close()` then `bufferPool.clear()` — but running it twice still
     * clears a pool twice, and that pool is built with the default `SingleThreaded` threading mode,
     * which is documented as not thread-safe.
     */
    @Test
    fun codecSenderConcurrentCloseRunsTeardownExactlyOnce() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var worstObserved = 0
                repeat(RACE_ATTEMPTS) {
                    val sink = GatedStream()
                    sink.release()
                    val sender = CodecSender(sink = sink, codec = TestStringCodec)
                    val gate = AtomicBoolean(false)
                    val closers =
                        (1..RACERS).map {
                            thread(name = "sender-closer-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    sender.close()
                                }
                            }
                        }
                    Thread.sleep(1)
                    gate.set(true)
                    closers.forEach { it.join(THREAD_JOIN_MILLIS) }
                    if (sink.closeCount.get() > worstObserved) worstObserved = sink.closeCount.get()
                }
                println("[assumption] CodecSender concurrent close(): worst teardown passes = $worstObserved")
                assertEquals(1, worstObserved, "CodecSender teardown must run exactly once")
            }
        }

    /**
     * **`CodecReceiver` had the identical `receiving` check-then-act**, and it is the worst of the
     * three.
     *
     * Unlike [CodecConnection], its `finally` **releases the stream processor and clears the pool** on
     * the way out, so a first collector completing frees the state a second collector is still using —
     * and its pool is `SingleThreaded`. Two admitted collectors here is a use-after-release, not just
     * a shared decoder.
     */
    @Test
    fun codecReceiverConcurrentCollectorsAreNeverBothAdmitted() =
        runBlocking(Dispatchers.Default) {
            withTimeout(TEST_TIMEOUT) {
                var attemptsWithTwoInside = 0
                var rejections = 0
                repeat(RACE_ATTEMPTS) {
                    val source = GatedStream()
                    val receiver = CodecReceiver(source = source, codec = TestStringCodec)
                    val rejected = AtomicInteger(0)
                    val gate = AtomicBoolean(false)
                    val collectors =
                        (1..2).map {
                            thread(name = "receiver-collector-$it") {
                                runBlocking {
                                    while (!gate.get()) Thread.onSpinWait()
                                    try {
                                        receiver.receive().collect { }
                                    } catch (_: IllegalStateException) {
                                        rejected.incrementAndGet()
                                    }
                                }
                            }
                        }
                    Thread.sleep(1)
                    gate.set(true)
                    source.awaitReadersOrTimeout(expected = 2, timeout = RACE_OBSERVATION_WINDOW)
                    if (source.peakReadersInside.get() > 1) attemptsWithTwoInside++
                    if (rejected.get() > 0) rejections++
                    source.release()
                    collectors.forEach { it.join(THREAD_JOIN_MILLIS) }
                }
                println(
                    "[assumption] CodecReceiver concurrent receive(): $attemptsWithTwoInside/" +
                        "$RACE_ATTEMPTS had two collectors inside; $rejections/$RACE_ATTEMPTS rejected one",
                )
                assertEquals(0, attemptsWithTwoInside, "CodecReceiver must admit exactly one collector")
                assertTrue(rejections > 0, "no contention observed, so the zero above would be vacuous")
            }
        }

    /**
     * Records the parallelism this run actually had, because that number is what made the harness's
     * worst bug invisible.
     *
     * The Part A races originally blocked eight coroutines on a `CyclicBarrier` over
     * `Dispatchers.Default`. That passes on a developer laptop and **hangs forever** on a 4-vCPU CI
     * runner, where the pool has fewer workers than the barrier needs — and the enclosing
     * `withTimeout` cannot fire to rescue it, because its own resumption needs the same starved pool.
     * The evidence for "green" was gathered entirely on an 18-core Mac; CI wedged for 15 minutes and
     * died on a silence watchdog. Printing the core count means the next reader can tell at a glance
     * whether a green run was a meaningful one.
     *
     * `raceOnThreads` no longer depends on this number at all — each racer owns a dedicated platform
     * thread — so this is a record, not a guard. Verified by pinning a local run to
     * `-XX:ActiveProcessorCount=2`, below the racer count, where the whole suite still passes.
     */
    @Test
    fun recordsTheParallelismThisRunActuallyHad() {
        val cores = Runtime.getRuntime().availableProcessors()
        println("[assumption] availableProcessors=$cores, racers per contended test=$RACERS")
        assertTrue(
            cores >= 1,
            "a run with no reported processors would make every concurrency measurement here suspect",
        )
    }

    /**
     * Runs [block] on [count] **dedicated platform threads**, released together by a spin gate.
     *
     * ## Why not `async(Dispatchers.Default)` with a `CyclicBarrier`
     *
     * That is what these tests used to do, and it **wedged this PR's own CI**. `CyclicBarrier.await()`
     * blocks its carrier thread, and `Dispatchers.Default` caps CPU workers at `availableProcessors`
     * with no compensation for blocked ones. On a 4-vCPU runner, eight racers cannot all get a worker,
     * so the barrier never trips — and the enclosing `withTimeout` **cannot fire either**, because its
     * own cancellation resumption has to be dispatched onto the same starved pool. The result is an
     * unkillable hang, not a failure; the lane only died 15 minutes later on a silence watchdog.
     *
     * Measured both ways: a standalone repro passes at 8 and 18 cores and hangs permanently at
     * `-XX:ActiveProcessorCount=4`. A local reproduction of it here never terminated at all.
     *
     * Dedicated threads cannot starve — each racer owns its carrier — and the spin gate releases them
     * within nanoseconds of each other, which is tighter than park/unpark anyway. The join is checked
     * rather than best-effort, so a wedged racer fails loudly instead of costing a silent timeout.
     */

    private fun <T> raceOnThreads(
        count: Int,
        block: suspend () -> T,
    ): List<T> {
        val gate = AtomicBoolean(false)
        val results = MutableList<Any?>(count) { null }
        val threads =
            (0 until count).map { i ->
                thread(name = "racer-$i") {
                    runBlocking {
                        while (!gate.get()) Thread.onSpinWait()
                        results[i] = block()
                    }
                }
            }
        Thread.sleep(1)
        gate.set(true)
        threads.forEach { t ->
            t.join(THREAD_JOIN_MILLIS)
            check(!t.isAlive) {
                "racer ${t.name} did not finish within ${THREAD_JOIN_MILLIS}ms — the harness wedged, " +
                    "which must fail rather than pass quietly"
            }
        }
        @Suppress("UNCHECKED_CAST")
        return results as List<T>
    }

    private fun connectionOver(
        stream: ByteStream,
        scope: CoroutineScope,
    ): CodecConnection<String> =
        CodecConnection(
            stream = stream,
            codec = TestStringCodec,
            scope = scope,
            outboundCapacity = 8,
            overflowPolicy = OverflowPolicy.Suspend,
            config = TransportConfig(),
        )

    /**
     * A [ByteStream] that **parks** every reader inside `read()` until [release], and counts both the
     * readers concurrently inside it and its own closes.
     *
     * Parking is the whole point. A stream that returns [ReadResult.End] immediately lets a collector
     * pass straight through the guarded region, so two collectors completing tells you nothing about
     * whether they were ever concurrent. Holding them inside makes "two are past the guard at once" a
     * directly observable fact rather than an inference from timing.
     */
    private class GatedStream : ByteStream {
        val readersInside = AtomicInteger(0)
        val peakReadersInside = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        private val gate = CompletableDeferred<Unit>()

        override val isOpen: Boolean get() = closeCount.get() == 0
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(30.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(30.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            val inside = readersInside.incrementAndGet()
            peakReadersInside.updateAndGet { peak -> maxOf(peak, inside) }
            try {
                gate.await()
            } finally {
                readersInside.decrementAndGet()
            }
            return ReadResult.End
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            val remaining = buffer.remaining()
            repeat(remaining) { buffer.readByte() }
            return BytesWritten(remaining)
        }

        override suspend fun close() {
            closeCount.incrementAndGet()
        }

        fun release() {
            gate.complete(Unit)
        }

        /** Polls until [expected] readers are inside, or [timeout] elapses. Never throws on timeout. */
        fun awaitReadersOrTimeout(
            expected: Int,
            timeout: Duration,
        ) {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (peakReadersInside.get() < expected && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
        }
    }

    /**
     * A [ByteStream] that hands out an endless drip of single-byte chunks until [stop].
     *
     * One byte at a time on purpose: `TestStringCodec` needs two bytes before a frame can be drained,
     * so the chunks accumulate in the processor's deque instead of being consumed, which is what keeps
     * a concurrent `release()` iterating over a live, growing collection.
     */
    private class DrippingStream : ByteStream {
        val reads = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        private val readsAfterClose = AtomicInteger(0)

        @Volatile
        private var stopped = false

        override val isOpen: Boolean get() = closeCount.get() == 0
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(30.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(30.seconds)

        override suspend fun read(deadline: Duration): ReadResult {
            if (stopped) return ReadResult.End
            // Stay live for a bounded run of reads after close() is observed, then end. This is what
            // keeps a collector genuinely inside the processor while teardown runs, without leaving it
            // there forever — fixed code waits only for these reads to drain, unfixed code releases
            // the deque out from under them.
            if (closeCount.get() > 0 && readsAfterClose.incrementAndGet() > READS_AFTER_CLOSE) {
                return ReadResult.End
            }
            reads.incrementAndGet()
            val buffer = BufferFactory.Default.allocate(1)
            // 0xFF, not 0x00, and this is the difference between a real prober and a decorative one.
            // TestStringCodec reads a 2-byte length prefix: a drip of 0x00 declares a ZERO-length
            // frame, so the collector completed and CONSUMED a frame every two bytes and the deque
            // never grew past a chunk or two — there was almost nothing for release() to walk. With
            // 0xFF the declared length is 65535, `peekFrameSize` never reports Complete, and the
            // chunks genuinely accumulate. Measured: with 0x00 this prober caught the defect 0/300
            // in-suite; the window it was supposed to open did not exist.
            buffer.writeByte(0xFF.toByte())
            buffer.resetForRead()
            return ReadResult.Data(buffer)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            val remaining = buffer.remaining()
            repeat(remaining) { buffer.readByte() }
            return BytesWritten(remaining)
        }

        /**
         * Closing does **not** stop the drip, deliberately.
         *
         * A real transport ends its reads on close, and modelling that here is more faithful — but it
         * also destroys this harness's whole purpose. The defect under test is teardown running while a
         * collector is *actively* inside the stream processor, so the collector has to stay live across
         * the close. Making `close()` stop the drip let the collector exit immediately, closed the
         * window, and the test then passed against the unfixed code — a decorative test that proved
         * nothing. It is kept hostile on purpose; [stop] ends the run afterwards.
         */
        override suspend fun close() {
            closeCount.incrementAndGet()
        }

        fun stop() {
            stopped = true
        }

        fun awaitReadsOrTimeout(
            minimum: Int,
            timeout: Duration,
        ) {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (reads.get() < minimum && System.nanoTime() < deadline) Thread.onSpinWait()
        }
    }
}
