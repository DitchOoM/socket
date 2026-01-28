package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.*
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
    internal var sockfd: Int = -1

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) return EMPTY_BUFFER

        // Allocate buffer with native memory for zero-copy io_uring read
        val bufferSize = 65536
        val buffer = PlatformBuffer.allocate(bufferSize, AllocationZone.Direct)

        // Get native memory pointer
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = nativeAccess.nativeAddress.toCPointer<ByteVar>()!!
            val bytesRead = readWithIoUring(ptr, bufferSize, timeout)

            return when {
                bytesRead > 0 -> {
                    // Set buffer position to bytesRead so remaining() returns correct value
                    buffer.position(bytesRead)
                    buffer
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    handleReadError(-bytesRead)
                }
            }
        }

        // Fallback for managed memory (shouldn't happen with AllocationZone.Direct on native)
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val bytesRead =
                array.usePinned { pinned ->
                    readWithIoUring(pinned.addressOf(0), bufferSize, timeout)
                }

            return when {
                bytesRead > 0 -> {
                    buffer.position(bytesRead)
                    buffer
                }
                bytesRead == 0 -> {
                    closeInternal()
                    throw SocketClosedException("Connection closed by peer")
                }
                else -> {
                    handleReadError(-bytesRead)
                }
            }
        }

        throw SocketException("Buffer has no accessible memory for io_uring read")
    }

    private suspend fun readWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val fd = sockfd
        val result =
            IoUringManager.submitAndWait(timeout) { sqe, _ ->
                io_uring_prep_recv(sqe, fd, ptr, size.convert(), 0)
            }
        return result
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
        if (sockfd < 0) return -1

        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr =
                (nativeAccess.nativeAddress + buffer.position())
                    .toCPointer<ByteVar>()!!
            val bytesSent = writeWithIoUring(ptr, remaining, timeout)
            return when {
                bytesSent >= 0 -> {
                    buffer.position(buffer.position() + bytesSent)
                    bytesSent
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
                val bytesSent = writeWithIoUring(pinned.addressOf(offset), remaining, timeout)
                when {
                    bytesSent >= 0 -> {
                        buffer.position(buffer.position() + bytesSent)
                        bytesSent
                    }
                    else -> handleWriteError(-bytesSent)
                }
            }
        }

        throw SocketException("Buffer has no accessible memory for io_uring write")
    }

    private suspend fun writeWithIoUring(
        ptr: CPointer<ByteVar>,
        size: Int,
        timeout: Duration,
    ): Int {
        val fd = sockfd
        val result =
            IoUringManager.submitAndWait(timeout) { sqe, _ ->
                io_uring_prep_send(sqe, fd, ptr, size.convert(), 0)
            }
        return result
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

    private fun closeInternal() {
        if (sockfd >= 0) {
            closeSocket(sockfd)
            sockfd = -1
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
