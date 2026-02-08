package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
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
 * Uses IoUringManager for proper completion dispatch.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxClientSocket(
    private val useTls: Boolean,
) : ClientToServerSocket {
    private var sockfd: Int = -1
    private var sslCtx: CPointer<SSL_CTX>? = null
    private var ssl: CPointer<SSL>? = null
    private var currentTlsOptions: TlsOptions = TlsOptions.DEFAULT

    /**
     * Cached socket receive buffer size from SO_RCVBUF.
     * Queried once at connect time and reused for all read operations.
     * Can be overridden by PlatformSocketConfig.readBufferSize if configured.
     */
    private var cachedReadBufferSize: Int = DEFAULT_READ_BUFFER_SIZE

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        tlsOptions: TlsOptions,
    ) {
        val host = hostname ?: "localhost"
        this.currentTlsOptions = tlsOptions

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

                // Cache the socket's receive buffer size for efficient read operations
                cachedReadBufferSize = getSocketReceiveBufferSize(sockfd)

                // Initialize TLS if requested
                if (useTls) {
                    initTls(host, timeout)
                }

                // Track active socket for IoUringManager auto-cleanup
                IoUringManager.onSocketOpened()
            } catch (e: Exception) {
                closeInternal()
                throw e
            } finally {
                freeaddrinfo(addrInfo)
            }
        }
    }

    private suspend fun connectWithIoUring(
        addr: CPointer<sockaddr>,
        addrLen: socklen_t,
        timeout: Duration,
    ) {
        val fd = sockfd
        val result =
            IoUringManager.submitAndWait(timeout) { sqe, _ ->
                io_uring_prep_connect(sqe, fd, addr, addrLen)
            }

        if (result < 0) {
            val errorCode = -result
            when (errorCode) {
                ECONNREFUSED -> throw SocketException("Connection refused")
                ETIMEDOUT, ETIME -> throw SocketException("Connection timed out")
                ENETUNREACH -> throw SocketException("Network unreachable")
                EHOSTUNREACH -> throw SocketException("Host unreachable")
                else -> {
                    val errorMessage = strerror(errorCode)?.toKString() ?: "Unknown error"
                    throw SocketException("connect failed: $errorMessage (errno=$errorCode)")
                }
            }
        }
    }

    private suspend fun initTls(
        hostname: String,
        timeout: Duration,
    ) {
        // Initialize OpenSSL (1.1.0+ auto-initializes, but explicit init is safe)
        OPENSSL_init_ssl(0u, null)

        // Create SSL context
        val method = TLS_client_method() ?: throw SSLSocketException("Failed to get TLS method")
        sslCtx = SSL_CTX_new(method) ?: throw SSLSocketException("Failed to create SSL context")

        // Load system CA certificates from common Linux distribution paths.
        // Supported distributions:
        //   - Debian/Ubuntu: /etc/ssl/certs/ca-certificates.crt
        //   - RHEL/Fedora/CentOS: /etc/pki/tls/certs/ca-bundle.crt
        //   - OpenSUSE: /etc/ssl/ca-bundle.pem
        //   - Alpine/Arch: /etc/ssl/cert.pem
        //   - Fallback: OpenSSL default paths
        val caLoaded =
            SSL_CTX_load_verify_locations(sslCtx, "/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/certs") == 1 ||
                SSL_CTX_load_verify_locations(sslCtx, "/etc/pki/tls/certs/ca-bundle.crt", "/etc/pki/tls/certs") == 1 ||
                SSL_CTX_load_verify_locations(sslCtx, "/etc/ssl/ca-bundle.pem", "/etc/ssl/certs") == 1 ||
                SSL_CTX_load_verify_locations(sslCtx, "/etc/ssl/cert.pem", "/etc/ssl/certs") == 1 ||
                SSL_CTX_set_default_verify_paths(sslCtx) == 1
        if (!caLoaded) {
            throw SSLSocketException(
                "Failed to load CA certificates. Tried: " +
                    "/etc/ssl/certs/ca-certificates.crt, " +
                    "/etc/pki/tls/certs/ca-bundle.crt, " +
                    "/etc/ssl/ca-bundle.pem, " +
                    "/etc/ssl/cert.pem, " +
                    "and default paths",
            )
        }

        // Configure certificate verification based on TlsOptions
        // Enable peer verification by default, disable only if TlsOptions specifies insecure mode
        val verifyCertificates = currentTlsOptions.verifyCertificates && !currentTlsOptions.allowSelfSigned
        ssl_ctx_set_verify_peer(sslCtx, if (verifyCertificates) 1 else 0)

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
    private suspend fun performTlsHandshake(timeout: Duration) {
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
    private suspend fun waitForPoll(
        events: Short,
        timeout: Duration,
    ) {
        val fd = sockfd
        val result =
            IoUringManager.submitAndWait(timeout) { sqe, _ ->
                io_uring_prep_poll_add(sqe, fd, events.toUInt())
            }

        if (result < 0) {
            val errorCode = -result
            if (errorCode == ETIME || errorCode == ETIMEDOUT) {
                throw SSLHandshakeFailedException(Exception("TLS handshake timed out"))
            }
            throw SSLHandshakeFailedException(Exception("Poll failed: ${strerror(errorCode)?.toKString()}"))
        }
    }

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) throw SocketClosedException("Socket is closed")

        // Allocate buffer with native memory for zero-copy io_uring read
        // Use PlatformSocketConfig override if explicitly set, otherwise use cached SO_RCVBUF
        val bufferSize = getEffectiveReadBufferSize()
        val buffer = PlatformBuffer.allocate(bufferSize, AllocationZone.Direct)

        // Get native memory pointer
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = nativeAccess.nativeAddress.toCPointer<ByteVar>()!!
            val bytesRead =
                if (ssl != null) {
                    // TLS read with polling for non-blocking socket
                    sslRead(ptr, bufferSize, timeout)
                } else {
                    // io_uring async read
                    readWithIoUring(ptr, bufferSize, timeout)
                }

            return when {
                bytesRead > 0 -> {
                    // Set position to bytesRead so resetForRead() works correctly
                    // resetForRead() will set limit = position, then position = 0
                    buffer.position(bytesRead)
                    buffer
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    if (ssl != null) {
                        handleSslReadError(bytesRead)
                    } else {
                        handleReadError(-bytesRead)
                    }
                }
            }
        }

        // Fallback for managed memory
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val bytesRead =
                array.usePinned { pinned ->
                    val ptr = pinned.addressOf(0)
                    if (ssl != null) {
                        sslRead(ptr, bufferSize, timeout)
                    } else {
                        readWithIoUring(ptr, bufferSize, timeout)
                    }
                }

            return when {
                bytesRead > 0 -> {
                    // Set position to bytesRead so resetForRead() works correctly
                    // resetForRead() will set limit = position, then position = 0
                    buffer.position(bytesRead)
                    buffer
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    if (ssl != null) {
                        handleSslReadError(bytesRead)
                    } else {
                        handleReadError(-bytesRead)
                    }
                }
            }
        }

        throw SocketException("Buffer has no accessible memory for io_uring read")
    }

    /**
     * Zero-copy read into caller-provided buffer.
     * Writes directly into the buffer using io_uring, avoiding allocations.
     *
     * @param buffer The buffer to write data into. Must have remaining capacity.
     * @param timeout Read timeout duration.
     * @return Number of bytes read, or throws on error/close.
     */
    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        if (sockfd < 0) throw SocketClosedException("Socket is closed")

        val capacity = buffer.remaining()
        if (capacity == 0) return 0

        // Zero-copy path: write directly into native memory
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = (nativeAccess.nativeAddress + buffer.position()).toCPointer<ByteVar>()!!
            val bytesRead =
                if (ssl != null) {
                    sslRead(ptr, capacity, timeout)
                } else {
                    readWithIoUring(ptr, capacity, timeout)
                }

            return when {
                bytesRead > 0 -> {
                    buffer.position(buffer.position() + bytesRead)
                    bytesRead
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    if (ssl != null) {
                        handleSslReadError(bytesRead)
                    } else {
                        handleReadError(-bytesRead)
                    }
                }
            }
        }

        // Zero-copy path: write directly into managed array (pinned)
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val offset = managedAccess.arrayOffset + buffer.position()
            val bytesRead =
                array.usePinned { pinned ->
                    val ptr = pinned.addressOf(offset)
                    if (ssl != null) {
                        sslRead(ptr, capacity, timeout)
                    } else {
                        readWithIoUring(ptr, capacity, timeout)
                    }
                }

            return when {
                bytesRead > 0 -> {
                    buffer.position(buffer.position() + bytesRead)
                    bytesRead
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    if (ssl != null) {
                        handleSslReadError(bytesRead)
                    } else {
                        handleReadError(-bytesRead)
                    }
                }
            }
        }

        throw SocketException("Buffer has no accessible memory for io_uring read")
    }

    /**
     * SSL read with io_uring polling for non-blocking sockets.
     * Handles WANT_READ/WANT_WRITE by polling until data is available.
     */
    private suspend fun sslRead(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val mark = TimeSource.Monotonic.markNow()

        while (true) {
            val result = SSL_read(ssl, ptr, size)
            if (result > 0) return result
            if (result == 0) return 0 // Connection closed

            val error = SSL_get_error(ssl, result)
            when (error) {
                SSL_ERROR_WANT_READ -> {
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        return -1 // Will trigger timeout error
                    }
                    waitForPoll(POLLIN.toShort(), remaining)
                }
                SSL_ERROR_WANT_WRITE -> {
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        return -1
                    }
                    waitForPoll(POLLOUT.toShort(), remaining)
                }
                else -> return result // Other error, let caller handle
            }
        }
    }

    private suspend fun readWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val fd = sockfd
        return IoUringManager.submitAndWait(timeout) { sqe, _ ->
            io_uring_prep_recv(sqe, fd, ptr, size.convert(), 0)
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
            EAGAIN, EWOULDBLOCK, ETIME, ETIMEDOUT -> throw SocketException("Read timed out")
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
        if (sockfd < 0) throw SocketClosedException("Socket is closed")

        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = (nativeAccess.nativeAddress + buffer.position()).toCPointer<ByteVar>()!!
            val bytesSent =
                if (ssl != null) {
                    sslWrite(ptr, remaining, timeout).toLong()
                } else {
                    writeWithIoUring(ptr, remaining, timeout).toLong()
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
                        sslWrite(ptr, remaining, timeout).toLong()
                    } else {
                        writeWithIoUring(ptr, remaining, timeout).toLong()
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
                    sslWrite(ptr, bytes.size, timeout).toLong()
                } else {
                    writeWithIoUring(ptr, bytes.size, timeout).toLong()
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

    private suspend fun writeWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val fd = sockfd
        return IoUringManager.submitAndWait(timeout) { sqe, _ ->
            io_uring_prep_send(sqe, fd, ptr, size.convert(), 0)
        }
    }

    /**
     * SSL write with io_uring polling for non-blocking sockets.
     * Handles WANT_READ/WANT_WRITE by polling until socket is ready.
     */
    private suspend fun sslWrite(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val mark = TimeSource.Monotonic.markNow()

        while (true) {
            val result = SSL_write(ssl, ptr, size)
            if (result > 0) return result

            val error = SSL_get_error(ssl, result)
            when (error) {
                SSL_ERROR_WANT_READ -> {
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        return -1 // Will trigger timeout error
                    }
                    waitForPoll(POLLIN.toShort(), remaining)
                }
                SSL_ERROR_WANT_WRITE -> {
                    val remaining = timeout - mark.elapsedNow()
                    if (remaining.isNegative()) {
                        return -1
                    }
                    waitForPoll(POLLOUT.toShort(), remaining)
                }
                else -> return result // Other error, let caller handle
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
            EAGAIN, EWOULDBLOCK, ETIME, ETIMEDOUT -> throw SocketException("Write timed out")
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

    /**
     * Get the effective read buffer size.
     * Returns PlatformSocketConfig.readBufferSize if explicitly configured (non-default),
     * otherwise returns the cached SO_RCVBUF value queried at connect time.
     */
    private fun getEffectiveReadBufferSize(): Int {
        val configuredSize = PlatformSocketConfig.readBufferSize
        // If user explicitly configured a different buffer size, use that
        if (configuredSize != DEFAULT_READ_BUFFER_SIZE) {
            return configuredSize
        }
        // Otherwise use the cached SO_RCVBUF value
        return cachedReadBufferSize
    }

    private fun closeInternal() {
        val wasOpen = sockfd >= 0
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
        // Notify IoUringManager so it can auto-cleanup when last socket closes.
        // Only decrement if the socket was actually open (avoid double-close underflow).
        if (wasOpen) {
            IoUringManager.onSocketClosed()
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
