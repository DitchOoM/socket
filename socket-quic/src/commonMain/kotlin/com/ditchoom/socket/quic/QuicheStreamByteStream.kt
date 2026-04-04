package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.transport.ByteStream
import com.ditchoom.socket.transport.BytesWritten
import com.ditchoom.socket.transport.ReadResult
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * Platform-agnostic interface for a QUIC stream backed by a quiche connection.
 *
 * The platform-specific event loop implements this to bridge quiche's
 * `conn_stream_recv` / `conn_stream_send` into the [ByteStream] contract.
 *
 * Implementations must:
 * - Call quiche `conn_stream_recv` with a buffer from [bufferFactory]
 * - Pass the buffer's native address (zero-copy)
 * - Drive the quiche event loop to flush outgoing packets after writes
 */
interface QuicheStreamAdapter {
    /**
     * Read from the quiche stream into a buffer allocated from [bufferFactory].
     * Returns [ReadResult.Data] with the buffer, [ReadResult.End] on FIN,
     * or throws on error/timeout.
     */
    suspend fun streamRead(
        streamId: QuicStreamId,
        bufferFactory: BufferFactory,
        bufferSize: Int,
        timeout: Duration,
    ): ReadResult

    /**
     * Write [buffer]'s remaining bytes to the quiche stream.
     * The buffer's native address is passed directly to quiche (zero-copy).
     * Returns bytes written, or throws on error.
     */
    suspend fun streamWrite(
        streamId: QuicStreamId,
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int

    /** Close the stream (send FIN). */
    suspend fun streamClose(streamId: QuicStreamId)
}

/**
 * [ByteStream] backed by a single quiche stream.
 * Delegates to [QuicheStreamAdapter] which drives the platform-specific event loop.
 */
class QuicheStreamByteStream(
    val streamId: QuicStreamId,
    private val adapter: QuicheStreamAdapter,
    private val bufferFactory: BufferFactory = BufferFactory.deterministic(),
    private val bufferSize: Int = 65536,
) : ByteStream {
    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed

    override suspend fun read(timeout: Duration): ReadResult {
        check(!closed) { "QuicheStreamByteStream($streamId) is closed" }
        return adapter.streamRead(streamId, bufferFactory, bufferSize, timeout)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten {
        check(!closed) { "QuicheStreamByteStream($streamId) is closed" }
        val written = adapter.streamWrite(streamId, buffer, timeout)
        return BytesWritten(written)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        adapter.streamClose(streamId)
    }
}
