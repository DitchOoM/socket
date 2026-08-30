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
     * concrete buffer: a pool-return for pooled factories (which is what every
     * production construction site passes — [QuicheDriver.streamReadPool]), and
     * an actual free for off-heap `deterministic()` buffers.
     *
     * **Skipping the release is never harmless, on any factory** (#538). Under
     * the default [BufferFactory.Default] the native allocation behind the
     * buffer is owned by the *collector* — an `Arena.ofAuto()` segment on
     * JDK 21+, a `Cleaner`-backed direct `ByteBuffer` on JDK 17 / Android — and
     * the collector schedules itself on *managed-heap* pressure, which a
     * pointer-sized wrapper in front of a 64 KB native allocation does not
     * produce. And because production always reads through a pool, a buffer
     * that is never released is a pool slot that is never returned, so every
     * later read misses the pool and allocates fresh. That compounds without
     * bound: a device walk that read and dropped reached 20.8 GB of address
     * space in 2 h 36 m and died of `std::bad_alloc`.
     *
     * Callers that do not keep the bytes should not be releasing anything by
     * hand — use the scoped `read(deadline) { … }` (`ScopedRead`, in
     * `:socket-quic`), which releases on every exit path including exception
     * and cancellation. The codec/mux path already honors ownership without it:
     * a `CodecConnection` hands the buffer to its `StreamProcessor`, which takes
     * ownership and frees each chunk on consume and on `release()` — so
     * injecting a pooled or `deterministic()` factory via
     * `TransportConfig.bufferFactory` through `withQuicMux` leaks nothing.
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

    /**
     * Release any bytes the implementation buffered for this stream that can no longer be delivered —
     * called once the read side is gone for good ([QuicheStreamByteStream.close] / [reset], after which
     * `read()` is rejected). The driver-backed implementation frees the chunks it drained out of quiche
     * at connection teardown (see [StreamSlot.pendingData]); an implementation that buffers nothing has
     * nothing to release, hence the no-op default.
     */
    fun releaseUndeliveredReads() {}
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
    // Request/response-shaped default (unchanged pre-existing behavior). Every construction site
    // driven by a real connection instead passes QuicheDriver.streamReadPolicy /
    // QuicheDriver.streamWritePolicy, which honor QuicOptions.persistentStreams — see that option's
    // doc for why a stream-level deadline is a DIFFERENT thing from the connection's idle timeout.
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(DEFAULT_STREAM_DEADLINE),
    override val writePolicy: WritePolicy = WritePolicy.Bounded(DEFAULT_STREAM_DEADLINE),
) : ByteStream,
    HalfCloseable,
    Resettable {
    @Volatile
    private var closed = false

    @Volatile
    private var sendFinished = false

    override val isOpen: Boolean get() = !closed

    /**
     * Read the next chunk from the stream, **transferring the buffer to the
     * caller**.
     *
     * On [ReadResult.Data] the returned buffer is caller-owned and the caller
     * must release it via `buffer.freeIfNeeded()` once consumed. Skipping that
     * leaks native memory on every platform and every factory — including the
     * default [BufferFactory.Default], whose native allocation is owned by the
     * collector and whose collector will not run on native pressure, and
     * especially under the pool this stream is always constructed with, where
     * an unreleased buffer permanently costs a pool slot. See
     * [QuicheStreamAdapter.streamRead] for the full contract and #538 for what
     * it cost to believe otherwise.
     *
     * **Prefer the scoped `read(deadline) { … }`** (`ScopedRead`, in
     * `:socket-quic`) unless the bytes genuinely have to outlive the call: it
     * releases on every exit path, including exception and cancellation, so the
     * release is not the caller's to remember. This overload stays for the
     * callers who really do keep the buffer — the codec/mux path
     * (`CodecConnection` → `StreamProcessor`) is the production example, and it
     * frees each chunk on consume and on `release()`.
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
        // read() is rejected from here on, so any teardown-drained chunk left undelivered is
        // unreachable — release it instead of holding a pooled/native buffer for the slot's lifetime.
        adapter.releaseUndeliveredReads()
    }

    override suspend fun reset(errorCode: Long) {
        if (closed) return
        closed = true
        // Abort both directions: RESET_STREAM (write) then STOP_SENDING (read), RFC 9000 §19.4/§19.5.
        adapter.streamShutdown(streamId, direction = 1, errorCode)
        adapter.streamShutdown(streamId, direction = 0, errorCode)
        adapter.releaseUndeliveredReads()
    }

    companion object {
        /** Default per-call deadline for a request/response-shaped stream (`QuicOptions.persistentStreams = false`). */
        val DEFAULT_STREAM_DEADLINE: Duration = 15.seconds
    }
}
