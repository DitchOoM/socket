package com.ditchoom.socket

import com.ditchoom.socket.posix.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import platform.posix.*
import kotlin.coroutines.coroutineContext

/**
 * Server socket implementation using io_uring for async accept operations.
 *
 * io_uring provides true async I/O with zero-copy support on Linux 5.1+.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxServerSocket : ServerSocket {
    private var serverFd: Int = -1
    private var boundPort: Int = -1
    private var listening: Boolean = false

    override suspend fun bind(port: Int, host: String?, backlog: Int): Flow<ClientSocket> {
        memScoped {
            // Create socket
            serverFd = socket(AF_INET, SOCK_STREAM, 0)
            checkSocketResult(serverFd, "socket")

            try {
                // Set socket options
                setReuseAddr(serverFd)
                setNonBlocking(serverFd)

                // Prepare address
                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                addr.sin_family = AF_INET.convert()
                addr.sin_port = htons(port.toUShort())

                if (host != null && host != "0.0.0.0") {
                    val result = inet_pton(AF_INET, host, addr.sin_addr.ptr)
                    if (result != 1) {
                        throw SocketException("Invalid host address: $host")
                    }
                } else {
                    addr.sin_addr.s_addr = htonl(INADDR_ANY.convert())
                }

                // Bind
                val bindResult = platform.posix.bind(serverFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                checkSocketResult(bindResult, "bind")

                // Get actual bound port (useful when port=0)
                val boundAddr = alloc<sockaddr_in>()
                val boundAddrLen = alloc<socklen_tVar>()
                boundAddrLen.value = sizeOf<sockaddr_in>().convert()
                getsockname(serverFd, boundAddr.ptr.reinterpret(), boundAddrLen.ptr)
                boundPort = ntohs(boundAddr.sin_port).toInt()

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

    private fun acceptWithIoUring(): ClientSocket? {
        if (serverFd < 0 || !listening) return null

        memScoped {
            val ring = IoUringManager.getRing()

            val clientAddr = alloc<sockaddr_in>()
            val clientAddrLen = alloc<socklen_tVar>()
            clientAddrLen.value = sizeOf<sockaddr_in>().convert()

            // Get submission queue entry
            val sqe = io_uring_get_sqe(ring)
            if (sqe == null) {
                // Ring is full, try again later
                return null
            }

            // Prepare accept operation
            io_uring_prep_accept(sqe, serverFd, clientAddr.ptr.reinterpret(), clientAddrLen.ptr, 0)

            // Submit
            val submitted = io_uring_submit(ring)
            if (submitted < 0) {
                return null
            }

            // Wait for completion with short timeout (1 second) to allow cancellation checks
            val cqe = allocPointerTo<io_uring_cqe>()
            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = 1
            ts.tv_nsec = 0

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (waitRet < 0) {
                // Timeout or error - return null to allow cancellation check
                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    return null
                }
                return null
            }

            val cqeVal = cqe.value!!
            val clientFd = cqeVal.pointed.res
            io_uring_cqe_seen(ring, cqeVal)

            return when {
                clientFd >= 0 -> {
                    // Successfully accepted a connection
                    val wrapper = LinuxSocketWrapper()
                    wrapper.sockfd = clientFd

                    // Allocate read buffer for the client
                    wrapper.initReadBuffer()

                    wrapper
                }
                else -> {
                    val err = -clientFd
                    when (err) {
                        EAGAIN, EWOULDBLOCK, EINTR -> {
                            // No connection ready, return null
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
        }
    }

    override fun isListening(): Boolean = listening && serverFd >= 0

    override fun port(): Int = boundPort

    private fun closeInternal() {
        listening = false
        if (serverFd >= 0) {
            closeSocket(serverFd)
            serverFd = -1
        }
        boundPort = -1
    }

    override suspend fun close() {
        closeInternal()
    }
}
