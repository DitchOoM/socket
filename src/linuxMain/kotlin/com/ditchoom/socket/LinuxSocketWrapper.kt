package com.ditchoom.socket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Base socket wrapper using io_uring for async I/O on Linux.
 *
 * Provides true zero-copy read/write operations using PlatformBuffer with native memory.
 * Uses IoUringManager for proper completion dispatch, ensuring concurrent operations
 * on multiple sockets don't interfere with each other.
 */
@OptIn(ExperimentalForeignApi::class)
open class LinuxSocketWrapper : ClientSocket {
    /**
     * The injected transport configuration. Server-accepted sockets inherit the default until
     * a config is applied via [configure]; buffer allocation and read/write policy both read
     * from here rather than from a mutable per-socket property.
     */
    protected var config: TransportConfig = TransportConfig()

    /**
     * Single per-connection source of io_uring receive buffers — a pool over [config]'s buffer factory
     * so `readRaw` reuses buffers instead of allocating a fresh GC-reclaimed one per read (see
     * [ReadBufferSource]). Lazy so it captures the [config] applied at open/configure time.
     */
    protected val readBufferSource: ReadBufferSource by lazy { ReadBufferSource(config) }

    private val descriptor = SocketDescriptor()

    /** Makes an accepted connection's descriptor this socket's. */
    internal fun adopt(fd: Int) {
        descriptor.adopt(fd)
        cachedReadBufferSize = descriptor.withOpenNow(::getSocketReceiveBufferSize)
    }

    /**
     * Cached socket receive buffer size from SO_RCVBUF.
     * Queried once when socket fd is set and reused for all read operations.
     * Can be overridden by [IoTuning.readBufferSize] if configured.
     */
    private var cachedReadBufferSize: Int = DEFAULT_READ_BUFFER_SIZE

    override val isOpen: Boolean get() = descriptor.isOpen

    override val readPolicy: ReadPolicy get() = config.readPolicy

    override val writePolicy: WritePolicy get() = config.writePolicy

    /** Apply a [TransportConfig] to a server-accepted socket. */
    internal fun configure(config: TransportConfig) {
        this.config = config
    }

    override suspend fun localPort(): Int = descriptor.withOpenNowOr(-1, ::getLocalPort)

    override suspend fun remotePort(): Int = descriptor.withOpenNowOr(-1, ::getRemotePort)

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    private suspend fun readRaw(deadline: Duration): ReadBuffer = descriptor.withOpen { fd -> readOpen(fd, deadline) }

    private suspend fun readOpen(
        fd: Int,
        deadline: Duration,
    ): ReadBuffer {
        // Allocate buffer with native memory for zero-copy io_uring read
        // Use IoTuning override if explicitly set, otherwise use cached SO_RCVBUF
        val bufferSize = getEffectiveReadBufferSize()
        val buffer = readBufferSource.acquire(bufferSize)

        try {
            // Get native memory pointer
            val nativeAccess = buffer.nativeMemoryAccess
            if (nativeAccess != null) {
                val ptr = nativeAccess.nativeAddress.toCPointer<ByteVar>()!!
                val bytesRead = readWithIoUring(fd, ptr, bufferSize, deadline)

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
                        handleReadError(-bytesRead)
                    }
                }
            }

            // Fallback for managed memory (shouldn't happen with Direct allocation on native)
            val managedAccess = buffer.managedMemoryAccess
            if (managedAccess != null) {
                val array = managedAccess.backingArray
                val bytesRead =
                    array.usePinned { pinned ->
                        readWithIoUring(fd, pinned.addressOf(0), bufferSize, deadline)
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
                        handleReadError(-bytesRead)
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

    private suspend fun readWithIoUring(
        fd: Int,
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int =
        descriptor.submit(fd, SocketDescriptor.Lane.Read, timeout) { sqe, open ->
            io_uring_prep_recv(sqe, open, ptr, size.convert(), 0)
        }

    private fun handleReadError(errorCode: Int): Nothing {
        val ex = mapErrnoToException(errorCode, "recv")
        if (ex is SocketClosedException) closeInternal()
        throw ex
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = descriptor.withOpen { fd -> writeOpen(fd, buffer, deadline) }

    private suspend fun writeOpen(
        fd: Int,
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        val remaining = buffer.remaining()
        if (remaining == 0) return BytesWritten(0)

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr =
                (nativeAccess.nativeAddress + buffer.position())
                    .toCPointer<ByteVar>()!!
            val bytesSent = writeWithIoUring(fd, ptr, remaining, deadline)
            return when {
                bytesSent >= 0 -> {
                    buffer.position(buffer.position() + bytesSent)
                    BytesWritten(bytesSent)
                }
                else -> handleWriteError(-bytesSent)
            }
        }

        // Zero-copy path: check if buffer has managed array access
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val offset = managedAccess.arrayOffset + buffer.position()
            return array.usePinned { pinned ->
                val bytesSent = writeWithIoUring(fd, pinned.addressOf(offset), remaining, deadline)
                when {
                    bytesSent >= 0 -> {
                        buffer.position(buffer.position() + bytesSent)
                        BytesWritten(bytesSent)
                    }
                    else -> handleWriteError(-bytesSent)
                }
            }
        }

        throw SocketIOException("Buffer has no accessible memory for io_uring write")
    }

    private suspend fun writeWithIoUring(
        fd: Int,
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int =
        descriptor.submit(fd, SocketDescriptor.Lane.Write, timeout) { sqe, open ->
            io_uring_prep_send(sqe, open, ptr, size.convert(), 0)
        }

    private fun handleWriteError(errorCode: Int): Nothing {
        val ex = mapErrnoToException(errorCode, "send")
        if (ex is SocketClosedException) closeInternal()
        throw ex
    }

    /**
     * Get the effective read buffer size.
     * Returns [IoTuning.readBufferSize] if explicitly configured (non-default),
     * otherwise returns the cached SO_RCVBUF value queried at accept time.
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

    /** Refuses further reads and writes and cancels any parked one; the last one out closes the descriptor. */
    private fun closeInternal() {
        descriptor.close()
    }

    override suspend fun close() {
        closeInternal()
    }
}
