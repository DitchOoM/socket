package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * A single QUIC stream exposed as a [ByteStream].
 *
 * Wraps a bidirectional or unidirectional QUIC stream. Read/write operations
 * delegate to the underlying QUIC engine (quiche or Network.framework).
 *
 * State guards prevent use-after-close — calling [read] or [write] after [close]
 * throws [IllegalStateException] immediately rather than returning garbage.
 */
class QuicByteStream internal constructor(
    val streamId: QuicStreamId,
    private val delegate: ByteStream,
) : ByteStream {
    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed && delegate.isOpen

    override suspend fun read(timeout: Duration): ReadResult {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        return delegate.read(timeout)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        return delegate.write(buffer, timeout)
    }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): BytesWritten {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        return delegate.writeGathered(buffers, timeout)
    }

    override suspend fun close() {
        if (closed) return // idempotent
        closed = true
        delegate.close()
    }
}
