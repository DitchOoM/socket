package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.socket.posix.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Duration

/**
 * Base socket wrapper using io_uring for async I/O on Linux.
 *
 * Provides zero-copy read/write operations with timeout support.
 */
@OptIn(ExperimentalForeignApi::class)
open class LinuxSocketWrapper : ClientSocket {
    internal var sockfd: Int = -1

    // Pinned buffer for zero-copy reads
    private var readBuffer: ByteArray? = null
    private var pinnedReadBuffer: Pinned<ByteArray>? = null

    override fun isOpen(): Boolean = sockfd >= 0

    override suspend fun localPort(): Int = getLocalPort(sockfd)

    override suspend fun remotePort(): Int = getRemotePort(sockfd)

    /**
     * Initialize the read buffer. Called after accept() for server-side sockets.
     */
    fun initReadBuffer() {
        if (readBuffer == null) {
            readBuffer = ByteArray(65536)
            pinnedReadBuffer = readBuffer!!.pin()
        }
    }

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (sockfd < 0) return EMPTY_BUFFER

        // Ensure buffer is initialized
        if (readBuffer == null) {
            initReadBuffer()
        }

        val buffer = readBuffer ?: return EMPTY_BUFFER
        val pinned = pinnedReadBuffer ?: return EMPTY_BUFFER

        val bytesRead = readWithIoUring(pinned, buffer.size, timeout)

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
                handleReadError((-bytesRead).toInt())
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
        val bytesSent = writeWithIoUring(bytes, timeout)

        return when {
            bytesSent >= 0 -> bytesSent.toInt()
            else -> handleWriteError((-bytesSent).toInt())
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

        if (sockfd >= 0) {
            closeSocket(sockfd)
            sockfd = -1
        }
    }

    override suspend fun close() {
        closeInternal()
    }
}
