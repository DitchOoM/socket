package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Mock [ClientToServerSocket] for protocol-level testing without network I/O.
 *
 * Enqueue server responses via [enqueueRead] / [enqueueReadBytes] and
 * inspect client writes via [writtenBuffers].
 */
class MockClientToServerSocket : ClientToServerSocket {
    private var open = false
    private val readQueue = Channel<Result<ReadBuffer>>(Channel.UNLIMITED)
    val writtenBuffers = mutableListOf<ReadBuffer>()
    var openCalled = false
        private set

    fun enqueueRead(buffer: ReadBuffer) {
        readQueue.trySend(Result.success(buffer))
    }

    fun enqueueReadBytes(vararg bytes: Byte) {
        val buffer = PlatformBuffer.allocate(bytes.size)
        for (b in bytes) buffer.writeByte(b)
        // Leave position at end of written data (like a real socket read).
        // Caller should call resetForRead() on the result of read().
        enqueueRead(buffer)
    }

    fun enqueueReadError(exception: Exception) {
        readQueue.trySend(Result.failure(exception))
    }

    fun simulateDisconnect() {
        open = false
        readQueue.trySend(Result.failure(SocketClosedException("Mock disconnect")))
    }

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions,
    ) {
        open = true
        openCalled = true
    }

    override fun isOpen() = open

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!open) throw SocketClosedException("Mock socket is closed")
        val result =
            withTimeout(timeout) {
                readQueue.receive()
            }
        return result.getOrThrow()
    }

    override suspend fun read(
        buffer: WriteBuffer,
        timeout: Duration,
    ): Int {
        val readBuffer = read(timeout)
        readBuffer.resetForRead()
        val bytesRead = readBuffer.remaining()
        buffer.write(readBuffer)
        return bytesRead
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (!open) throw SocketClosedException("Mock socket is closed")
        val bytes = buffer.remaining()
        // Copy the buffer content so the original can be reused
        val copy = PlatformBuffer.allocate(bytes)
        copy.write(buffer)
        copy.resetForRead()
        writtenBuffers.add(copy)
        return bytes
    }

    override suspend fun close() {
        open = false
        readQueue.close()
    }

    override suspend fun localPort() = 12345

    override suspend fun remotePort() = 80

    fun verifyWriteCount(expected: Int) {
        assertEquals(expected, writtenBuffers.size, "Expected $expected writes, got ${writtenBuffers.size}")
    }

    fun verifyOpened() {
        assertTrue(openCalled, "Expected open() to have been called")
    }
}
