package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
 * Shared io_uring instance with dedicated poller thread for completion dispatch.
 *
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 *
 * Architecture:
 * - Single poller thread blocks on io_uring_wait_cqe_timeout (efficient kernel wait)
 * - Coroutines submit operations and suspend via CompletableDeferred.await()
 * - Poller dispatches completions to waiting coroutines by user_data
 * - Timeouts are handled via kernel timeout, not coroutine timeout (no orphaned ops)
 *
 * This design uses exactly ONE thread for all socket I/O in the application,
 * regardless of how many concurrent operations are in flight.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private val ringRef = AtomicReference<CPointer<io_uring>?>(null)

    // Configuration is read from PlatformSocketConfig
    // These getters allow runtime configuration changes to take effect
    private val queueDepth: Int
        get() = PlatformSocketConfig.ioQueueDepth

    private val maxSqeRetries: Int
        get() = PlatformSocketConfig.ioQueueRetries

    private val sqeRetryDelay: Duration
        get() = PlatformSocketConfig.ioRetryDelay

    // Default max wait time when no operations have deadlines
    private val DEFAULT_POLL_TIMEOUT = 1.seconds

    // Unique ID generator for user_data
    private val nextUserDataCounter = AtomicLong(1L)

    // Pending operations waiting for completion (with optional deadlines)
    private val pendingOps = mutableMapOf<Long, PendingOperation>()
    private val pendingOpsMutex = Mutex()

    // Mutex for submission queue operations only
    private val submitMutex = Mutex()

    // Dedicated poller thread - lazily initialized and reinitializable after cleanup
    // Using AtomicReference to allow thread-safe reinitialization
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
            // Another thread created it first, cancel ours
            newScope.cancel()
        }
        return pollerScopeRef.value!!
    }

    fun getRing(): CPointer<io_uring> {
        // Try to get existing ring
        ringRef.value?.let { return it }

        // Initialize new ring
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

        // Try to set atomically - if another thread beat us, use theirs
        if (!ringRef.compareAndSet(null, ptr)) {
            // Another thread initialized first, clean up ours
            io_uring_queue_exit(ptr)
            nativeHeap.free(ptr)
        }

        return ringRef.value!!
    }

    /**
     * Ensure the poller thread is running.
     * Called lazily on first operation.
     */
    private fun ensurePollerStarted() {
        if (pollerStarted.compareAndSet(0, 1)) {
            val scope = getOrCreatePollerScope()
            val job =
                scope.launch {
                    pollerLoop()
                }
            pollerJobRef.value = job
        }
    }

    /**
     * Calculate time until the earliest deadline, or default timeout if none.
     * Must be called while holding pendingOpsMutex.
     */
    private fun calculateNextTimeout(): Duration {
        var earliest: Duration? = null

        for ((_, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            // elapsedNow() returns negative if deadline is in the future
            // So -elapsedNow() gives us time remaining until deadline
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
     * Must be called while holding pendingOpsMutex.
     * Returns list of expired userData to remove.
     */
    private fun completeExpiredOperations(): List<Long> {
        val expired = mutableListOf<Long>()

        for ((userData, op) in pendingOps) {
            val deadline = op.deadline ?: continue
            if (deadline.hasPassedNow() && !op.deferred.isCompleted) {
                op.deferred.complete(-ETIMEDOUT)
                expired.add(userData)
            }
        }

        return expired
    }

    /**
     * Main poller loop - runs on dedicated thread.
     * Blocks on io_uring_wait_cqe_timeout and dispatches completions.
     * Timeout is calculated from earliest pending deadline.
     */
    private suspend fun pollerLoop() {
        val ring = getRing()

        // Allocate once and reuse to avoid fragmentation in tight loop
        val cqePtr = nativeHeap.alloc<CPointerVar<io_uring_cqe>>()
        val ts = nativeHeap.alloc<__kernel_timespec>()

        try {
            while (currentCoroutineContext().isActive) {
                // Calculate timeout based on earliest deadline
                val timeout =
                    pendingOpsMutex.withLock {
                        calculateNextTimeout()
                    }

                ts.tv_sec = timeout.inWholeSeconds
                ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

                val waitRet = io_uring_wait_cqe_timeout(ring, cqePtr.ptr, ts.ptr)

                // Check for expired operations regardless of wait result
                pendingOpsMutex.withLock {
                    val expired = completeExpiredOperations()
                    expired.forEach { pendingOps.remove(it) }
                }

                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    // Kernel timeout - we've already handled expired ops above
                    continue
                }

                if (waitRet < 0) {
                    // Other error - continue polling
                    continue
                }

                // Process all available CQEs
                do {
                    val cqeVal = cqePtr.value
                    if (cqeVal == null) break

                    val userData = io_uring_cqe_get_data64(cqeVal).toLong()
                    val result = cqeVal.pointed.res
                    io_uring_cqe_seen(ring, cqeVal)

                    // Skip wakeup NOPs (user_data = 0)
                    if (userData == 0L) continue

                    // Dispatch to waiting coroutine
                    pendingOpsMutex.withLock {
                        val op = pendingOps[userData]
                        if (op != null && !op.deferred.isCompleted) {
                            op.deferred.complete(result)
                        }
                    }
                } while (io_uring_peek_cqe(ring, cqePtr.ptr) >= 0)
            }
        } finally {
            // Free allocations when loop exits
            nativeHeap.free(ts)
            nativeHeap.free(cqePtr)
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
     * Timeouts are handled via kernel timeout - no orphaned operations.
     *
     * If the submission queue is full, this will retry with exponential backoff
     * up to maxSqeRetries times before failing.
     *
     * @param prepareOp Function to prepare the SQE (called with the SQE and user_data)
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

        // Register pending operation BEFORE submitting
        pendingOpsMutex.withLock {
            pendingOps[userData] = PendingOperation(deferred, deadline)
        }

        try {
            // Submit with retry logic for full ring
            var retries = 0
            while (true) {
                val submitted =
                    submitMutex.withLock {
                        memScoped {
                            val ring = getRing()

                            // Get SQE - may return null if ring is full
                            val sqe = io_uring_get_sqe(ring)
                            if (sqe == null) {
                                // Ring is full, signal retry needed
                                return@memScoped -1
                            }

                            prepareOp(sqe, userData)
                            io_uring_sqe_set_data64(sqe, userData.toULong())

                            // Submit to kernel
                            io_uring_submit(ring)
                        }
                    }

                when {
                    submitted >= 0 -> break // Success
                    submitted == -1 -> {
                        // Ring was full, retry with backoff
                        retries++
                        if (retries > maxSqeRetries) {
                            throw SocketException(
                                "io_uring submission queue full after $maxSqeRetries retries. " +
                                    "Too many concurrent operations.",
                            )
                        }
                        // Exponential backoff: 1ms, 2ms, 4ms, ...
                        kotlinx.coroutines.delay(sqeRetryDelay * retries)
                    }
                    else -> throwFromResult(submitted, "io_uring_submit")
                }
            }

            // Suspend until poller dispatches our completion (or timeout)
            // Use suspendCancellableCoroutine so we can cancel the io_uring operation
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    // Cancel the io_uring operation when the coroutine is cancelled
                    // This is fire-and-forget - we don't wait for the cancel to complete
                    submitNoWaitUnsafe { sqe ->
                        io_uring_prep_cancel64(sqe, userData.toULong(), 0)
                    }
                    // Complete the deferred so the poller doesn't try to complete it later
                    deferred.complete(-ECANCELED)
                }
                // Resume when deferred completes
                deferred.invokeOnCompletion { throwable ->
                    if (throwable != null) {
                        cont.resumeWithException(throwable)
                    } else {
                        cont.resume(deferred.getCompleted())
                    }
                }
            }
        } finally {
            // Clean up pending operation - use NonCancellable to ensure this runs
            withContext(NonCancellable) {
                pendingOpsMutex.withLock {
                    pendingOps.remove(userData)
                }
            }
        }
    }

    /**
     * Allocate a user_data value and register it for an upcoming operation.
     * This allows the caller to know the userData before submission, enabling
     * proper cancellation handling.
     *
     * @return Pair of (userData, deferred) for tracking the operation
     */
    suspend fun registerOperation(timeout: Duration? = null): Pair<Long, CompletableDeferred<Int>> {
        ensurePollerStarted()

        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }

        pendingOpsMutex.withLock {
            pendingOps[userData] = PendingOperation(deferred, deadline)
        }

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
        prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit,
    ): Int {
        try {
            var retries = 0
            while (true) {
                val submitted =
                    submitMutex.withLock {
                        memScoped {
                            val ring = getRing()
                            val sqe = io_uring_get_sqe(ring)
                            if (sqe == null) {
                                return@memScoped -1
                            }

                            prepareOp(sqe)
                            io_uring_sqe_set_data64(sqe, userData.toULong())
                            io_uring_submit(ring)
                        }
                    }

                when {
                    submitted >= 0 -> break
                    submitted == -1 -> {
                        retries++
                        if (retries > maxSqeRetries) {
                            throw SocketException(
                                "io_uring submission queue full after $maxSqeRetries retries.",
                            )
                        }
                        kotlinx.coroutines.delay(sqeRetryDelay * retries)
                    }
                    else -> throwFromResult(submitted, "io_uring_submit")
                }
            }

            // Use suspendCancellableCoroutine so we can cancel the io_uring operation
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation {
                    // Cancel the io_uring operation when the coroutine is cancelled
                    submitNoWaitUnsafe { sqe ->
                        io_uring_prep_cancel64(sqe, userData.toULong(), 0)
                    }
                    deferred.complete(-ECANCELED)
                }
                deferred.invokeOnCompletion { throwable ->
                    if (throwable != null) {
                        cont.resumeWithException(throwable)
                    } else {
                        cont.resume(deferred.getCompleted())
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                pendingOpsMutex.withLock {
                    pendingOps.remove(userData)
                }
            }
        }
    }

    /**
     * Submit an operation without waiting (fire-and-forget).
     * Used for cancel operations. This is a suspend function.
     */
    suspend fun submitNoWait(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        submitMutex.withLock {
            memScoped {
                val ring = getRing()
                val sqe = io_uring_get_sqe(ring) ?: return
                prepareOp(sqe)
                io_uring_submit(ring)
            }
        }
    }

    /**
     * Submit an operation without waiting - non-blocking version.
     * Used when called from non-suspend context (like cleanup).
     * Does NOT acquire the mutex - use only when safe (e.g., during shutdown).
     */
    fun submitNoWaitUnsafe(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        val ring = ringRef.value ?: return
        memScoped {
            val sqe = io_uring_get_sqe(ring) ?: return
            prepareOp(sqe)
            io_uring_submit(ring)
        }
    }

    /**
     * Cleanup resources. Call when shutting down.
     *
     * Submits a NOP operation to wake the poller thread immediately,
     * avoiding up to DEFAULT_POLL_TIMEOUT delay on shutdown.
     *
     * After cleanup, the IoUringManager can be reused - new operations
     * will reinitialize the ring and poller thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cleanup() {
        // Mark poller as stopping
        val wasStarted = pollerStarted.getAndSet(0) == 1

        if (wasStarted) {
            // IMPORTANT: Cancel the job FIRST so isActive becomes false
            // Then wake the poller so it exits immediately
            val job = pollerJobRef.getAndSet(null)
            job?.cancel()

            // Wake up the poller immediately by submitting a NOP
            // This causes io_uring_wait_cqe_timeout to return immediately
            // The poller will check isActive (now false) and exit
            wakePollerWithNop()

            // Wait briefly for the poller to actually exit
            // This ensures the poller thread has stopped before we close the dispatcher
            // Use Dispatchers.Default to avoid deadlock if cleanup() is called from a coroutine
            runBlocking(kotlinx.coroutines.Dispatchers.Default) {
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

        // Cleanup ring
        val ptr = ringRef.getAndSet(null)
        ptr?.let {
            io_uring_queue_exit(it)
            nativeHeap.free(it)
        }

        // Complete any pending operations with error
        // Use Dispatchers.Default to avoid deadlock if cleanup() is called from a coroutine
        runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            pendingOpsMutex.withLock {
                pendingOps.values.forEach { it.deferred.complete(-ECANCELED) }
                pendingOps.clear()
            }
        }
    }

    /**
     * Submit a NOP operation to wake the poller thread.
     * Used during shutdown to avoid waiting for the poll timeout.
     *
     * This is safe to call even if the SQ is full - in that case,
     * the poller will wake on the next completion anyway.
     */
    private fun wakePollerWithNop() {
        val ring = ringRef.value ?: return

        memScoped {
            val sqe = io_uring_get_sqe(ring)
            if (sqe != null) {
                // Use user_data = 0 to indicate this is a wakeup NOP
                // The poller will ignore completions with user_data = 0
                io_uring_prep_nop(sqe)
                io_uring_sqe_set_data64(sqe, 0UL)
                io_uring_submit(ring)
            }
            // If sqe is null, the SQ is full which means there are pending
            // operations that will complete and wake the poller anyway
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
