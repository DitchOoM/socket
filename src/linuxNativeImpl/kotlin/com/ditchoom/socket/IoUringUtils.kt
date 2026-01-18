package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Shared io_uring instance for socket operations.
 *
 * io_uring provides async I/O with zero-copy support on Linux 5.1+.
 *
 * The io_uring struct is allocated on the native heap and must be explicitly
 * cleaned up via [cleanup] to avoid memory leaks.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IoUringManager {
    private var ringPtr: CPointer<io_uring>? = null
    private val lock = Any()
    private const val QUEUE_DEPTH = 256

    fun getRing(): CPointer<io_uring> {
        synchronized(lock) {
            if (ringPtr == null) {
                initRing()
            }
        }
        return ringPtr!!
    }

    private fun initRing() {
        // Allocate on native heap (not stack) so it persists beyond this function
        val ptr = nativeHeap.alloc<io_uring>().ptr
        val ret = io_uring_queue_init(QUEUE_DEPTH.toUInt(), ptr, 0u)
        if (ret < 0) {
            nativeHeap.free(ptr)
            errno = -ret
            throw SocketException("Failed to initialize io_uring: ${strerror(-ret)?.toKString()}")
        }
        ringPtr = ptr
    }

    fun cleanup() {
        synchronized(lock) {
            ringPtr?.let { ptr ->
                io_uring_queue_exit(ptr)
                nativeHeap.free(ptr)
                ringPtr = null
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
 * Get local port number from socket (supports both IPv4 and IPv6).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getLocalPort(sockfd: Int): Int {
    memScoped {
        val addr = alloc<sockaddr_storage>()
        val addrLen = alloc<socklen_tVar>()
        addrLen.value = sizeOf<sockaddr_storage>().convert()

        if (getsockname(sockfd, addr.ptr.reinterpret(), addrLen.ptr) == 0) {
            return getPortFromSockaddr(addr.ptr.reinterpret(), addr.ss_family.toInt())
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

        if (getpeername(sockfd, addr.ptr.reinterpret(), addrLen.ptr) == 0) {
            return getPortFromSockaddr(addr.ptr.reinterpret(), addr.ss_family.toInt())
        }
    }
    return -1
}

/**
 * Extract port from sockaddr based on address family.
 */
@OptIn(ExperimentalForeignApi::class)
private fun getPortFromSockaddr(addr: CPointer<sockaddr>, family: Int): Int {
    return when (family) {
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

        if (getsockname(sockfd, addr.ptr.reinterpret(), addrLen.ptr) == 0) {
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
internal fun setIPv6Only(sockfd: Int, v6Only: Boolean) {
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
