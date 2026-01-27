package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.concurrent.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Server socket implementation using io_uring for async accept operations.
 *
 * io_uring provides true async I/O with zero-copy support on Linux 5.1+.
 * Uses IoUringManager for proper completion dispatch and io_uring async cancel
 * for immediate cancellation instead of timeout polling.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxServerSocket : ServerSocket {
    private var serverFd: Int = -1
    private var boundPort: Int = -1
    private var listening: Boolean = false

    // Unique user_data for accept operations to enable cancellation
    private val pendingAcceptUserData = AtomicLong(0L)

    override suspend fun bind(
        port: Int,
        host: String?,
        backlog: Int,
    ): Flow<ClientSocket> {
        // Convert -1 (or any negative port) to 0 to let OS assign an ephemeral port
        val effectivePort = if (port < 0) 0 else port

        memScoped {
            // Determine address family from host
            val isIPv6Host = host != null && host.contains(':')
            val useIPv6 = isIPv6Host || host == null || host == "::" || host == "0.0.0.0"

            // Create socket (prefer IPv6 for dual-stack support)
            serverFd =
                if (useIPv6 && !isIPv6Host && (host == null || host == "0.0.0.0")) {
                    // Use IPv6 dual-stack socket to accept both IPv4 and IPv6
                    socket(AF_INET6, SOCK_STREAM, 0)
                } else if (isIPv6Host || host == "::") {
                    socket(AF_INET6, SOCK_STREAM, 0)
                } else {
                    socket(AF_INET, SOCK_STREAM, 0)
                }
            checkSocketResult(serverFd, "socket")

            try {
                // Set socket options
                setReuseAddr(serverFd)
                setNonBlocking(serverFd)

                // For IPv6 sockets, enable dual-stack (accept both IPv4 and IPv6)
                if (useIPv6 && (host == null || host == "0.0.0.0")) {
                    setIPv6Only(serverFd, false)
                }

                // Prepare address and bind
                val addrLen: socklen_t
                val addrPtr: CPointer<sockaddr>

                if (serverFd != -1 && isSocketIPv6(serverFd)) {
                    val addr = alloc<sockaddr_in6>()
                    memset(addr.ptr, 0, sizeOf<sockaddr_in6>().convert())
                    addr.sin6_family = AF_INET6.convert()
                    addr.sin6_port = htons(effectivePort.toUShort())

                    if (host != null && host != "::" && host != "0.0.0.0") {
                        val result = inet_pton(AF_INET6, host, addr.sin6_addr.ptr)
                        if (result != 1) {
                            throw SocketException("Invalid IPv6 address: $host")
                        }
                    }
                    // sin6_addr is already zeroed (in6addr_any equivalent)

                    addrLen = sizeOf<sockaddr_in6>().convert()
                    addrPtr = addr.ptr.reinterpret()
                } else {
                    val addr = alloc<sockaddr_in>()
                    memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                    addr.sin_family = AF_INET.convert()
                    addr.sin_port = htons(effectivePort.toUShort())

                    if (host != null && host != "0.0.0.0") {
                        val result = inet_pton(AF_INET, host, addr.sin_addr.ptr)
                        if (result != 1) {
                            throw SocketException("Invalid IPv4 address: $host")
                        }
                    } else {
                        addr.sin_addr.s_addr = htonl(INADDR_ANY.convert())
                    }

                    addrLen = sizeOf<sockaddr_in>().convert()
                    addrPtr = addr.ptr.reinterpret()
                }

                // Bind
                val bindResult = socket_bind(serverFd, addrPtr, addrLen)
                checkSocketResult(bindResult, "bind")

                // Get actual bound port (useful when port=0)
                boundPort = getLocalPort(serverFd)

                // Listen
                val effectiveBacklog = if (backlog <= 0) SOMAXCONN else backlog
                val listenResult = listen(serverFd, effectiveBacklog)
                checkSocketResult(listenResult, "listen")

                listening = true
            } catch (e: Exception) {
                closeInternal()
                throw e
            }
        }

        // Return a flow that accepts connections using io_uring
        return flow {
            while (coroutineContext.isActive && listening) {
                val clientSocket = acceptWithIoUring()
                if (clientSocket != null) {
                    emit(clientSocket)
                }
            }
        }
    }

    private suspend fun acceptWithIoUring(): ClientSocket? {
        if (serverFd < 0 || !listening) return null

        // Allocate storage that persists through the async operation
        val clientAddr = nativeHeap.alloc<sockaddr_storage>()
        val clientAddrLen = nativeHeap.alloc<socklen_tVar>()
        clientAddrLen.value = sizeOf<sockaddr_storage>().convert()

        try {
            val fd = serverFd
            val addrPtr = clientAddr.ptr.reinterpret<sockaddr>()
            val addrLenPtr = clientAddrLen.ptr

            // Submit accept and wait for completion
            val result =
                IoUringManager.submitAndWait(timeout = null) { sqe, userData ->
                    io_uring_prep_accept(sqe, fd, addrPtr, addrLenPtr, 0)
                    // Store userData for potential cancellation
                    pendingAcceptUserData.value = userData
                }

            pendingAcceptUserData.value = 0L

            return when {
                result >= 0 -> {
                    // Successfully accepted a connection
                    val wrapper = LinuxSocketWrapper()
                    wrapper.sockfd = result
                    wrapper
                }
                else -> {
                    val err = -result
                    when (err) {
                        EAGAIN, EWOULDBLOCK, EINTR -> {
                            // No connection ready, return null
                            null
                        }
                        ECANCELED -> {
                            // Operation was cancelled via close()
                            listening = false
                            null
                        }
                        EBADF, EINVAL -> {
                            // Socket closed
                            listening = false
                            null
                        }
                        else -> {
                            // Other error - could throw or return null
                            null
                        }
                    }
                }
            }
        } finally {
            nativeHeap.free(clientAddr)
            nativeHeap.free(clientAddrLen)
        }
    }

    override fun isListening(): Boolean = listening && serverFd >= 0

    override fun port(): Int = boundPort

    private fun closeInternal() {
        if (!listening && serverFd < 0) return

        // Cancel any pending accept operation using io_uring async cancel
        cancelPendingAccept()

        listening = false
        if (serverFd >= 0) {
            closeSocket(serverFd)
            serverFd = -1
        }
        boundPort = -1
    }

    /**
     * Cancel pending accept operation using io_uring async cancel.
     */
    private fun cancelPendingAccept() {
        val userData = pendingAcceptUserData.value
        if (userData == 0L) return

        // Fire-and-forget cancel - we don't wait for completion
        // The accept operation will return ECANCELED
        memScoped {
            val ring = IoUringManager.getRing()
            val sqe = io_uring_get_sqe(ring) ?: return

            // Cancel the pending accept by its user_data
            io_uring_prep_cancel64(sqe, userData.toULong(), 0)
            io_uring_submit(ring)
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
