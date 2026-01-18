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

/**
 * Base socket wrapper using io_uring for async I/O on Linux.
 *
 * Provides true zero-copy read/write operations using NativeBuffer.
 * No ByteArray intermediaries - data flows directly between native memory and io_uring.
 */
@OptIn(ExperimentalForeignApi::class)
open class LinuxSocketWrapper : ClientSocket {
    internal var sockfd: Int = -1

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) return EMPTY_BUFFER

        // Allocate native buffer for zero-copy read
        val buffer = PlatformBuffer.allocate(65536, AllocationZone.Direct)
        val nativeAccess = buffer.nativeMemoryAccess
            ?: throw SocketException("Failed to get native memory access")
        val ptr = nativeAccess.nativeAddress.toCPointer<kotlinx.cinterop.ByteVar>()!!

        val bytesRead = readWithIoUring(ptr, buffer.capacity, timeout)

        return when {
            bytesRead > 0 -> {
                // Set position to bytes read, then reset for reading
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
                handleReadError((-bytesRead).toInt())
            }
        }
    }

    private fun readWithIoUring(
        ptr: CPointer<kotlinx.cinterop.ByteVar>,
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

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        if (sockfd < 0) return -1

        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        // Zero-copy path: check if buffer has native memory access
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr = (nativeAccess.nativeAddress + buffer.position())
                .toCPointer<kotlinx.cinterop.ByteVar>()!!
            val bytesSent = writeWithIoUring(ptr, remaining, timeout)
            return when {
                bytesSent >= 0 -> {
                    buffer.position(buffer.position() + bytesSent.toInt())
                    bytesSent.toInt()
                }
                else -> handleWriteError((-bytesSent).toInt())
            }
        }

        // Zero-copy path: check if buffer has managed array access
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val array = managedAccess.backingArray
            val offset = managedAccess.arrayOffset + buffer.position()
            return array.usePinned { pinned ->
                val bytesSent = writeWithIoUringPinned(pinned.addressOf(offset), remaining, timeout)
                when {
                    bytesSent >= 0 -> {
                        buffer.position(buffer.position() + bytesSent.toInt())
                        bytesSent.toInt()
                    }
                    else -> handleWriteError((-bytesSent).toInt())
                }
            }
        }

        // Fallback: copy to temporary array (should rarely happen)
        val bytes = buffer.readByteArray(remaining)
        return bytes.usePinned { pinned ->
            val bytesSent = writeWithIoUringPinned(pinned.addressOf(0), bytes.size, timeout)
            when {
                bytesSent >= 0 -> bytesSent.toInt()
                else -> handleWriteError((-bytesSent).toInt())
            }
        }
    }

    private fun writeWithIoUring(
        ptr: CPointer<kotlinx.cinterop.ByteVar>,
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

    private fun writeWithIoUringPinned(
        ptr: CPointer<kotlinx.cinterop.ByteVar>,
        size: Int,
        timeout: Duration,
    ): Long = writeWithIoUring(ptr, size, timeout)

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
        if (sockfd >= 0) {
            closeSocket(sockfd)
            sockfd = -1
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
