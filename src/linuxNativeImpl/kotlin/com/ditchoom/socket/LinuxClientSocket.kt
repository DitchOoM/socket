package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Duration

/**
 * Client socket implementation using io_uring for async I/O with optional OpenSSL TLS.
 *
 * io_uring provides true async I/O with zero-copy support on Linux 5.1+.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxClientSocket(private val useTls: Boolean) : ClientToServerSocket {
    private var sockfd: Int = -1
    private var sslCtx: CPointer<SSL_CTX>? = null
    private var ssl: CPointer<SSL>? = null

    // Pinned buffer for zero-copy reads
    private var readBuffer: ByteArray? = null
    private var pinnedReadBuffer: Pinned<ByteArray>? = null

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun open(port: Int, timeout: Duration, hostname: String?) {
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
                    initTls(host)
                }

                // Allocate read buffer for zero-copy operations
                readBuffer = ByteArray(65536)
                pinnedReadBuffer = readBuffer!!.pin()

            } catch (e: Exception) {
                freeaddrinfo(addrInfo)
                closeInternal()
                throw e
            }

            freeaddrinfo(addrInfo)
        }
    }

    private fun connectWithIoUring(addr: CPointer<sockaddr>, addrLen: socklen_t, timeout: Duration) {
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
                errno = -res
                when (-res) {
                    ECONNREFUSED -> throw SocketException("Connection refused")
                    ETIMEDOUT -> throw SocketException("Connection timed out")
                    ENETUNREACH -> throw SocketException("Network unreachable")
                    EHOSTUNREACH -> throw SocketException("Host unreachable")
                    else -> throwSocketException("connect")
                }
            }
        }
    }

    private fun initTls(hostname: String) {
        // Initialize OpenSSL
        SSL_library_init()
        SSL_load_error_strings()
        OpenSSL_add_all_algorithms()

        // Create SSL context
        val method = TLS_client_method() ?: throw SSLSocketException("Failed to get TLS method")
        sslCtx = SSL_CTX_new(method) ?: throw SSLSocketException("Failed to create SSL context")

        // Set default verify paths for system CA certificates
        SSL_CTX_set_default_verify_paths(sslCtx)

        // Create SSL connection
        ssl = SSL_new(sslCtx) ?: throw SSLSocketException("Failed to create SSL object")

        // Set hostname for SNI
        SSL_set_tlsext_host_name(ssl, hostname)

        // Attach socket to SSL
        SSL_set_fd(ssl, sockfd)

        // Perform handshake (this is blocking, but TLS handshake is complex to do async)
        val result = SSL_connect(ssl)
        if (result != 1) {
            val error = SSL_get_error(ssl, result)
            val errorStr = getOpenSSLError()
            throw SSLHandshakeFailedException(Exception("SSL handshake failed: $errorStr (error=$error)"))
        }
    }

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) return EMPTY_BUFFER

        val buffer = readBuffer ?: return EMPTY_BUFFER
        val pinned = pinnedReadBuffer ?: return EMPTY_BUFFER

        val bytesRead = if (ssl != null) {
            // TLS read (blocking on SSL layer)
            SSL_read(ssl, pinned.addressOf(0), buffer.size).toLong()
        } else {
            // io_uring async read
            readWithIoUring(pinned, buffer.size, timeout)
        }

        return when {
            bytesRead > 0 -> {
                val resultBuffer = PlatformBuffer.allocate(bytesRead.toInt(), AllocationZone.Heap)
                resultBuffer.writeBytes(buffer, 0, bytesRead.toInt())
                resultBuffer.resetForRead()
                resultBuffer
            }
            bytesRead == 0L -> {
                closeInternal()
                throw SocketClosedException("Connection closed by peer")
            }
            else -> {
                if (ssl != null) {
                    handleSslReadError(bytesRead.toInt())
                } else {
                    handleReadError((-bytesRead).toInt())
                }
            }
        }
    }

    private fun readWithIoUring(pinned: Pinned<ByteArray>, size: Int, timeout: Duration): Long {
        memScoped {
            val ring = IoUringManager.getRing()

            val sqe = io_uring_get_sqe(ring) ?: throw SocketException("Failed to get SQE")
            io_uring_prep_recv(sqe, sockfd, pinned.addressOf(0), size.convert(), 0)

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
                errno = errorCode
                throwSocketException("recv")
            }
        }
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        if (sockfd < 0) return -1

        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        val bytes = buffer.readByteArray(remaining)

        val bytesSent = if (ssl != null) {
            bytes.usePinned { pinned ->
                SSL_write(ssl, pinned.addressOf(0), bytes.size).toLong()
            }
        } else {
            writeWithIoUring(bytes, timeout)
        }

        return when {
            bytesSent >= 0 -> bytesSent.toInt()
            else -> {
                if (ssl != null) {
                    handleSslWriteError(bytesSent.toInt())
                } else {
                    handleWriteError((-bytesSent).toInt())
                }
            }
        }
    }

    private fun writeWithIoUring(bytes: ByteArray, timeout: Duration): Long {
        return bytes.usePinned { pinned ->
            memScoped {
                val ring = IoUringManager.getRing()

                val sqe = io_uring_get_sqe(ring) ?: throw SocketException("Failed to get SQE")
                io_uring_prep_send(sqe, sockfd, pinned.addressOf(0), bytes.size.convert(), 0)

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

                if (res >= 0) res.toLong() else -(-res).toLong()
            }
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
                errno = errorCode
                throwSocketException("send")
            }
        }
    }

    private fun closeInternal() {
        pinnedReadBuffer?.unpin()
        pinnedReadBuffer = null
        readBuffer = null

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
