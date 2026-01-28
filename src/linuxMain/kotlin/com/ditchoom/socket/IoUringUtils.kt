package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Shared io_uring instance with proper completion dispatch.
 *
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 *
 * This implementation properly routes completion queue entries (CQEs) to their
 * corresponding operations using the user_data field, enabling safe concurrent
 * operations on multiple sockets sharing the same ring.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private val ringRef = AtomicReference<CPointer<io_uring>?>(null)
    private const val QUEUE_DEPTH = 256
    private val POLL_WAIT_TIMEOUT = 100.milliseconds

    // Unique ID generator for user_data
    private val nextUserData = AtomicLong(1L)

    // Pending operations waiting for completion
    // Using a simple concurrent map implementation with mutex
    private val pendingOps = mutableMapOf<Long, CompletableDeferred<Int>>()
    private val pendingOpsMutex = Mutex()

    // Mutex for ring operations (submit + wait must be atomic per operation)
    private val ringMutex = Mutex()

    fun getRing(): CPointer<io_uring> {
        // Try to get existing ring
        ringRef.value?.let { return it }

        // Initialize new ring
        val ptr = nativeHeap.alloc<io_uring>().ptr
        val ret = io_uring_queue_init(QUEUE_DEPTH.toUInt(), ptr, 0u)
        if (ret < 0) {
            nativeHeap.free(ptr)
            val errorMsg = strerror(-ret)?.toKString() ?: "Unknown error"
            throw SocketException("Failed to initialize io_uring: $errorMsg (errno=${-ret})")
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
     * Generate a unique user_data value for an operation.
     */
    fun nextUserData(): Long = nextUserData.incrementAndGet()

    /**
     * Submit an io_uring operation and wait for its completion.
     *
     * This handles the complexity of routing CQEs to the correct waiter when
     * multiple operations are in flight concurrently.
     *
     * @param prepareOp Function to prepare the SQE (called with the SQE and user_data)
     * @param timeout Optional timeout for the operation
     * @return The result code from the CQE (positive for success, negative for error)
     */
    suspend fun submitAndWait(
        timeout: Duration? = null,
        prepareOp: (sqe: CPointer<io_uring_sqe>, userData: Long) -> Unit,
    ): Int {
        val userData = nextUserData()
        val deferred = CompletableDeferred<Int>()

        // Register pending operation BEFORE submitting
        pendingOpsMutex.withLock {
            pendingOps[userData] = deferred
        }

        try {
            // Submit under mutex (quick operation)
            ringMutex.withLock {
                memScoped {
                    val ring = getRing()

                    // Get SQE and prepare operation
                    val sqe =
                        io_uring_get_sqe(ring)
                            ?: throw SocketException("Failed to get SQE - ring is full")

                    prepareOp(sqe, userData)
                    io_uring_sqe_set_data64(sqe, userData.toULong())

                    // Submit
                    val submitted = io_uring_submit(ring)
                    if (submitted < 0) {
                        throwFromResult(submitted, "io_uring_submit")
                    }
                }
            }

            // Poll for completions (releases ringMutex so other operations can submit)
            pollForCompletion(userData, timeout)

            // Wait for our specific completion
            return deferred.await()
        } finally {
            // Clean up pending operation
            pendingOpsMutex.withLock {
                pendingOps.remove(userData)
            }
        }
    }

    /**
     * Poll for CQEs and dispatch them to their owners.
     * Continues until the target operation completes.
     *
     * This method acquires the ring mutex only briefly to poll, allowing
     * other coroutines to submit operations while we wait.
     *
     * IMPORTANT: No suspend functions inside memScoped - Kotlin/Native memScoped
     * uses stack allocation, and suspending can corrupt memory.
     */
    private suspend fun pollForCompletion(
        targetUserData: Long,
        timeout: Duration?,
    ) {
        val deadline = timeout?.let { TimeSource.Monotonic.markNow() + it }

        while (true) {
            // Check if already completed (another poller might have dispatched it)
            val alreadyCompleted =
                pendingOpsMutex.withLock {
                    pendingOps[targetUserData]?.isCompleted == true
                }
            if (alreadyCompleted) return

            // Poll under mutex (brief, no suspend inside memScoped)
            // Collect completions to dispatch AFTER releasing memScoped
            val pollResult =
                ringMutex.withLock {
                    pollCqesNonSuspending(targetUserData, deadline)
                }

            // Now dispatch outside of memScoped (safe to suspend)
            for ((userData, result) in pollResult.completions) {
                pendingOpsMutex.withLock {
                    pendingOps[userData]?.complete(result)
                }
            }

            if (pollResult.foundTarget || pollResult.timedOut) {
                return
            }

            // Yield to allow other coroutines to run
            kotlinx.coroutines.yield()
        }
    }

    /**
     * Result of polling CQEs - contains data to dispatch after memScoped exits.
     */
    private data class PollResult(
        val foundTarget: Boolean,
        val timedOut: Boolean,
        val completions: List<Pair<Long, Int>>,
    )

    /**
     * Poll CQEs without any suspend points - safe to call inside memScoped.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun pollCqesNonSuspending(
        targetUserData: Long,
        deadline: TimeSource.Monotonic.ValueTimeMark?,
    ): PollResult =
        memScoped {
            val ring = getRing()
            val cqe = allocPointerTo<io_uring_cqe>()
            val completions = mutableListOf<Pair<Long, Int>>()

            // Calculate remaining timeout
            val remainingTimeout =
                if (deadline != null) {
                    val remaining = deadline - TimeSource.Monotonic.markNow()
                    if (remaining.isNegative()) {
                        // Timeout expired
                        completions.add(targetUserData to -ETIMEDOUT)
                        return@memScoped PollResult(foundTarget = true, timedOut = true, completions)
                    }
                    remaining
                } else {
                    null
                }

            // Wait with a short timeout to allow other operations to proceed
            val waitTimeout = remainingTimeout?.let { minOf(it, POLL_WAIT_TIMEOUT) } ?: POLL_WAIT_TIMEOUT

            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = waitTimeout.inWholeSeconds
            ts.tv_nsec = ((waitTimeout.inWholeMilliseconds % 1000) * 1_000_000)

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)

            if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                // Short timeout expired, loop and check again
                return@memScoped PollResult(foundTarget = false, timedOut = false, completions)
            }

            if (waitRet < 0) {
                // Other error
                completions.add(targetUserData to waitRet)
                return@memScoped PollResult(foundTarget = true, timedOut = false, completions)
            }

            // Process all available CQEs
            var foundOurTarget = false
            do {
                val cqeVal = cqe.value ?: break
                val cqeUserData = io_uring_cqe_get_data64(cqeVal).toLong()
                val result = cqeVal.pointed.res
                io_uring_cqe_seen(ring, cqeVal)

                // Collect for dispatch after memScoped
                completions.add(cqeUserData to result)

                if (cqeUserData == targetUserData) {
                    foundOurTarget = true
                }
            } while (io_uring_peek_cqe(ring, cqe.ptr) >= 0)

            PollResult(foundTarget = foundOurTarget, timedOut = false, completions)
        }

    /**
     * Submit an operation without waiting (fire-and-forget).
     * Used for cancel operations.
     */
    suspend fun submitNoWait(prepareOp: (sqe: CPointer<io_uring_sqe>) -> Unit) {
        ringMutex.withLock {
            memScoped {
                val ring = getRing()
                val sqe = io_uring_get_sqe(ring) ?: return
                prepareOp(sqe)
                io_uring_submit(ring)
            }
        }
    }

    fun cleanup() {
        val ptr = ringRef.getAndSet(null)
        ptr?.let {
            io_uring_queue_exit(it)
            nativeHeap.free(it)
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
