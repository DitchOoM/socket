package com.ditchoom.socket.udp

import com.ditchoom.socket.udp.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

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

    // Reference counting for auto-cleanup when all sockets are closed.
    // On Kotlin/Native, newSingleThreadContext creates a non-daemon thread
    // that prevents process exit. By cleaning up when the last socket closes,
    // we ensure the event loop thread is stopped and the process can terminate.
    private val activeSocketCount = AtomicInt(0)

    /**
     * Called when a socket is opened. Increments the active socket counter.
     */
    fun onSocketOpened() {
        activeSocketCount.incrementAndGet()
    }

    /**
     * Called when a socket is closed. Decrements the active socket counter.
     * When the last socket closes, automatically cleans up the io_uring
     * infrastructure (event loop thread, ring, dispatcher) so the process can exit.
     */
    fun onSocketClosed() {
        if (activeSocketCount.decrementAndGet() <= 0) {
            // Reset to 0 in case of underflow from double-close
            activeSocketCount.compareAndSet(-1, 0)
            cleanup()
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

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun getOrCreatePollerDispatcher(): CloseableCoroutineDispatcher {
        pollerDispatcherRef.value?.let { return it }
        val newDispatcher = newSingleThreadContext("io_uring-udp-poller")
        if (!pollerDispatcherRef.compareAndSet(null, newDispatcher)) {
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
     * Initialize io_uring ring with progressive flag fallback.
     *
     * Tries SINGLE_ISSUER + COOP_TASKRUN + DEFER_TASKRUN first (best performance),
     * falls back to fewer flags for older kernels.
     *
     * MUST be called on the event loop thread (SINGLE_ISSUER binds to creating thread).
     */
    private fun initRing(): CPointer<io_uring> {
        ringRef.value?.let { return it }

        val ptr = nativeHeap.alloc<io_uring>().ptr
        val params = nativeHeap.alloc<io_uring_params>()

        // Progressive fallback: best flags first, then fewer, then none
        val flagSets =
            listOf(
                IORING_SETUP_SINGLE_ISSUER or IORING_SETUP_COOP_TASKRUN or IORING_SETUP_DEFER_TASKRUN,
                IORING_SETUP_SINGLE_ISSUER or IORING_SETUP_COOP_TASKRUN,
                IORING_SETUP_SINGLE_ISSUER,
                0u,
            )

        var lastError = 0
        for (flags in flagSets) {
            // Zero out params for each attempt
            memset(params.ptr, 0, sizeOf<io_uring_params>().convert())
            params.flags = flags

            val ret = io_uring_queue_init_params(queueDepth.toUInt(), ptr, params.ptr)
            if (ret >= 0) {
                nativeHeap.free(params)
                ringRef.value = ptr
                return ptr
            }
            lastError = -ret
        }

        nativeHeap.free(params)
        nativeHeap.free(ptr)
        val errorMsg = strerror(lastError)?.toKString() ?: "Unknown error"
        throw IoUringUnavailableException(
            "Failed to initialize io_uring: $errorMsg (errno=$lastError). " +
                "This module requires Linux kernel 5.1+ with io_uring support. " +
                "Check your kernel version with 'uname -r'.",
        )
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
        val ring = initRing()
        setupEventfd(ring)

        // Plain HashMap — only accessed from this thread, no synchronization needed
        val pendingOps = HashMap<Long, PendingOperation>()

        val cqePtr = nativeHeap.alloc<CPointerVar<io_uring_cqe>>()
        val ts = nativeHeap.alloc<__kernel_timespec>()

        try {
            while (pollerStarted.value == 1) {
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
            }

            // Drain remaining channel requests so callers aren't left hanging
            while (true) {
                val request = submissionChannel.tryReceive().getOrNull() ?: break
                request.deferred.complete(-ECANCELED)
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
        ensurePollerStarted()

        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }

        // Send request to event loop via lock-free channel
        val request = SubmissionRequest(userData, deferred, deadline, prepareOp)
        submissionChannel.trySend(request)
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
            val fd = wakeupFd.value
            if (fd >= 0) {
                memScoped {
                    val buf = alloc<eventfd_tVar>()
                    buf.value = 1u
                    eventfd_write(fd, buf.value)
                }
            }

            // Wait for event loop to fully exit (it handles ring/eventfd/channel cleanup)
            val job = pollerJobRef.getAndSet(null)
            if (job != null) {
                runBlocking { job.join() }
            }

            // Safe now — event loop has exited and cleaned up ring resources
            val scope = pollerScopeRef.getAndSet(null)
            scope?.cancel()
            val dispatcher = pollerDispatcherRef.getAndSet(null)
            dispatcher?.close()
        }
}
