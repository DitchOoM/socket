package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
     *
     * ### Buffer ownership
     * On [ReadResult.Data], ownership of the returned buffer transfers to the
     * caller. The implementation frees the buffer itself on every other path
     * (FIN, error, closed channel) but **not** on the data path — there is no
     * release point here by design. The caller must release it when fully
     * consumed via `buffer.freeIfNeeded()` (equivalently
     * `PlatformBuffer.freeNativeMemory()`), which is polymorphic on the
     * concrete buffer: a no-op for heap buffers (the default
     * [BufferFactory.Default], reclaimed by GC), an actual free for off-heap
     * `deterministic()` buffers, and a pool-return for pooled factories.
     *
     * This is what makes a caller-supplied pooled or `deterministic()`
     * [bufferFactory] safe: the codec/mux path already honors it — a
     * `CodecConnection` hands the buffer to its `StreamProcessor`, which takes
     * ownership and frees each chunk on consume and on `release()` — so
     * injecting such a factory via `TransportConfig.bufferFactory` through
     * `withQuicMux` leaks nothing. Only a consumer of the *raw* [read] API who
     * ignores the returned buffer's ownership leaks under a non-GC factory.
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

    /**
     * Shut down [direction] of the stream with application error code [errorCode]:
     * 0 = read (sends STOP_SENDING), 1 = write (sends RESET_STREAM). Used to abruptly abort a stream.
     */
    suspend fun streamShutdown(
        streamId: QuicStreamId,
        direction: Int,
        errorCode: Long,
    )
}

/**
 * [ByteStream] backed by a single quiche stream.
 * Delegates to [QuicheStreamAdapter] which drives the platform-specific event loop.
 */
class QuicheStreamByteStream(
    val streamId: QuicStreamId,
    private val adapter: QuicheStreamAdapter,
    // The driver and every platform facade pass QuicheDriver.streamReadPool here — a per-connection
    // BufferPool over the leaf TransportConfig.bufferFactory — so reads recycle buffers instead of
    // allocating one per read. Any plain BufferFactory also works; it just allocates fresh each read.
    private val bufferFactory: BufferFactory,
    private val bufferSize: Int = QuicheDriver.STREAM_READ_BUFFER_SIZE,
) : ByteStream,
    HalfCloseable,
    Resettable {
    @Volatile
    private var closed = false

    @Volatile
    private var sendFinished = false

    override val isOpen: Boolean get() = !closed

    // QUIC stream policy refinement to UntilClosed (persistent WebTransport streams) is Phase 3
    // work; the request/response-shaped Bounded default is correct for the current stream surface.
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)

    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    /**
     * Read the next chunk from the stream.
     *
     * On [ReadResult.Data] the returned buffer is **caller-owned** — release it
     * via `buffer.freeIfNeeded()` once consumed. Harmless to skip under the
     * default heap [BufferFactory.Default] (GC reclaims), but required to avoid
     * a native leak under a `deterministic()` or pooled factory. See
     * [QuicheStreamAdapter.streamRead] for the full ownership contract; the
     * codec/mux path (`CodecConnection` → `StreamProcessor`) already honors it.
     */
    override suspend fun read(deadline: Duration): ReadResult {
        check(!closed) { "QuicheStreamByteStream($streamId) is closed" }
        return adapter.streamRead(streamId, bufferFactory, bufferSize, deadline)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        check(!closed) { "QuicheStreamByteStream($streamId) is closed" }
        check(!sendFinished) { "QuicheStreamByteStream($streamId) send side is finished" }
        val written = adapter.streamWrite(streamId, buffer, deadline)
        return BytesWritten(written)
    }

    override suspend fun shutdownSend() {
        if (closed || sendFinished) return
        sendFinished = true
        // streamClose maps to quiche stream_send(fin=true) — a send-side FIN only; the read
        // side stays open until the peer's FIN arrives (slot.finReceived in the driver).
        adapter.streamClose(streamId)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Avoid a duplicate FIN if the send side was already shut down.
        if (!sendFinished) adapter.streamClose(streamId)
    }

    override suspend fun reset(errorCode: Long) {
        if (closed) return
        closed = true
        // Abort both directions: RESET_STREAM (write) then STOP_SENDING (read), RFC 9000 §19.4/§19.5.
        adapter.streamShutdown(streamId, direction = 1, errorCode)
        adapter.streamShutdown(streamId, direction = 0, errorCode)
    }
}
