package com.ditchoom.socket

import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.coroutineContext

/**
 * Server socket implementation using io_uring for async accept operations.
 *
 * io_uring provides true async I/O with zero-copy support on Linux 5.1+.
 * Uses IoUringManager for proper completion dispatch and io_uring async cancel
 * for immediate cancellation instead of timeout polling.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxServerSocket(
    private val config: TransportConfig = TransportConfig(),
) : ServerSocket {
    private val descriptor = SocketDescriptor()
    private var boundPort: Int = -1

    // Atomic flag for thread-safe listening state checks across coroutines
    private val listening = AtomicInt(0) // 0 = not listening, 1 = listening

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
            val fd =
                if (useIPv6 && !isIPv6Host && (host == null || host == "0.0.0.0")) {
                    // Use IPv6 dual-stack socket to accept both IPv4 and IPv6
                    socket(AF_INET6, SOCK_STREAM, 0)
                } else if (isIPv6Host || host == "::") {
                    socket(AF_INET6, SOCK_STREAM, 0)
                } else {
                    socket(AF_INET, SOCK_STREAM, 0)
                }
            checkSocketResult(fd, "socket")
            descriptor.adopt(fd)

            descriptor.withOpenNow { serverFd -> setUp(serverFd, effectivePort, host, useIPv6, backlog) }
        }

        // Return a flow that accepts connections using io_uring
        return flow {
            while (coroutineContext.isActive && listening.value == 1) {
                val clientSocket = acceptWithIoUring()
                if (clientSocket != null) {
                    emit(clientSocket)
                }
            }
        }
    }

    /** Options, bind and listen on the adopted [serverFd]; a failure closes the socket. */
    private fun setUp(
        serverFd: Int,
        effectivePort: Int,
        host: String?,
        useIPv6: Boolean,
        backlog: Int,
    ) {
        memScoped {
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
                            throw SocketIOException("Invalid IPv6 address: $host")
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
                            throw SocketIOException("Invalid IPv4 address: $host")
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

                listening.value = 1
            } catch (e: Exception) {
                // Clean up on bind/listen failure
                listening.value = 0
                descriptor.close()
                throw e
            }
        }
    }

    private suspend fun acceptWithIoUring(): ClientSocket? {
        if (listening.value == 0) return null
        return try {
            descriptor.withOpen(::acceptOn)
        } catch (_: SocketClosedException) {
            // Closed before this accept named the descriptor.
            listening.value = 0
            null
        }
    }

    private suspend fun acceptOn(serverFd: Int): ClientSocket? {
        // Allocate storage that persists through the async operation
        val clientAddr = nativeHeap.alloc<sockaddr_storage>()
        val clientAddrLen = nativeHeap.alloc<socklen_tVar>()
        clientAddrLen.value = sizeOf<sockaddr_storage>().convert()

        try {
            val addrPtr = clientAddr.ptr.reinterpret<sockaddr>()
            val addrLenPtr = clientAddrLen.ptr

            // A close cancels this accept once the poller has prepared it, and refuses it before then.
            val result =
                descriptor.submit(serverFd, SocketDescriptor.Lane.Read, timeout = null) { sqe, open ->
                    io_uring_prep_accept(sqe, open, addrPtr, addrLenPtr, 0)
                }

            return when {
                result >= 0 -> {
                    // Successfully accepted a connection — apply the server's injected config so
                    // accepted sockets obey the same read/write policy + buffer factory as clients.
                    val wrapper = LinuxSocketWrapper()
                    wrapper.configure(config)
                    wrapper.adopt(result)
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
                            listening.value = 0
                            null
                        }
                        EBADF, EINVAL -> {
                            // Socket closed
                            listening.value = 0
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

    override fun isListening(): Boolean = listening.value == 1 && descriptor.isOpen

    override fun port(): Int = boundPort

    /** Stops listening and cancels a pending accept; the last one out closes the descriptor. */
    override suspend fun close() {
        listening.value = 0
        descriptor.close()
        boundPort = -1
    }
}
