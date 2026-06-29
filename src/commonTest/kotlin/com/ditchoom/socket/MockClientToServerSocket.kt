package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
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
        val buffer = BufferFactory.Default.allocate(bytes.size)
        for (b in bytes) buffer.writeByte(b)
        buffer.resetForRead()
        enqueueRead(buffer)
    }

    fun enqueueReadError(exception: Exception) {
        readQueue.trySend(Result.failure(exception))
    }

    fun simulateDisconnect() {
        open = false
        readQueue.trySend(Result.failure(SocketClosedException.General("Mock disconnect")))
    }

    private var config: TransportConfig = TransportConfig()

    override suspend fun open(
        port: Int,
        hostname: String?,
        config: TransportConfig,
    ) {
        this.config = config
        open = true
        openCalled = true
    }

    override val isOpen: Boolean get() = open

    override val readPolicy: ReadPolicy get() = config.readPolicy
    override val writePolicy: WritePolicy get() = config.writePolicy

    override suspend fun read(deadline: Duration): ReadResult =
        translateRead {
            if (!open) throw SocketClosedException.General("Mock socket is closed")
            withTimeout(deadline) { readQueue.receive() }.getOrThrow()
        }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (!open) throw SocketClosedException.General("Mock socket is closed")
        val bytes = buffer.remaining()
        // Copy the buffer content so the original can be reused
        val copy = BufferFactory.Default.allocate(bytes)
        copy.write(buffer)
        copy.resetForRead()
        writtenBuffers.add(copy)
        return BytesWritten(bytes)
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
