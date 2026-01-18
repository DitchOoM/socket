package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Client socket implementation using io_uring for async I/O with optional OpenSSL TLS.
 *
 * Uses NativeBuffer for true zero-copy I/O - no ByteArray intermediaries.
 * io_uring provides async I/O on Linux 5.1+.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxClientSocket(
    private val useTls: Boolean,
) : ClientToServerSocket {
    private var sockfd: Int = -1
    private var sslCtx: CPointer<SSL_CTX>? = null
    private var ssl: CPointer<SSL>? = null

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
    ) {
        val host = hostname ?: "localhost"

        memScoped {
            // Resolve hostname
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM
            hints.ai_protocol = IPPROTO_TCP

            val result = allocPointerTo<addrinfo>()
            val portStr = port.toString()

            val ret = getaddrinfo(host, portStr, hints.ptr, result.ptr)
            if (ret != 0) {
                val errorMsg = gai_strerror(ret)?.toKString() ?: "Unknown DNS error"
                throw SocketUnknownHostException(host, errorMsg)
            }

            val addrInfo = result.value ?: throw SocketUnknownHostException(host, "No address found")

            try {
                // Create socket
                sockfd = socket(addrInfo.pointed.ai_family, SOCK_STREAM, 0)
                checkSocketResult(sockfd, "socket")

                // Set non-blocking for io_uring
                setNonBlocking(sockfd)

                // Connect using io_uring
                connectWithIoUring(addrInfo.pointed.ai_addr!!, addrInfo.pointed.ai_addrlen, timeout)

                // Initialize TLS if requested
                if (useTls) {
                    initTls(host, timeout)
                }
            } catch (e: Exception) {
                freeaddrinfo(addrInfo)
                closeInternal()
                throw e
            }

            freeaddrinfo(addrInfo)
        }
    }

    private fun connectWithIoUring(
        addr: CPointer<sockaddr>,
        addrLen: socklen_t,
        timeout: Duration,
    ) {
        memScoped {
            val ring = IoUringManager.getRing()

            // Get submission queue entry
            val sqe = io_uring_get_sqe(ring) ?: throw SocketException("Failed to get SQE")

            // Prepare connect operation
            io_uring_prep_connect(sqe, sockfd, addr, addrLen)

            // Submit
            val submitted = io_uring_submit(ring)
            if (submitted < 0) {
                throwFromResult(submitted, "io_uring_submit")
            }

            // Wait for completion with timeout
            val cqe = allocPointerTo<io_uring_cqe>()
            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = timeout.inWholeSeconds
            ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (waitRet < 0) {
                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    throw SocketException("Connection timed out")
                }
                throwFromResult(waitRet, "io_uring_wait_cqe_timeout")
            }

            val cqeVal = cqe.value!!
            val res = cqeVal.pointed.res
            io_uring_cqe_seen(ring, cqeVal)

            if (res < 0) {
                val errorCode = -res
                when (errorCode) {
                    ECONNREFUSED -> throw SocketException("Connection refused")
                    ETIMEDOUT -> throw SocketException("Connection timed out")
                    ENETUNREACH -> throw SocketException("Network unreachable")
                    EHOSTUNREACH -> throw SocketException("Host unreachable")
                    else -> {
                        val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
                        throw SocketException("connect failed: $errorMessage (errno=$errorCode)")
                    }
                }
            }
        }
    }

    private fun initTls(
        hostname: String,
        timeout: Duration,
    ) {
        // Initialize OpenSSL (1.1.0+ auto-initializes, but explicit init is safe)
        OPENSSL_init_ssl(0u, null)

        // Create SSL context
        val method = TLS_client_method() ?: throw SSLSocketException("Failed to get TLS method")
        sslCtx = SSL_CTX_new(method) ?: throw SSLSocketException("Failed to create SSL context")

        // Set default verify paths for system CA certificates
        SSL_CTX_set_default_verify_paths(sslCtx)

        // Create SSL connection
        ssl = SSL_new(sslCtx) ?: throw SSLSocketException("Failed to create SSL object")

        // Set hostname for SNI
        ssl_set_hostname(ssl, hostname)

        // Attach socket to SSL
        SSL_set_fd(ssl, sockfd)

        // Perform non-blocking handshake using io_uring for polling
        performTlsHandshake(timeout)
    }

    /**
     * Non-blocking TLS handshake using io_uring poll.
     * Loops on SSL_connect() handling WANT_READ/WANT_WRITE with io_uring polling.
     */
    private fun performTlsHandshake(timeout: Duration) {
        val mark = TimeSource.Monotonic.markNow()

        while (true) {
            val result = SSL_connect(ssl)
            if (result == 1) {
                // Handshake complete
                return
            }

            val error = SSL_get_error(ssl, result)
            when (error) {
                SSL_ERROR_WANT_READ -> {
                    // Wait for socket to be readable
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        throw SSLHandshakeFailedException(Exception("TLS handshake timed out"))
                    }
                    waitForPoll(POLLIN.toShort(), remaining)
                }
                SSL_ERROR_WANT_WRITE -> {
                    // Wait for socket to be writable
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        throw SSLHandshakeFailedException(Exception("TLS handshake timed out"))
                    }
                    waitForPoll(POLLOUT.toShort(), remaining)
                }
                else -> {
                    val errorStr = getOpenSSLError()
                    throw SSLHandshakeFailedException(Exception("SSL handshake failed: $errorStr (error=$error)"))
                }
            }
        }
    }

    /**
     * Wait for socket to be ready for read or write using io_uring poll.
     */
    private fun waitForPoll(
        events: Short,
        timeout: Duration,
    ) {
        memScoped {
            val ring = IoUringManager.getRing()

            val sqe =
                io_uring_get_sqe(ring)
                    ?: throw SSLHandshakeFailedException(Exception("Failed to get SQE for poll"))
            io_uring_prep_poll_add(sqe, sockfd, events.toUInt())

            val submitted = io_uring_submit(ring)
            if (submitted < 0) {
                throw SSLHandshakeFailedException(Exception("Failed to submit poll: ${strerror(-submitted)?.toKString()}"))
            }

            val cqe = allocPointerTo<io_uring_cqe>()
            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = timeout.inWholeSeconds
            ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (waitRet < 0) {
                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    throw SSLHandshakeFailedException(Exception("TLS handshake timed out"))
                }
                throw SSLHandshakeFailedException(Exception("Poll wait failed: ${strerror(-waitRet)?.toKString()}"))
            }

            val cqeVal = cqe.value!!
            val res = cqeVal.pointed.res
            io_uring_cqe_seen(ring, cqeVal)

            if (res < 0) {
                throw SSLHandshakeFailedException(Exception("Poll failed: ${strerror(-res)?.toKString()}"))
            }
        }
    }

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) return EMPTY_BUFFER

        // Allocate native buffer for zero-copy read
        val buffer = PlatformBuffer.allocate(65536, AllocationZone.Direct)
        val nativeAccess =
            buffer.nativeMemoryAccess
                ?: throw SocketException("Failed to get native memory access")
        val ptr = nativeAccess.nativeAddress.toCPointer<ByteVar>()!!

        val bytesRead =
            if (ssl != null) {
                // TLS read using native buffer
                SSL_read(ssl, ptr, buffer.capacity).toLong()
            } else {
                // io_uring async read
                readWithIoUring(ptr, buffer.capacity, timeout)
            }

        return when {
            bytesRead > 0 -> {
                buffer.position(bytesRead.toInt())
                buffer.resetForRead()
                buffer
            }
            bytesRead == 0L -> {
                buffer.close()
                closeInternal()
                throw SocketClosedException("Connection closed by peer")
            }
            else -> {
                buffer.close()
                if (ssl != null) {
                    handleSslReadError(bytesRead.toInt())
                } else {
                    handleReadError((-bytesRead).toInt())
                }
            }
        }
    }

    private fun readWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Long {
        memScoped {
            val ring = IoUringManager.getRing()

            val sqe = io_uring_get_sqe(ring) ?: throw SocketException("Failed to get SQE")
            io_uring_prep_recv(sqe, sockfd, ptr, size.convert(), 0)

            val submitted = io_uring_submit(ring)
            if (submitted < 0) {
                throwFromResult(submitted, "io_uring_submit")
            }

            val cqe = allocPointerTo<io_uring_cqe>()
            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = timeout.inWholeSeconds
            ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (waitRet < 0) {
                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    throw SocketException("Read timed out")
                }
                throwFromResult(waitRet, "io_uring_wait_cqe_timeout")
            }

            val cqeVal = cqe.value!!
            val res = cqeVal.pointed.res
            io_uring_cqe_seen(ring, cqeVal)

            return if (res >= 0) res.toLong() else -(-res).toLong()
        }
    }

    private fun handleSslReadError(result: Int): Nothing {
        val error = SSL_get_error(ssl, result)
        when (error) {
            SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {
                throw SocketException("Read timed out")
            }
            SSL_ERROR_ZERO_RETURN -> {
                closeInternal()
                throw SocketClosedException("Connection closed by peer")
            }
            else -> {
                throw SocketException("SSL read error: ${getOpenSSLError()}")
            }
        }
    }

    private fun handleReadError(errorCode: Int): Nothing {
        when (errorCode) {
            EAGAIN, EWOULDBLOCK -> throw SocketException("Read timed out")
            ECONNRESET, ENOTCONN, EPIPE -> {
                closeInternal()
                throw SocketClosedException("Connection closed")
            }
            else -> {
                val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
                throw SocketException("recv failed: $errorMessage (errno=$errorCode)")
            }
        }
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (sockfd < 0) return -1

        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = (nativeAccess.nativeAddress + buffer.position()).toCPointer<ByteVar>()!!
            val bytesSent =
                if (ssl != null) {
                    SSL_write(ssl, ptr, remaining).toLong()
                } else {
                    writeWithIoUring(ptr, remaining, timeout)
                }
            return handleWriteResult(buffer, bytesSent)
        }

        // Zero-copy path: check if buffer has managed array access
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val offset = managedAccess.arrayOffset + buffer.position()
            return array.usePinned { pinned ->
                val ptr = pinned.addressOf(offset)
                val bytesSent =
                    if (ssl != null) {
                        SSL_write(ssl, ptr, remaining).toLong()
                    } else {
                        writeWithIoUring(ptr, remaining, timeout)
                    }
                handleWriteResult(buffer, bytesSent)
            }
        }

        // Fallback: copy to temporary array
        val bytes = buffer.readByteArray(remaining)
        return bytes.usePinned { pinned ->
            val ptr = pinned.addressOf(0)
            val bytesSent =
                if (ssl != null) {
                    SSL_write(ssl, ptr, bytes.size).toLong()
                } else {
                    writeWithIoUring(ptr, bytes.size, timeout)
                }
            when {
                bytesSent >= 0 -> bytesSent.toInt()
                else ->
                    if (ssl != null) {
                        handleSslWriteError(bytesSent.toInt())
                    } else {
                        handleWriteError((-bytesSent).toInt())
                    }
            }
        }
    }

    private fun handleWriteResult(
        buffer: ReadBuffer,
        bytesSent: Long,
    ): Int =
        when {
            bytesSent >= 0 -> {
                buffer.position(buffer.position() + bytesSent.toInt())
                bytesSent.toInt()
            }
            else ->
                if (ssl != null) {
                    handleSslWriteError(bytesSent.toInt())
                } else {
                    handleWriteError((-bytesSent).toInt())
                }
        }

    private fun writeWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Long {
        memScoped {
            val ring = IoUringManager.getRing()

            val sqe = io_uring_get_sqe(ring) ?: throw SocketException("Failed to get SQE")
            io_uring_prep_send(sqe, sockfd, ptr, size.convert(), 0)

            val submitted = io_uring_submit(ring)
            if (submitted < 0) {
                throwFromResult(submitted, "io_uring_submit")
            }

            val cqe = allocPointerTo<io_uring_cqe>()
            val ts = alloc<__kernel_timespec>()
            ts.tv_sec = timeout.inWholeSeconds
            ts.tv_nsec = ((timeout.inWholeMilliseconds % 1000) * 1_000_000)

            val waitRet = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (waitRet < 0) {
                if (waitRet == -ETIME || waitRet == -ETIMEDOUT) {
                    throw SocketException("Write timed out")
                }
                throwFromResult(waitRet, "io_uring_wait_cqe_timeout")
            }

            val cqeVal = cqe.value!!
            val res = cqeVal.pointed.res
            io_uring_cqe_seen(ring, cqeVal)

            return if (res >= 0) res.toLong() else -(-res).toLong()
        }
    }

    private fun handleSslWriteError(result: Int): Nothing {
        val error = SSL_get_error(ssl, result)
        when (error) {
            SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {
                throw SocketException("Write timed out")
            }
            else -> {
                throw SocketException("SSL write error: ${getOpenSSLError()}")
            }
        }
    }

    private fun handleWriteError(errorCode: Int): Nothing {
        when (errorCode) {
            EAGAIN, EWOULDBLOCK -> throw SocketException("Write timed out")
            ECONNRESET, ENOTCONN, EPIPE -> {
                closeInternal()
                throw SocketClosedException("Connection closed")
            }
            else -> {
                val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
                throw SocketException("send failed: $errorMessage (errno=$errorCode)")
            }
        }
    }

    private fun closeInternal() {
        ssl?.let {
            SSL_shutdown(it)
            SSL_free(it)
            ssl = null
        }
        sslCtx?.let {
            SSL_CTX_free(it)
            sslCtx = null
        }
        if (sockfd >= 0) {
            closeSocket(sockfd)
            sockfd = -1
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
