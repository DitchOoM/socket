package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlin.concurrent.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
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
    /** The injected transport configuration, supplied at `allocate(config)` time. */
    private val config: TransportConfig = TransportConfig(),
) : ClientToServerSocket {
    private var sockfd: Int = -1
    private var sslCtx: CPointer<SSL_CTX>? = null
    private var ssl: CPointer<SSL>? = null
    private var currentTlsConfig: TlsConfig = TlsConfig.DEFAULT

    /**
     * Cached socket receive buffer size from SO_RCVBUF.
     * Queried once at connect time and reused for all read operations.
     * Can be overridden by [IoTuning.readBufferSize] if configured.
     */
    private var cachedReadBufferSize: Int = DEFAULT_READ_BUFFER_SIZE

    /**
     * user_data of the recv currently parked in io_uring, or 0 when no read is in flight.
     * Set on the event loop thread once the recv SQE is prepared (see [readWithIoUring])
     * so a concurrent [close] can cancel it; cleared when the read returns. Lets close()
     * promptly complete a parked recv with -ECANCELED instead of waiting out its timeout
     * (issue #83).
     */
    private val pendingReadUserData = AtomicLong(0L)

    override val isOpen: Boolean get() = sockfd >= 0

    override val readPolicy: ReadPolicy get() = config.readPolicy

    override val writePolicy: WritePolicy get() = config.writePolicy

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun open(
        port: Int,
        hostname: String?,
    ) {
        val timeout = config.connectTimeout
        val host = hostname ?: "localhost"
        val tlsConfig = config.tls
        this.currentTlsConfig = tlsConfig ?: TlsConfig.DEFAULT

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
                // Walk the addrinfo linked list and try each candidate in order. getaddrinfo
                // returns multiple records for dual-stack hosts (AAAA + A, multiple IPv4s, etc.);
                // attempting only the first record — as the prior code did — fails the entire
                // connect when the head address is unreachable even though a later address would
                // succeed. broker.hivemq.com is the canonical reproducer: its 8 AAAA records all
                // ECONNREFUSED while the A record accepts. RFC 8305 / Happy Eyeballs racing is
                // not implemented here; sequential fallback is enough to clear the common
                // dual-stack-misconfiguration case.
                var lastError: Exception? = null
                var entryPtr: CPointer<addrinfo>? = addrInfo
                while (entryPtr != null) {
                    val entry = entryPtr.pointed
                    val candidateFd = socket(entry.ai_family, SOCK_STREAM, 0)
                    if (candidateFd < 0) {
                        lastError = mapErrnoToException(errno, "socket")
                        entryPtr = entry.ai_next
                        continue
                    }
                    try {
                        setNonBlocking(candidateFd)
                        applySocketOptions(candidateFd, config.io)
                        sockfd = candidateFd
                        connectWithIoUring(entry.ai_addr!!, entry.ai_addrlen, timeout)
                        // Connect succeeded — exit the fallback loop.
                        lastError = null
                        break
                    } catch (e: CancellationException) {
                        // A cancelled connect must propagate immediately — not be recorded as lastError
                        // and retried against the next address (which would mask the cancellation, or
                        // surface a bogus "all addresses unreachable" if the last socket() then fails).
                        closeSocket(candidateFd)
                        sockfd = -1
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        closeSocket(candidateFd)
                        sockfd = -1
                        entryPtr = entry.ai_next
                    }
                }

                if (sockfd < 0) {
                    throw lastError
                        ?: SocketConnectionException.Refused(host, port, platformError = "all addresses unreachable")
                }

                // Cache the socket's receive buffer size for efficient read operations
                cachedReadBufferSize = getSocketReceiveBufferSize(sockfd)

                // Initialize TLS if requested
                if (tlsConfig != null) {
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
            throw mapErrnoToException(-result, "connect")
        }
    }

    private suspend fun initTls(
        hostname: String,
        timeout: Duration,
    ) {
        // Initialize OpenSSL (1.1.0+ auto-initializes, but explicit init is safe)
        OPENSSL_init_ssl(0u, null)

        // Create SSL context
        val method = TLS_client_method() ?: throw SSLProtocolException("Failed to get TLS method")
        sslCtx = SSL_CTX_new(method) ?: throw SSLProtocolException("Failed to create SSL context")

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
            throw SSLProtocolException(
                "Failed to load CA certificates. Tried: " +
                    "/etc/ssl/certs/ca-certificates.crt, " +
                    "/etc/pki/tls/certs/ca-bundle.crt, " +
                    "/etc/ssl/ca-bundle.pem, " +
                    "/etc/ssl/cert.pem, " +
                    "and default paths",
            )
        }

        // Configure certificate verification based on TlsConfig
        // Enable peer verification by default, disable only if TlsConfig specifies insecure mode
        val verifyCertificates = currentTlsConfig.verifyCertificates && !currentTlsConfig.allowSelfSigned
        ssl_ctx_set_verify_peer(sslCtx, if (verifyCertificates) 1 else 0)

        // Create SSL connection
        ssl = SSL_new(sslCtx) ?: throw SSLProtocolException("Failed to create SSL object")

        // Set hostname for SNI
        ssl_set_hostname(ssl, hostname)

        // Enforce SAN/hostname matching when verifyHostname is on. Without this,
        // SSL_VERIFY_PEER only checks the chain — a trusted cert with a SAN
        // that doesn't cover the connected host still completes the handshake,
        // which JVM, Apple, and JS all reject by default. The C wrapper picks
        // between X509_VERIFY_PARAM_set1_host (DNS) and …set1_ip_asc (IP) by
        // probing with inet_pton; see LinuxSockets.def.
        if (currentTlsConfig.verifyHostname) {
            if (ssl_set_verify_host(ssl, hostname) != 1) {
                throw SSLProtocolException(
                    "Failed to configure hostname verification for '$hostname'",
                )
            }
        }

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

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (sockfd < 0) throw SocketClosedException.General("Socket is closed")

        // Allocate buffer with native memory for zero-copy io_uring read
        // Use IoTuning override if explicitly set, otherwise use cached SO_RCVBUF
        val bufferSize = getEffectiveReadBufferSize()
        val buffer = config.bufferFactory.allocate(bufferSize)

        try {
            // Get native memory pointer
            val nativeAccess = buffer.nativeMemoryAccess
            if (nativeAccess != null) {
                val ptr = nativeAccess.nativeAddress.toCPointer<ByteVar>()!!
                val bytesRead =
                    if (ssl != null) {
                        // TLS read with polling for non-blocking socket
                        sslRead(ptr, bufferSize, deadline)
                    } else {
                        // io_uring async read
                        readWithIoUring(ptr, bufferSize, deadline)
                    }

                return when {
                    bytesRead > 0 -> {
                        buffer.position(bytesRead)
                        buffer.resetForRead()
                        buffer
                    }
                    bytesRead == 0 -> {
                        closeInternal()
                        throw SocketClosedException.EndOfStream()
                    }
                    else -> {
                        if (ssl != null) {
                            handleSslReadError(bytesRead, deadline)
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
                            sslRead(ptr, bufferSize, deadline)
                        } else {
                            readWithIoUring(ptr, bufferSize, deadline)
                        }
                    }

                return when {
                    bytesRead > 0 -> {
                        buffer.position(bytesRead)
                        buffer.resetForRead()
                        buffer
                    }
                    bytesRead == 0 -> {
                        closeInternal()
                        throw SocketClosedException.EndOfStream()
                    }
                    else -> {
                        if (ssl != null) {
                            handleSslReadError(bytesRead, deadline)
                        } else {
                            handleReadError(-bytesRead)
                        }
                    }
                }
            }

            throw SocketIOException("Buffer has no accessible memory for io_uring read")
        } catch (e: CancellationException) {
            // Safe to free: submitAndWait ensures the kernel is done with the buffer
            buffer.freeNativeMemory()
            throw e
        }
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
        // Register the userData up front so close() can cancel this recv by id. Mark
        // it parked inside prepareOp — which runs on the event loop thread AFTER the
        // recv is registered in pendingOps — so a concurrent close() can never enqueue
        // its cancel ahead of this recv in the FIFO submission channel. (issue #83)
        val (userData, deferred) = IoUringManager.registerOperation()
        return try {
            IoUringManager.submitRegistered(userData, deferred, timeout) { sqe ->
                io_uring_prep_recv(sqe, fd, ptr, size.convert(), 0)
                pendingReadUserData.value = userData
            }
        } finally {
            pendingReadUserData.compareAndSet(userData, 0L)
        }
    }

    private fun handleSslReadError(
        result: Int,
        deadline: Duration,
    ): Nothing {
        val error = SSL_get_error(ssl, result)
        when (error) {
            SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {
                throw SocketTimeoutException(TimeoutContext.Read(deadline))
            }
            SSL_ERROR_ZERO_RETURN -> {
                closeInternal()
                throw SocketClosedException.EndOfStream()
            }
            else -> {
                throw SSLProtocolException("SSL read error: ${getOpenSSLError()}")
            }
        }
    }

    private fun handleReadError(errorCode: Int): Nothing {
        val ex = mapErrnoToException(errorCode, "recv")
        if (ex is SocketClosedException) closeInternal()
        throw ex
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (sockfd < 0) throw SocketClosedException.General("Socket is closed")

        val remaining = buffer.remaining()
        if (remaining == 0) return BytesWritten(0)

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = (nativeAccess.nativeAddress + buffer.position()).toCPointer<ByteVar>()!!
            val bytesSent =
                if (ssl != null) {
                    sslWrite(ptr, remaining, deadline).toLong()
                } else {
                    writeWithIoUring(ptr, remaining, deadline).toLong()
                }
            return BytesWritten(handleWriteResult(buffer, bytesSent, deadline))
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
                        sslWrite(ptr, remaining, deadline).toLong()
                    } else {
                        writeWithIoUring(ptr, remaining, deadline).toLong()
                    }
                BytesWritten(handleWriteResult(buffer, bytesSent, deadline))
            }
        }

        // Fallback: neither native nor managed access (e.g. a sliced/composite ReadBuffer).
        // Stage into a deterministic native scratch buffer so sslWrite/io_uring
        // get a pointer without going through a Kotlin-heap ByteArray. Scratch
        // is freed unconditionally; source position is advanced by the actual
        // bytes-sent count via handleWriteResult, not by the amount we staged.
        val scratch = BufferFactory.deterministic().allocate(remaining)
        try {
            val sourcePos = buffer.position()
            scratch.write(buffer) // consumes `remaining` bytes from source
            buffer.position(sourcePos) // handleWriteResult will re-advance
            scratch.resetForRead()
            val ptr =
                scratch.nativeMemoryAccess!!
                    .nativeAddress
                    .toCPointer<ByteVar>()!!
            val bytesSent =
                if (ssl != null) {
                    sslWrite(ptr, remaining, deadline).toLong()
                } else {
                    writeWithIoUring(ptr, remaining, deadline).toLong()
                }
            return BytesWritten(handleWriteResult(buffer, bytesSent, deadline))
        } finally {
            scratch.freeNativeMemory()
        }
    }

    private fun handleWriteResult(
        buffer: ReadBuffer,
        bytesSent: Long,
        deadline: Duration,
    ): Int =
        when {
            bytesSent >= 0 -> {
                buffer.position(buffer.position() + bytesSent.toInt())
                bytesSent.toInt()
            }
            ssl != null -> handleSslWriteError(bytesSent.toInt(), deadline)
            else -> {
                val errorCode = (-bytesSent).toInt()
                if (errorCode == ETIME || errorCode == ETIMEDOUT) {
                    // An opt-in Bounded(d) write blew its io_uring linked-timeout. DESTRUCTIVE
                    // (RFC_WRITE_TIMEOUT_CONTRACT §4): the send buffer is wedged, so close, then surface
                    // the uniform typed Write timeout. An UntilClosed write submits with no linked
                    // timeout and never lands here.
                    closeInternal()
                    throw SocketTimeoutException(TimeoutContext.Write(deadline))
                }
                handleWriteError(errorCode)
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

    private fun handleSslWriteError(
        result: Int,
        deadline: Duration,
    ): Nothing {
        val error = SSL_get_error(ssl, result)
        when (error) {
            SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE -> {
                throw SocketTimeoutException(TimeoutContext.Write(deadline))
            }
            else -> {
                throw SSLProtocolException("SSL write error: ${getOpenSSLError()}")
            }
        }
    }

    private fun handleWriteError(errorCode: Int): Nothing {
        val ex = mapErrnoToException(errorCode, "send")
        if (ex is SocketClosedException) closeInternal()
        throw ex
    }

    /**
     * Get the effective read buffer size.
     * Returns [IoTuning.readBufferSize] if explicitly configured (non-default),
     * otherwise returns the cached SO_RCVBUF value queried at connect time.
     */
    private fun getEffectiveReadBufferSize(): Int {
        val configuredSize = config.io.readBufferSize
        // If user explicitly configured a different buffer size, use that
        if (configuredSize != DEFAULT_READ_BUFFER_SIZE) {
            return configuredSize
        }
        // Otherwise use the cached SO_RCVBUF value
        return cachedReadBufferSize
    }

    private fun closeInternal() {
        val wasOpen = sockfd >= 0
        // Promptly cancel a concurrently-parked recv so it returns -ECANCELED (→
        // SocketClosedException) instead of waiting out its full timeout. Closing the
        // fd alone is not prompt on Linux ARM64 (issue #83). Submit the cancel before
        // closing the fd; it targets the recv by user_data, so it is correct regardless
        // of fd state. A same-coroutine close (read already returned) sees 0 here.
        IoUringManager.cancelOperation(pendingReadUserData.getAndSet(0L))
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

    /**
     * Test hook: user_data of the recv currently parked in io_uring, or 0 if no read is
     * in flight. Lets a deterministic test wait until a read is observably parked before
     * racing close() against it (issue #83) instead of guessing with a fixed delay.
     */
    internal fun parkedReadUserDataForTest(): Long = pendingReadUserData.value
}
