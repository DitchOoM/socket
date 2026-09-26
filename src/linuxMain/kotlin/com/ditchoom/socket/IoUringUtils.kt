package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*

/**
 * Maps a POSIX errno value to the appropriate [SocketException] subtype.
 *
 * This is the single source of truth for errno → exception mapping on Linux.
 * All call sites (throwSocketException, throwFromResult, handleReadError,
 * handleWriteError, connectWithIoUring) delegate here.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun mapErrnoToException(
    errorCode: Int,
    operation: String,
): SocketException {
    val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
    val message = "$operation failed: $errorMessage (errno=$errorCode)"
    return when (errorCode) {
        ECONNREFUSED -> SocketConnectionException.Refused(null, 0, platformError = message)
        ECONNRESET, ECONNABORTED -> SocketClosedException.ConnectionReset(message)
        ENOTCONN, EPIPE, ESHUTDOWN -> SocketClosedException.BrokenPipe(message)
        // The socket's fd is gone underneath an in-flight op — the connection is closed.
        // EBADF: the read path won the peer-close race, ran closeInternal() (fd → -1), and a
        //   concurrent send/recv then hit the closed fd. ECANCELED: io_uring cancels in-flight
        //   SQEs when their fd is closed (coroutine cancellation is handled separately in
        //   submitAndWait, which rethrows CancellationException before reaching here). Both mean
        //   "socket closed", so they must surface as SocketClosedException — not the generic
        //   SocketIOException — to honour the read/write contract regardless of which errno the
        //   kernel happens to deliver. See LinuxConcurrentCloseTests + LinuxExceptionMappingTests.
        EBADF, ECANCELED -> SocketClosedException.General(message)
        ENETUNREACH -> SocketConnectionException.NetworkUnreachable(message)
        EHOSTUNREACH -> SocketConnectionException.HostUnreachable(message)
        ETIMEDOUT, ETIME -> SocketTimeoutException("$operation timed out")
        EAGAIN, EWOULDBLOCK -> SocketTimeoutException("$operation timed out")
        // The kernel could not allocate memory for the operation — a typed connect failure
        // rather than an opaque I/O error, so callers can branch on ConnectionFailureReason.OutOfMemory.
        ENOMEM -> SocketConnectionException.Other(ConnectionFailureReason.OutOfMemory, message)
        else -> SocketIOException(message)
    }
}

/**
 * Maps POSIX errno values to appropriate socket exceptions.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun throwSocketException(operation: String): Nothing = throw mapErrnoToException(errno, operation)

/**
 * Throw exception from a negative result code.
 */
internal fun throwFromResult(
    result: Int,
    operation: String,
): Nothing = throw mapErrnoToException(-result, operation)

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
 * Apply [IoTuning] TCP options to a socket fd.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applySocketOptions(
    sockfd: Int,
    options: IoTuning,
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
    if (errorCode == 0u) return "Unknown SSL error"

    memScoped {
        val buffer = allocArray<ByteVar>(256)
        ERR_error_string_n(errorCode, buffer, 256u)
        return buffer.toKString()
    }
}
