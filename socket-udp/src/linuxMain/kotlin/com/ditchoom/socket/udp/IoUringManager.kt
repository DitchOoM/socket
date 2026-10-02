package com.ditchoom.socket.udp

import com.ditchoom.socket.udp.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Thrown when the shared io_uring ring cannot be initialized (kernel < 5.1, or io_uring disabled).
 * The datagram substrate requires io_uring; there is no epoll fallback in this module.
 */
class IoUringUnavailableException(
    message: String,
) : RuntimeException(message)

/**
 * Pending operation state - tracks deferred and optional deadline.
 *
 * [cancelRequested] is set once the deadline has passed and an `io_uring_prep_cancel64` has
 * been submitted for this op. The op is NOT completed/removed on expiry — it stays in
 * `pendingOps` until the kernel produces its CQE (with `-ECANCELED`, or the real result if it
 * raced in). This preserves the invariant that a buffer handed to io_uring is only freed by the
 * caller after the kernel is done with it; completing on bare timeout let the caller free a
 * buffer the kernel still owned, so a later datagram wrote into freed memory (UAF / heap
 * corruption — the linuxX64 idle-timeout crash).
 */
private class PendingOperation(
    val deferred: CompletableDeferred<Int>,
    val deadline: TimeSource.Monotonic.ValueTimeMark?,
    var cancelRequested: Boolean = false,
)

/**
 * Request to submit an io_uring operation, sent from any thread to the event loop
 * via a lock-free Channel.
 */
@OptIn(ExperimentalForeignApi::class)
private class SubmissionRequest(
    val userData: Long,
    val deferred: CompletableDeferred<Int>,
    val deadline: TimeSource.Monotonic.ValueTimeMark?,
    val prepareOp: (sqe: CPointer<io_uring_sqe>, userData: Long) -> Unit,
)

/**
 * User_data value reserved for eventfd wakeup poll CQEs.
 * The event loop ignores completions with this value.
 */
private const val EVENTFD_USER_DATA = 0L

/**
 * Shared io_uring instance with single-threaded event loop.
 *
 * Lifted from root :socket's `IoUringUtils.kt` (the proven, UAF-correct engine) into `:socket-udp`
 * so the UDP datapath carries no TCP/TLS dependency. Kept near-verbatim — the buffer-lifetime fencing
 * (deadline → cancel, not fabricate-completion) is load-bearing and must not drift.
 *
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 *
 * Architecture:
 * - Single event loop thread owns the io_uring ring (no cross-thread submission)
 * - Coroutines send SubmissionRequests via a lock-free Channel
 * - eventfd wakes the event loop when new requests arrive while it's sleeping
 * - Event loop processes CQEs and resumes waiting coroutines on the same thread
 * - No Mutex, no futex overhead — all ring access is single-threaded
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private val ringRef = AtomicReference<CPointer<io_uring>?>(null)

    // process-global io_uring ring depth
    private val queueDepth: Int = 1024

    // Default max wait time when no operations have deadlines
    private val DEFAULT_POLL_TIMEOUT = 1.seconds

    // Unique ID generator for user_data (starts at 1; 0 is reserved for eventfd)
    private val nextUserDataCounter = AtomicLong(1L)

    // Reference counting: the last socket's close releases the ring and its eventfd once the event loop
    // is idle (see releaseIfIdle). The poller worker thread itself is kept (see cleanup).
    private val activeSocketCount = AtomicInt(0)

    /** Sockets currently counted as open; the last one's close releases the ring. */
    internal val activeSockets: Int get() = activeSocketCount.value

    /**
     * Called when a socket is opened. Increments the active socket counter.
     */
    fun onSocketOpened() {
        activeSocketCount.incrementAndGet()
    }

    /**
     * Called when a socket is closed. Decrements the active socket counter, and when the last socket
     * closes asks the event loop to release the ring if it is idle — see [releaseIfIdle].
     */
    fun onSocketClosed() {
        if (activeSocketCount.decrementAndGet() <= 0) {
            // Reset to 0 in case of underflow from double-close
            activeSocketCount.compareAndSet(-1, 0)
            releaseIfIdle()
        }
    }

    /**
     * The release a last-socket close asks of the event loop. The loop owns the answer because only it
     * knows what is in flight: a connect is not counted as a socket until it completes, and a socket can
     * open after the count reached zero, so a count of zero does not mean nothing is using the ring.
     */
    private sealed interface IdleReleaseRequest {
        object None : IdleReleaseRequest

        class Asked(
            val answer: CompletableDeferred<IdleRelease>,
        ) : IdleReleaseRequest
    }

    private enum class IdleRelease {
        /** Nothing was in flight and no socket was open: the loop exited and the ring is released. */
        Released,

        /** An operation was in flight or a socket had opened: the loop is still running. */
        Refused,
    }

    private val idleRelease = AtomicReference<IdleReleaseRequest>(IdleReleaseRequest.None)

    /** How the event loop ended, which decides what its `finally` owes the operations it leaves. */
    private sealed interface LoopExit {
        /** [cleanup] stopped it: everything pending or queued fails with `-ECANCELED`. */
        object Stopped : LoopExit

        /** It released an idle ring: nothing is pending, and anything queued since is the next start's. */
        class Idle(
            val answer: CompletableDeferred<IdleRelease>,
        ) : LoopExit
    }

    /**
     * Releases the ring after the last socket closed, unless the event loop still has work. Unlike
     * [cleanup], this never fails an operation: an operation in flight, or a socket opened since the
     * count reached zero, makes the loop refuse and keep running, and the ring is released on a later
     * last close.
     */
    private fun releaseIfIdle() =
        withLifecycleLock {
            if (activeSocketCount.value > 0 || pollerStarted.value != 1) return@withLifecycleLock
            val answer = CompletableDeferred<IdleRelease>()
            // Asked before the flag clears, so a loop that sees the flag clear always finds the request.
            idleRelease.value = IdleReleaseRequest.Asked(answer)
            pollerStarted.value = 0
            forceWake()
            if (runBlocking { answer.await() } == IdleRelease.Released) {
                pollerJobRef.getAndSet(null)?.let { runBlocking { it.join() } }
            }
        }

    /** Wakes the event loop whether or not it is marked sleeping, so it observes a stop request now. */
    private fun forceWake() {
        val fd = wakeupFd.value
        if (fd >= 0) {
            memScoped {
                val buf = alloc<eventfd_tVar>()
                buf.value = 1u
                eventfd_write(fd, buf.value)
            }
        }
    }

    // Lock-free channel for submission requests from any thread to event loop
    private val submissionChannel = Channel<SubmissionRequest>(Channel.UNLIMITED)

    // eventfd for waking the event loop when it's sleeping in io_uring_wait_cqe_timeout
    private val wakeupFd = AtomicInt(-1)

    // 1 = event loop is about to sleep or sleeping in io_uring_wait_cqe_timeout
    private val pollerSleeping = AtomicInt(0)

    // Dedicated event loop thread - lazily initialized and reinitializable after cleanup
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val pollerDispatcherRef = AtomicReference<CloseableCoroutineDispatcher?>(null)
    private val pollerScopeRef = AtomicReference<CoroutineScope?>(null)
    private val pollerJobRef = AtomicReference<Job?>(null)
    private val pollerStarted = AtomicInt(0) // 0 = not started, 1 = started

    // Serializes poller start ([ensurePollerStarted]) against poller stop ([cleanup]). Without it, a
    // start racing the last-socket-close can flip [pollerStarted] back to 1 *after* cleanup cleared it,
    // so the running event loop never observes the stop and cleanup's `runBlocking { job.join() }` blocks
    // forever — the intermittent linuxX64 hang seen in the WebTransport suite. This guards only the cold
    // start/stop transitions; the per-op I/O hot path takes the lock-free fast return in
    // [ensurePollerStarted], so ring throughput is unchanged (the "no Mutex on ring access" invariant
    // holds). Process-lifetime singleton mutex: allocated once, never freed.
    private val lifecycleMutex: CPointer<pthread_mutex_t> =
        nativeHeap.alloc<pthread_mutex_t>().ptr.also { pthread_mutex_init(it, null) }

    private inline fun <T> withLifecycleLock(block: () -> T): T {
        pthread_mutex_lock(lifecycleMutex)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(lifecycleMutex)
        }
    }

    /**
     * How many poller worker threads this manager has actually created.
     *
     * A regression guard, and deliberately not a wall clock: the worker is kept across [cleanup], so a
     * bind/close/cleanup cycle must not allocate a new one. Closing the dispatcher there would make this
     * grow once per cycle, which a test can assert exactly — where the ~100 ms it costs could only be
     * asserted with a timing budget.
     */
    internal val pollerDispatchersCreated = AtomicInt(0)

    /**
     * Rings this manager has created and released over the process lifetime. `created - released`
     * is the number alive right now, and is what turns an `io_uring_setup` `ENOMEM` from "the kernel
     * said no" into a named leak or a named non-leak: every ring is charged to the process
     * until `io_uring_queue_exit`, so a count that grows across bind/close cycles is the defect, and a
     * count of one at the failure is proof the budget was exhausted by something else.
     */
    internal val ringsCreated = AtomicInt(0)
    internal val ringsReleased = AtomicInt(0)

    /**
     * This manager's state plus the host's, for a failure report — the ledger a resource error
     * needs to be read as anything other than "try again". Failure path only.
     */
    internal fun diagnosticSnapshot(): String =
        buildString {
            appendLine("io_uring manager (socket-udp):")
            appendLine(
                "  rings: created=${ringsCreated.value} released=${ringsReleased.value} " +
                    "live=${ringsCreated.value - ringsReleased.value} queueDepth=$queueDepth",
            )
            appendLine(
                "  poller: started=${pollerStarted.value} sleeping=${pollerSleeping.value} " +
                    "workersCreated=${pollerDispatchersCreated.value} activeSockets=${activeSocketCount.value}",
            )
            append(ioUringHostReport())
        }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun getOrCreatePollerDispatcher(): CloseableCoroutineDispatcher {
        pollerDispatcherRef.value?.let { return it }
        val newDispatcher = newSingleThreadContext("io_uring-udp-poller")
        if (pollerDispatcherRef.compareAndSet(null, newDispatcher)) {
            pollerDispatchersCreated.incrementAndGet()
        } else {
            newDispatcher.close()
        }
        return pollerDispatcherRef.value!!
    }

    private fun getOrCreatePollerScope(): CoroutineScope {
        pollerScopeRef.value?.let { return it }
        val dispatcher = getOrCreatePollerDispatcher()
        val newScope = CoroutineScope(dispatcher + SupervisorJob())
        if (!pollerScopeRef.compareAndSet(null, newScope)) {
            newScope.cancel()
        }
        return pollerScopeRef.value!!
    }

    /**
     * The ring, created through [setUpIoUring] if there is none yet.
     *
     * MUST be called on the event loop thread (SINGLE_ISSUER binds to creating thread).
     */
    private fun initRing(): CPointer<io_uring> {
        ringRef.value?.let { return it }

        val ptr = nativeHeap.alloc<io_uring>().ptr
        val params = nativeHeap.alloc<io_uring_params>()
        val setup =
            setUpIoUring({ micros -> usleep(micros.toUInt()) }) { flags ->
                memset(params.ptr, 0, sizeOf<io_uring_params>().convert())
                params.flags = flags
                io_uring_queue_init_params(queueDepth.toUInt(), ptr, params.ptr)
            }
        nativeHeap.free(params)
        return when (setup) {
            RingSetup.Created -> {
                ringsCreated.incrementAndGet()
                ringRef.value = ptr
                ptr
            }
            is RingSetup.Refused -> {
                nativeHeap.free(ptr)
                val refused = setup.final.errno
                throw IoUringUnavailableException(
                    "Failed to initialize io_uring: ${strerror(refused)?.toKString() ?: "Unknown error"} " +
                        "(errno=$refused). This module requires Linux kernel 5.1+ with io_uring support. " +
                        "Attempts: ${setup.describe()}\n" +
                        diagnosticSnapshot(),
                )
            }
        }
    }

    /**
     * Ends a poller start whose ring could not be set up: the start is withdrawn, so the next operation
     * sets up a ring afresh, and every submission already queued fails with [cause].
     */
    private fun abandonStart(cause: IoUringUnavailableException) {
        pollerStarted.compareAndSet(1, 0)
        while (true) {
            val request = submissionChannel.tryReceive().getOrNull() ?: break
            request.deferred.completeExceptionally(cause)
        }
    }

    /**
     * Get the ring reference. All operations go through the submission channel,
     * so the ring is always initialized by the event loop thread via initRing().
     */
    fun getRing(): CPointer<io_uring> = ringRef.value ?: throw IoUringUnavailableException("IoUringManager not initialized")

    /**
     * Create eventfd and register multi-shot poll on it.
     * Called once during event loop startup, on the event loop thread.
     */
    private fun setupEventfd(ring: CPointer<io_uring>) {
        val fd = eventfd(0u, EFD_NONBLOCK)
        if (fd < 0) {
            throw IoUringUnavailableException("Failed to create eventfd: errno=$errno")
        }
        wakeupFd.value = fd

        // Register multi-shot poll on eventfd so any write wakes the event loop
        val sqe =
            io_uring_get_sqe(ring)
                ?: throw IoUringUnavailableException("Failed to get SQE for eventfd poll registration")
        io_uring_prep_poll_multishot(sqe, fd, POLLIN.toUInt())
        io_uring_sqe_set_data64(sqe, EVENTFD_USER_DATA.toULong())
        io_uring_submit(ring)
    }

    /**
     * Wake the event loop if it's sleeping in io_uring_wait_cqe_timeout.
     * Uses eventfd_write which is a single syscall.
     * Only writes if pollerSleeping == 1 to avoid unnecessary syscalls.
     */
    private fun wakePoller() {
        if (pollerSleeping.value == 1) {
            val fd = wakeupFd.value
            if (fd >= 0) {
                memScoped {
                    val buf = alloc<eventfd_tVar>()
                    buf.value = 1u
                    eventfd_write(fd, buf.value)
                }
            }
        }
    }

    /**
     * Drain the eventfd counter (reset it after wakeup).
     * Called on the event loop thread after processing a wakeup CQE.
     */
    private fun drainEventfd() {
        val fd = wakeupFd.value
        if (fd >= 0) {
            memScoped {
                val buf = alloc<eventfd_tVar>()
                eventfd_read(fd, buf.ptr)
            }
        }
    }

    /**
     * Ensure the event loop thread is running.
     * Called lazily on first operation.
     */
    private fun ensurePollerStarted() {
        // Hot path: loop already running — stays lock-free so per-submission cost is unchanged.
        if (pollerStarted.value == 1) return
        // Cold path: serialize the (re)start against cleanup() so it cannot resurrect pollerStarted
        // after cleanup cleared it (which would strand the running loop and hang cleanup's join).
        withLifecycleLock {
            if (pollerStarted.compareAndSet(0, 1)) {
                val scope = getOrCreatePollerScope()
                val job = scope.launch { eventLoop() }
                pollerJobRef.value = job
            }
        }
    }

    /**
     * Calculate time until the earliest deadline, or default timeout if none.
     * Only called from the event loop thread (no synchronization needed).
     */
    private fun calculateNextTimeout(pendingOps: HashMap<Long, PendingOperation>): Duration {
        var earliest: Duration? = null

        for ((_, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            // Already cancel-requested: its deadline is moot, we're just awaiting the kernel CQE.
            // Counting it would peg the wait at ZERO and busy-spin until that CQE lands.
            if (op.cancelRequested) continue
            val remaining = -deadline.elapsedNow()
            if (earliest == null || remaining < earliest) {
                earliest = remaining
            }
        }

        return when {
            earliest == null -> DEFAULT_POLL_TIMEOUT
            earliest.isNegative() -> Duration.ZERO
            else -> earliest
        }
    }

    /**
     * Handle expired operations. On deadline, submit an `io_uring_prep_cancel64` for the op's
     * userData rather than completing its deferred — the op stays in [pendingOps] until the kernel
     * delivers its CQE (which the normal CQE path completes with `-ECANCELED`, or the real result
     * if a completion raced the deadline). The kernel may still be holding the op's buffer pointer;
     * fabricating a `-ETIMEDOUT` completion here let the caller free that buffer while the recv was
     * still in flight, so a later datagram wrote into freed memory — the UAF behind the linuxX64
     * idle-timeout crash. Mirrors the coroutine-cancellation path in [submitAndWait].
     *
     * Only called from the event loop thread (no synchronization needed).
     * Returns true if any cancel SQEs were prepared (caller must `io_uring_submit`).
     */
    private fun processExpiredOperations(
        ring: CPointer<io_uring>,
        pendingOps: HashMap<Long, PendingOperation>,
    ): Boolean {
        var submittedCancel = false
        for ((userData, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            if (op.cancelRequested || op.deferred.isCompleted) continue
            if (!deadline.hasPassedNow()) continue
            val sqe = io_uring_get_sqe(ring) ?: break // ring full — retry next iteration
            io_uring_prep_cancel64(sqe, userData.toULong(), 0)
            // The cancel's own CQE carries a fresh userData → ignored by the CQE dispatcher
            // (pendingOps.remove returns null). The original op's CQE completes the deferred.
            io_uring_sqe_set_data64(sqe, nextUserData().toULong())
            op.cancelRequested = true
            timeoutCancelSubmitCount.incrementAndGet()
            submittedCancel = true
        }
        return submittedCancel
    }

    /**
     * Drain submission channel and prepare SQEs.
     * Only called from the event loop thread.
     * Returns true if any requests were submitted.
     */
    private fun drainSubmissionChannel(
        ring: CPointer<io_uring>,
        pendingOps: HashMap<Long, PendingOperation>,
    ): Boolean {
        var submitted = false
        while (true) {
            val request = submissionChannel.tryReceive().getOrNull() ?: break

            // If the deferred is already completed (e.g. cancelled), skip submission
            if (request.deferred.isCompleted) continue

            // Register in pendingOps
            pendingOps[request.userData] = PendingOperation(request.deferred, request.deadline)

            val sqe = io_uring_get_sqe(ring)
            if (sqe == null) {
                // Ring full — complete with error. Caller will see this as a failure.
                request.deferred.complete(-EBUSY)
                pendingOps.remove(request.userData)
                continue
            }

            request.prepareOp(sqe, request.userData)
            io_uring_sqe_set_data64(sqe, request.userData.toULong())
            submitted = true
        }
        return submitted
    }

    /**
     * Main event loop - runs on dedicated thread.
     *
     * All ring access happens on this single thread:
     * 1. Drain submission channel → prepare SQEs
     * 2. Batch submit to kernel
     * 3. Sleep in io_uring_wait_cqe_timeout
     * 4. Process CQEs → resume waiting coroutines
     * 5. Check expired operations
     */
    private fun eventLoop() {
        val ring =
            try {
                initRing().also(::setupEventfd)
            } catch (e: IoUringUnavailableException) {
                abandonStart(e)
                // A release asked while this start was setting up finds no ring to release.
                when (val asked = idleRelease.getAndSet(IdleReleaseRequest.None)) {
                    IdleReleaseRequest.None -> Unit
                    is IdleReleaseRequest.Asked -> asked.answer.complete(IdleRelease.Released)
                }
                return
            }

        // Plain HashMap — only accessed from this thread, no synchronization needed
        val pendingOps = HashMap<Long, PendingOperation>()

        val cqePtr = nativeHeap.alloc<CPointerVar<io_uring_cqe>>()
        val ts = nativeHeap.alloc<__kernel_timespec>()

        // A holder, not a `var`: Kotlin/Native smart-cast a `var` read in `finally` to its initial
        // `Stopped` even after the loop set it to `Idle`, and the cast then threw ClassCastException.
        val exit = AtomicReference<LoopExit>(LoopExit.Stopped)
        try {
            while (true) {
                // 0. A stop: cleanup() ends the loop now; a last-socket close asks it to release the
                //    ring only if idle. Requests queued before the stop are this loop's, so they are
                //    taken in before deciding — a request queued after it was made by a submitter that
                //    saw the flag clear, and that submitter starts the next loop.
                if (pollerStarted.value == 0) {
                    when (val asked = idleRelease.getAndSet(IdleReleaseRequest.None)) {
                        IdleReleaseRequest.None -> break
                        is IdleReleaseRequest.Asked -> {
                            exit.value = LoopExit.Idle(asked.answer)
                            if (drainSubmissionChannel(ring, pendingOps)) {
                                io_uring_submit(ring)
                            }
                            if (pendingOps.isEmpty() && activeSocketCount.value == 0) break
                            exit.value = LoopExit.Stopped
                            pollerStarted.value = 1
                            asked.answer.complete(IdleRelease.Refused)
                        }
                    }
                }

                // 1. Mark as sleeping FIRST — any request arriving after the drain
                //    will see this flag and write to eventfd, waking us from step 4.
                //    This eliminates the race between drain and sleep.
                pollerSleeping.value = 1

                // 2. Drain submission channel and prepare SQEs
                val hasNewSubmissions = drainSubmissionChannel(ring, pendingOps)

                // 3. Batch submit all prepared SQEs at once
                if (hasNewSubmissions) {
                    io_uring_submit(ring)
                }

                // 4. Calculate timeout from earliest deadline
                val timeout = calculateNextTimeout(pendingOps)
                ts.tv_sec = timeout.inWholeSeconds
                ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

                // 5. Sleep in io_uring_wait_cqe_timeout — eventfd CQE wakes us if
                //    new requests arrived after step 2
                val waitRet = io_uring_wait_cqe_timeout(ring, cqePtr.ptr, ts.ptr)
                pollerSleeping.value = 0

                // 5. Process expired operations — submit cancels for any that timed out
                if (processExpiredOperations(ring, pendingOps)) {
                    io_uring_submit(ring)
                }

                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    continue
                }
                if (waitRet < 0) {
                    // EINTR or other error — drain channel on next iteration
                    continue
                }

                // 6. Process all available CQEs
                do {
                    val cqeVal = cqePtr.value ?: break
                    val userData = io_uring_cqe_get_data64(cqeVal).toLong()
                    val result = cqeVal.pointed.res
                    val flags = cqeVal.pointed.flags
                    io_uring_cqe_seen(ring, cqeVal)

                    if (userData == EVENTFD_USER_DATA) {
                        // eventfd wakeup — drain the counter
                        drainEventfd()
                        // If IORING_CQE_F_MORE is NOT set, the multi-shot poll was terminated
                        // and we need to re-register it
                        if (flags.toInt() and IORING_CQE_F_MORE.toInt() == 0) {
                            val pollSqe = io_uring_get_sqe(ring)
                            if (pollSqe != null) {
                                val fd = wakeupFd.value
                                if (fd >= 0) {
                                    io_uring_prep_poll_multishot(pollSqe, fd, POLLIN.toUInt())
                                    io_uring_sqe_set_data64(pollSqe, EVENTFD_USER_DATA.toULong())
                                    io_uring_submit(ring)
                                }
                            }
                        }
                        continue
                    }

                    // Dispatch to waiting coroutine
                    val op = pendingOps.remove(userData)
                    if (op != null && !op.deferred.isCompleted) {
                        // A timeout-cancelled op reports -ETIMEDOUT to the caller (preserving
                        // timeout semantics), unless a datagram actually arrived before the cancel
                        // landed (result >= 0) — then deliver the real result. Crucially the deferred
                        // completes only now, on the CQE, so the caller frees its buffer only after
                        // the kernel is done with it (no UAF).
                        val toComplete = if (op.cancelRequested && result < 0) -ETIMEDOUT else result
                        op.deferred.complete(toComplete)
                    }
                } while (io_uring_peek_cqe(ring, cqePtr.ptr) >= 0)
            }
        } finally {
            nativeHeap.free(ts)
            nativeHeap.free(cqePtr)

            // Complete any remaining pending ops
            pendingOps.values.forEach { it.deferred.complete(-ECANCELED) }
            pendingOps.clear()

            // Close eventfd (must happen on event loop thread before ring teardown)
            val fd = wakeupFd.getAndSet(-1)
            if (fd >= 0) close(fd)

            // Destroy ring — MUST happen on event loop thread to avoid use-after-free
            // race with io_uring_wait_cqe_timeout
            val ptr = ringRef.getAndSet(null)
            ptr?.let {
                io_uring_queue_exit(it)
                nativeHeap.free(it)
                ringsReleased.incrementAndGet()
            }

            when (val ended = exit.value) {
                // Drain remaining channel requests so callers aren't left hanging
                LoopExit.Stopped ->
                    while (true) {
                        val request = submissionChannel.tryReceive().getOrNull() ?: break
                        request.deferred.complete(-ECANCELED)
                    }
                is LoopExit.Idle -> ended.answer.complete(IdleRelease.Released)
            }
        }
    }

    /**
     * Generate a unique user_data value for an operation.
     */
    fun nextUserData(): Long = nextUserDataCounter.incrementAndGet()

    /**
     * Submit an io_uring operation and wait for its completion.
     *
     * The calling coroutine suspends until the operation completes or times out.
     * Submission happens via lock-free Channel + eventfd wakeup — no Mutex/futex.
     *
     * @param prepareOp Function to prepare the SQE (called on the event loop thread)
     * @param timeout Optional timeout for the operation
     * @return The result code from the CQE (positive for success, negative for error)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun submitAndWait(
        timeout: Duration? = null,
        prepareOp: (sqe: CPointer<io_uring_sqe>, userData: Long) -> Unit,
    ): Int {
        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }

        // Queue first, then ensure the loop: a loop that stops (cleanup, or a ring it could not set
        // up) clears pollerStarted before its last drain, so a request queued before the check is
        // either drained by that loop or seen by the loop this check starts. Checking first leaves a
        // window in which the request lands after the last drain and waits on no loop at all.
        val request = SubmissionRequest(userData, deferred, deadline, prepareOp)
        submissionChannel.trySend(request)
        ensurePollerStarted()
        wakePoller()

        // Suspend until event loop dispatches our completion (or timeout).
        // On cancellation, submit io_uring_prep_cancel64 and wait for the kernel
        // to finish with any buffer pointers before letting CancellationException propagate.
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            // Kernel op may still be in flight. Cancel it and wait for the CQE
            // so any buffer pointers are no longer accessed by the kernel.
            submitNoWaitUnsafe { sqe ->
                io_uring_prep_cancel64(sqe, userData.toULong(), 0)
            }
            withContext(NonCancellable) {
                try {
                    withTimeout(100) { deferred.await() }
                } catch (_: Exception) {
                    // Timeout or completion error — kernel op is done either way
                }
            }
            throw e
        }
    }

    /**
     * Submit an operation without waiting - non-blocking version for cancellation.
     *
     * This is called from [invokeOnCancellation] handlers which run synchronously
     * during coroutine cancellation. Uses Channel.trySend which is non-suspend safe.
     */
    fun submitNoWaitUnsafe(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        if (pollerStarted.value != 1) return
        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val request =
            SubmissionRequest(userData, deferred, null) { sqe, _ ->
                prepareOp(sqe)
            }
        submissionChannel.trySend(request)
        wakePoller()
    }

    /**
     * Test-observable count of cancel SQEs submitted by [processExpiredOperations] when an op's
     * deadline passes.
     */
    internal val timeoutCancelSubmitCount = AtomicInt(0)

    /**
     * Cleanup resources. Call when shutting down.
     *
     * Wakes the event loop via eventfd, waits for it to exit, then tears down
     * the ring and dispatcher.
     *
     * After cleanup, the IoUringManager can be reused - new operations
     * will reinitialize the ring and event loop thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cleanup() =
        withLifecycleLock {
            val wasStarted = pollerStarted.getAndSet(0) == 1
            if (!wasStarted) return@withLifecycleLock

            // Force-wake the event loop (bypass pollerSleeping check).
            // After setting pollerStarted=0 above, the event loop will check the
            // flag and exit. Because we hold lifecycleMutex, no concurrent
            // ensurePollerStarted() can flip pollerStarted back to 1 before the loop
            // observes the 0, so the loop is guaranteed to terminate and the join
            // below cannot block forever. Ring, eventfd, and channel cleanup happen
            // in the event loop's finally block — no cross-thread resource teardown.
            forceWake()

            // Wait for event loop to fully exit (it handles ring/eventfd/channel cleanup)
            val job = pollerJobRef.getAndSet(null)
            if (job != null) {
                runBlocking { job.join() }
            }

            // The poller's scope and its worker thread are deliberately KEPT.
            //
            // Everything the kernel knows about is already gone: the event loop's own `finally` closed
            // the eventfd, destroyed the ring and drained the pending ops before the join above
            // returned. A `scope.cancel()` + `dispatcher.close()` here would cost a flat **~100 ms** on
            // every last-socket close, and none of it is io_uring: on Kotlin/Native
            // `CloseableCoroutineDispatcher.close()` blocks the caller until the backing worker
            // terminates, and the worker only notices shutdown after its own ~100 ms bounded park
            // expires (closing a dispatcher whose worker never ran a task is ~1 µs).
            //
            // A started `newSingleThreadContext` worker does not keep the process alive — a linuxX64
            // binary that leaks one exits in single-digit ms with code 0 — so one idle worker thread
            // is retained for the process lifetime, and because the refs are kept rather than nulled,
            // [getOrCreatePollerDispatcher] reuses it: a bind/close cycle allocates no thread either.
            // [pollerDispatchersCreated] is the regression guard on exactly that, and it needs no wall
            // clock to fire.
        }
}
