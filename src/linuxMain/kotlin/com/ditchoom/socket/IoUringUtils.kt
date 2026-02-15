package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Pending operation state - tracks deferred and optional deadline.
 */
private data class PendingOperation(
    val deferred: CompletableDeferred<Int>,
    val deadline: TimeSource.Monotonic.ValueTimeMark?,
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
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 *
 * Architecture:
 * - Single event loop thread owns the io_uring ring (no cross-thread submission)
 * - Coroutines send SubmissionRequests via a lock-free Channel
 * - eventfd wakes the event loop when new requests arrive while it's sleeping
 * - Event loop processes CQEs and resumes waiting coroutines on the same thread
 * - No Mutex, no futex overhead — all ring access is single-threaded
 *
 * This design uses exactly ONE thread for all socket I/O in the application,
 * regardless of how many concurrent operations are in flight.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private val ringRef = AtomicReference<CPointer<io_uring>?>(null)

    // Configuration is read from PlatformSocketConfig
    private val queueDepth: Int
        get() = PlatformSocketConfig.ioQueueDepth

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

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun getOrCreatePollerDispatcher(): CloseableCoroutineDispatcher {
        pollerDispatcherRef.value?.let { return it }
        val newDispatcher = newSingleThreadContext("io_uring-poller")
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
        val flagSets = listOf(
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
        throw SocketException(
            "Failed to initialize io_uring: $errorMsg (errno=$lastError). " +
                "This library requires Linux kernel 5.1+ with io_uring support. " +
                "Check your kernel version with 'uname -r'.",
        )
    }

    /**
     * Get the ring reference (for use by submitNoWaitUnsafe which runs from any thread).
     * For normal operations, use initRing() on the event loop thread.
     */
    fun getRing(): CPointer<io_uring> {
        ringRef.value?.let { return it }
        // Fallback: init without SINGLE_ISSUER flags (called from non-event-loop thread)
        val ptr = nativeHeap.alloc<io_uring>().ptr
        val ret = io_uring_queue_init(queueDepth.toUInt(), ptr, 0u)
        if (ret < 0) {
            nativeHeap.free(ptr)
            val errorMsg = strerror(-ret)?.toKString() ?: "Unknown error"
            throw SocketException(
                "Failed to initialize io_uring: $errorMsg (errno=${-ret}). " +
                    "This library requires Linux kernel 5.1+ with io_uring support. " +
                    "Check your kernel version with 'uname -r'.",
            )
        }
        if (!ringRef.compareAndSet(null, ptr)) {
            io_uring_queue_exit(ptr)
            nativeHeap.free(ptr)
        }
        return ringRef.value!!
    }

    /**
     * Create eventfd and register multi-shot poll on it.
     * Called once during event loop startup, on the event loop thread.
     */
    private fun setupEventfd(ring: CPointer<io_uring>) {
        val fd = eventfd(0u, EFD_NONBLOCK)
        if (fd < 0) {
            throw SocketException("Failed to create eventfd: errno=$errno")
        }
        wakeupFd.value = fd

        // Register multi-shot poll on eventfd so any write wakes the event loop
        val sqe = io_uring_get_sqe(ring)
            ?: throw SocketException("Failed to get SQE for eventfd poll registration")
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
        if (pollerStarted.compareAndSet(0, 1)) {
            val scope = getOrCreatePollerScope()
            val job = scope.launch { eventLoop() }
            pollerJobRef.value = job
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
     * Check for expired operations and complete them with -ETIMEDOUT.
     * Only called from the event loop thread (no synchronization needed).
     */
    private fun processExpiredOperations(pendingOps: HashMap<Long, PendingOperation>) {
        val iterator = pendingOps.iterator()
        while (iterator.hasNext()) {
            val (_, op) = iterator.next()
            val deadline = op.deadline ?: continue
            if (deadline.hasPassedNow() && !op.deferred.isCompleted) {
                op.deferred.complete(-ETIMEDOUT)
                iterator.remove()
            }
        }
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

                // 5. Process expired operations
                processExpiredOperations(pendingOps)

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
                        op.deferred.complete(result)
                    }
                } while (io_uring_peek_cqe(ring, cqePtr.ptr) >= 0)
            }
        } finally {
            nativeHeap.free(ts)
            nativeHeap.free(cqePtr)

            // Complete any remaining pending ops
            pendingOps.values.forEach { it.deferred.complete(-ECANCELED) }
            pendingOps.clear()
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
     * Allocate a user_data value and register it for an upcoming operation.
     * This allows the caller to know the userData before submission, enabling
     * proper cancellation handling.
     *
     * @return Pair of (userData, deferred) for tracking the operation
     */
    fun registerOperation(timeout: Duration? = null): Pair<Long, CompletableDeferred<Int>> {
        ensurePollerStarted()

        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        // Deadline is captured when the request is submitted via submitRegistered
        return userData to deferred
    }

    /**
     * Submit a pre-registered operation. Use with [registerOperation] when you need
     * to know the userData before submission (e.g., for cancellation).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun submitRegistered(
        userData: Long,
        deferred: CompletableDeferred<Int>,
        timeout: Duration? = null,
        prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit,
    ): Int {
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }

        // Wrap the single-arg prepareOp to match SubmissionRequest's two-arg signature
        val request = SubmissionRequest(userData, deferred, deadline) { sqe, _ ->
            prepareOp(sqe)
        }
        submissionChannel.trySend(request)
        wakePoller()

        try {
            return deferred.await()
        } catch (e: CancellationException) {
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
     * Submit an operation without waiting (fire-and-forget).
     * Used for cancel operations. This is a suspend function.
     */
    suspend fun submitNoWait(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val request = SubmissionRequest(userData, deferred, null) { sqe, _ ->
            prepareOp(sqe)
        }
        submissionChannel.trySend(request)
        wakePoller()
        // Fire-and-forget: don't wait for completion
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
        val request = SubmissionRequest(userData, deferred, null) { sqe, _ ->
            prepareOp(sqe)
        }
        submissionChannel.trySend(request)
        wakePoller()
    }

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
    fun cleanup() {
        val wasStarted = pollerStarted.getAndSet(0) == 1

        if (wasStarted) {
            val job = pollerJobRef.getAndSet(null)
            job?.cancel()

            // Wake the event loop so it checks pollerStarted and exits
            wakePoller()

            runBlocking {
                try {
                    withTimeout(200.milliseconds) {
                        job?.join()
                    }
                } catch (e: Exception) {
                    // Timeout is fine, poller will exit eventually
                }
            }
        }

        // Cancel and reset scope
        val scope = pollerScopeRef.getAndSet(null)
        scope?.cancel()

        // Close and reset dispatcher
        val dispatcher = pollerDispatcherRef.getAndSet(null)
        dispatcher?.close()

        // Close eventfd
        val fd = wakeupFd.getAndSet(-1)
        if (fd >= 0) {
            close(fd)
        }

        // Cleanup ring
        val ptr = ringRef.getAndSet(null)
        ptr?.let {
            io_uring_queue_exit(it)
            nativeHeap.free(it)
        }

        // Drain and cancel any remaining requests in the channel
        while (true) {
            val request = submissionChannel.tryReceive().getOrNull() ?: break
            request.deferred.complete(-ECANCELED)
        }
    }
}

/**
 * Maps POSIX errno values to appropriate socket exceptions.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun throwSocketException(operation: String): Nothing {
    val errorCode = errno
    val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
    val message = "$operation failed: $errorMessage (errno=$errorCode)"

    throw when (errorCode) {
        ECONNREFUSED, ECONNRESET, ECONNABORTED -> SocketException(message)
        ETIMEDOUT -> SocketException("$operation timed out")
        ENOTCONN, EPIPE, ESHUTDOWN -> SocketClosedException(message)
        EHOSTUNREACH, ENETUNREACH -> SocketException(message)
        else -> SocketException(message)
    }
}

/**
 * Throw exception from a negative result code.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun throwFromResult(
    result: Int,
    operation: String,
): Nothing {
    val errorCode = -result
    val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
    val message = "$operation failed: $errorMessage (errno=$errorCode)"

    throw when (errorCode) {
        ECONNREFUSED, ECONNRESET, ECONNABORTED -> SocketException(message)
        ETIMEDOUT -> SocketException("$operation timed out")
        ENOTCONN, EPIPE, ESHUTDOWN -> SocketClosedException(message)
        EHOSTUNREACH, ENETUNREACH -> SocketException(message)
        else -> SocketException(message)
    }
}

/**
 * Check if an operation succeeded, throw exception if not.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun checkSocketResult(
    result: Int,
    operation: String,
): Int {
    if (result < 0) {
        throwSocketException(operation)
    }
    return result
}

/**
 * Set socket to non-blocking mode.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun setNonBlocking(sockfd: Int) {
    val flags = fcntl(sockfd, F_GETFL, 0)
    checkSocketResult(flags, "fcntl(F_GETFL)")
    checkSocketResult(fcntl(sockfd, F_SETFL, flags or O_NONBLOCK), "fcntl(F_SETFL)")
}

/**
 * Disable Nagle's algorithm for low-latency sends.
 * Without this, small writes are delayed ~200ms waiting for ACKs (Nagle + delayed ACK interaction).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun setTcpNoDelay(sockfd: Int) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = 1
        checkSocketResult(
            setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, optval.ptr, sizeOf<IntVar>().convert()),
            "setsockopt(TCP_NODELAY)",
        )
    }
}

/**
 * Enable SO_REUSEADDR on a socket.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun setReuseAddr(sockfd: Int) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = 1
        checkSocketResult(
            setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, optval.ptr, sizeOf<IntVar>().convert()),
            "setsockopt(SO_REUSEADDR)",
        )
    }
}

/**
 * Apply SocketOptions to a socket fd.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applySocketOptions(
    sockfd: Int,
    options: SocketOptions,
) {
    if (options.tcpNoDelay == true) setTcpNoDelay(sockfd)
    if (options.reuseAddress == true) setReuseAddr(sockfd)
    if (options.keepAlive == true) setKeepAlive(sockfd)
    options.receiveBuffer?.let { setSocketReceiveBuffer(sockfd, it) }
    options.sendBuffer?.let { setSocketSendBuffer(sockfd, it) }
}

@OptIn(ExperimentalForeignApi::class)
private fun setKeepAlive(sockfd: Int) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = 1
        checkSocketResult(
            setsockopt(sockfd, SOL_SOCKET, SO_KEEPALIVE, optval.ptr, sizeOf<IntVar>().convert()),
            "setsockopt(SO_KEEPALIVE)",
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun setSocketReceiveBuffer(
    sockfd: Int,
    size: Int,
) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = size
        checkSocketResult(
            setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, optval.ptr, sizeOf<IntVar>().convert()),
            "setsockopt(SO_RCVBUF)",
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun setSocketSendBuffer(
    sockfd: Int,
    size: Int,
) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = size
        checkSocketResult(
            setsockopt(sockfd, SOL_SOCKET, SO_SNDBUF, optval.ptr, sizeOf<IntVar>().convert()),
            "setsockopt(SO_SNDBUF)",
        )
    }
}

/**
 * Get local port number from socket (supports both IPv4 and IPv6).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getLocalPort(sockfd: Int): Int {
    memScoped {
        val addr = alloc<sockaddr_storage>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_storage>().convert()

        val sockaddrPtr = addr.ptr.reinterpret<sockaddr>()
        if (socket_getsockname(sockfd, sockaddrPtr, addrLen.ptr) == 0) {
            return getPortFromSockaddr(sockaddrPtr, addr.ss_family.toInt())
        }
    }
    return -1
}

/**
 * Get remote port number from socket (supports both IPv4 and IPv6).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getRemotePort(sockfd: Int): Int {
    memScoped {
        val addr = alloc<sockaddr_storage>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_storage>().convert()

        val sockaddrPtr = addr.ptr.reinterpret<sockaddr>()
        if (socket_getpeername(sockfd, sockaddrPtr, addrLen.ptr) == 0) {
            return getPortFromSockaddr(sockaddrPtr, addr.ss_family.toInt())
        }
    }
    return -1
}

/**
 * Extract port from sockaddr based on address family.
 */
@OptIn(ExperimentalForeignApi::class)
private fun getPortFromSockaddr(
    addr: CPointer<sockaddr>,
    family: Int,
): Int =
    when (family) {
        AF_INET -> {
            val addr4 = addr.reinterpret<sockaddr_in>().pointed
            ntohs(addr4.sin_port).toInt()
        }
        AF_INET6 -> {
            val addr6 = addr.reinterpret<sockaddr_in6>().pointed
            ntohs(addr6.sin6_port).toInt()
        }
        else -> -1
    }

/**
 * Check if a socket is IPv6.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun isSocketIPv6(sockfd: Int): Boolean {
    memScoped {
        val addr = alloc<sockaddr_storage>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_storage>().convert()

        val sockaddrPtr = addr.ptr.reinterpret<sockaddr>()
        if (socket_getsockname(sockfd, sockaddrPtr, addrLen.ptr) == 0) {
            return addr.ss_family.toInt() == AF_INET6
        }
    }
    return false
}

/**
 * Set IPV6_V6ONLY socket option.
 * When set to false, allows IPv6 socket to accept IPv4 connections (dual-stack).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun setIPv6Only(
    sockfd: Int,
    v6Only: Boolean,
) {
    memScoped {
        val optval = alloc<IntVar>()
        optval.value = if (v6Only) 1 else 0
        setsockopt(sockfd, IPPROTO_IPV6, IPV6_V6ONLY, optval.ptr, sizeOf<IntVar>().convert())
    }
}

/**
 * Close a socket file descriptor.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun closeSocket(sockfd: Int) {
    if (sockfd >= 0) {
        close(sockfd)
    }
}

/**
 * Get the socket receive buffer size (SO_RCVBUF).
 *
 * This queries the kernel's receive buffer size for the socket, which is typically
 * set based on system defaults (net.core.rmem_default) or per-socket configuration.
 * The kernel usually doubles the requested value to account for bookkeeping overhead,
 * so the returned value may be larger than expected.
 *
 * @param sockfd The socket file descriptor
 * @return The receive buffer size in bytes, or a default value if query fails
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getSocketReceiveBufferSize(sockfd: Int): Int {
    if (sockfd < 0) return DEFAULT_READ_BUFFER_SIZE

    memScoped {
        val optval = alloc<IntVar>()
        val optlen = alloc<socklen_tVar>()
        optlen.value = sizeOf<IntVar>().convert()

        val result = getsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, optval.ptr, optlen.ptr)
        if (result == 0 && optval.value > 0) {
            // Kernel returns the doubled value, use it directly as it represents
            // the actual buffer space available
            return optval.value
        }
    }
    return DEFAULT_READ_BUFFER_SIZE
}

/**
 * Default read buffer size used when SO_RCVBUF query fails.
 */
internal const val DEFAULT_READ_BUFFER_SIZE = 65536

/**
 * Get OpenSSL error string.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getOpenSSLError(): String {
    val errorCode = ERR_get_error()
    if (errorCode == 0UL) return "Unknown SSL error"

    memScoped {
        val buffer = allocArray<ByteVar>(256)
        ERR_error_string_n(errorCode, buffer, 256u)
        return buffer.toKString()
    }
}
