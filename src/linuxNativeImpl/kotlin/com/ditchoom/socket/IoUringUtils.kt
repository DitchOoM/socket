package com.ditchoom.socket

import com.ditchoom.socket.posix.*
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Shared io_uring instance for socket operations.
 *
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private var ring: io_uring? = null
    private val lock = Any()
    private const val QUEUE_DEPTH = 256

    fun getRing(): CPointer<io_uring> {
        synchronized(lock) {
            if (ring == null) {
                initRing()
            }
        }
        return ring!!.ptr
    }

    private fun initRing() {
        memScoped {
            val ringPtr = alloc<io_uring>()
            val ret = io_uring_queue_init(QUEUE_DEPTH.toUInt(), ringPtr.ptr, 0u)
            if (ret < 0) {
                errno = -ret
                throw SocketException("Failed to initialize io_uring: ${strerror(-ret)?.toKString()}")
            }
            ring = ringPtr.pointed
        }
    }

    fun cleanup() {
        synchronized(lock) {
            ring?.let {
                io_uring_queue_exit(it.ptr)
                ring = null
            }
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
internal fun throwFromResult(result: Int, operation: String): Nothing {
    errno = -result
    throwSocketException(operation)
}

/**
 * Check if an operation succeeded, throw exception if not.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun checkSocketResult(result: Int, operation: String): Int {
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
            "setsockopt(SO_REUSEADDR)"
        )
    }
}

/**
 * Get local port number from socket.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getLocalPort(sockfd: Int): Int {
    memScoped {
        val addr = alloc<sockaddr_in>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_in>().convert()

        if (getsockname(sockfd, addr.ptr.reinterpret(), addrLen.ptr) == 0) {
            return ntohs(addr.sin_port).toInt()
        }
    }
    return -1
}

/**
 * Get remote port number from socket.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getRemotePort(sockfd: Int): Int {
    memScoped {
        val addr = alloc<sockaddr_in>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_in>().convert()

        if (getpeername(sockfd, addr.ptr.reinterpret(), addrLen.ptr) == 0) {
            return ntohs(addr.sin_port).toInt()
        }
    }
    return -1
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
