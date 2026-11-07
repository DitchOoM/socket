package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.transport.MuxIdentified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.concurrent.Volatile
import kotlin.time.Duration

// Half-close and reset are buffer-flow's cross-platform capability markers
// ([HalfCloseable] / [Resettable]) — reset is deliberately orthogonal (not a [ByteStream]) so it
// mixes onto send-only / receive-only streams too. socket-quic defines no duplicates of its own;
// every QUIC/WebTransport stream speaks the one capability vocabulary, so an `is`-smart-cast to a
// capability works uniformly across native and browser backings.

/**
 * A single QUIC stream exposed as a [ByteStream].
 *
 * Wraps a bidirectional or unidirectional QUIC stream. Read/write operations
 * delegate to the underlying QUIC engine (quiche).
 *
 * State guards prevent use-after-close — calling [read] or [write] after [close]
 * throws [IllegalStateException] immediately rather than returning garbage. [write]
 * (but not [read]) also throws after [shutdownSend], which finishes only the send side.
 *
 * The constructor is public so layers built on top of this module (e.g. the HTTP/3
 * client in `:socket-http3`) can wrap a [ByteStream] with a known [streamId] — most
 * usefully to fabricate scripted streams in their tests. Production code obtains
 * instances from [QuicScope.openStream]/[openUniStream]/[acceptStream], not by
 * constructing them directly.
 *
 * [origin] says whether what arrives on the stream can be a 0-RTT replay. Reads of a
 * [QuicStreamOrigin.Early] stream wait for the handshake to be confirmed unless [markReplaySafe] is called.
 */
class QuicByteStream(
    val streamId: QuicStreamId,
    private val delegate: ByteStream,
    val origin: QuicStreamOrigin,
) : ByteStream,
    HalfCloseable,
    Resettable,
    MuxIdentified {
    /** A stream nothing can replay: opened locally, or by the peer on a confirmed handshake. */
    constructor(streamId: QuicStreamId, delegate: ByteStream) : this(streamId, delegate, QuicStreamOrigin.Confirmed)

    override val muxStreamId: Long get() = streamId.id

    private val earlyReads: EarlyReads =
        when (origin) {
            QuicStreamOrigin.Confirmed -> EarlyReads.Open
            is QuicStreamOrigin.Early -> EarlyReads.Gated(origin)
        }

    /**
     * Read this stream without waiting for the handshake to be confirmed — **only if processing what it
     * carries twice is harmless**. An attacker who captured the client's first flight can replay a
     * [QuicStreamOrigin.Early] stream's bytes on a connection that will never be confirmed; a read that
     * waits never hands them over, one marked replay-safe does.
     *
     * A no-op on a [QuicStreamOrigin.Confirmed] stream and once the handshake is confirmed; a read already
     * waiting for confirmation proceeds at once.
     */
    fun markReplaySafe() {
        when (val gate = earlyReads) {
            EarlyReads.Open -> Unit
            is EarlyReads.Gated -> gate.open.complete(Unit)
        }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var sendFinished = false

    override val isOpen: Boolean get() = !closed && delegate.isOpen

    override val readPolicy: ReadPolicy get() = delegate.readPolicy

    override val writePolicy: WritePolicy get() = delegate.writePolicy

    /**
     * Read the next chunk, **transferring the buffer to the caller**: on [ReadResult.Data] the caller
     * owns `buffer` and must release it with `buffer.freeIfNeeded()` once it is done with the bytes.
     *
     * There is no factory under which forgetting that is safe. The QUIC read path always draws
     * from a per-connection pool, so an unreleased buffer is a pool slot that never comes back and
     * every later read allocates fresh; and under the default [com.ditchoom.buffer.BufferFactory.Default]
     * the native allocation behind the buffer is owned by the collector, which schedules itself on
     * managed-heap pressure and therefore does not run for a pointer-sized wrapper in front of 64 KB of
     * native memory. The two compound: a reader that drops every buffer grows address space without
     * bound until a native allocation fails.
     *
     * **Use the scoped [read] (`read(deadline) { … }`, returning [ScopedRead]) unless the bytes have to
     * outlive the call** — it releases on every exit path, exception and cancellation included. This
     * overload is for the callers that genuinely keep the buffer, and it is the one the codec/mux path
     * uses: `CodecConnection` hands each chunk to its `StreamProcessor`, which takes ownership.
     *
     * Note the asymmetry with [write], which is zero-copy and takes **no** ownership: the caller still
     * owns the buffer it wrote and still frees it. Read transfers, write does not.
     *
     * ### Terminal results
     * [ReadResult.End] means the peer sent FIN; [ReadResult.Reset] means the peer sent RESET_STREAM.
     * Bytes the transport accepted before either are delivered first.
     *
     * ### Early streams
     * The first read of a [QuicStreamOrigin.Early] stream not [marked replay-safe][markReplaySafe] waits
     * for the handshake to be confirmed, then reads under [deadline]. The handshake bounds that wait
     * itself: it ends confirmed, or closed with the reason this throws.
     *
     * @throws QuicCloseException when the connection ends without the peer's FIN or reset for this
     *   stream — an idle timeout, a CONNECTION_CLOSE from either side — after any bytes the transport
     *   already accepted have been delivered. [QuicCloseException.closeReason] says why.
     * @throws IllegalStateException after [close].
     */
    override suspend fun read(deadline: Duration): ReadResult {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        when (val gate = earlyReads) {
            EarlyReads.Open -> Unit
            is EarlyReads.Gated -> if (!gate.open.isCompleted) gate.awaitOpen()
        }
        return delegate.read(deadline)
    }

    /**
     * Write [buffer]'s remaining bytes to the stream, zero-copy: the backend reads the buffer in place
     * and takes no ownership — the caller frees it after the call returns (a returned count may be
     * partial; re-enter for the remainder, or use `writeFully`).
     *
     * ### Buffer requirement
     * Where the connection's [QuicScope.capabilities] declares
     * [QuicCapabilities.requiresNativeMemoryBuffers], [buffer] must be backed by native memory — every
     * quiche-backed connection, on every platform, hands the buffer's raw address to the engine.
     * Allocate from [QuicScope.bufferFactory] (or `BufferFactory.deterministic()`); a
     * `BufferFactory.Default` buffer satisfies this on the JVM (direct) but **not** on Linux
     * Kotlin/Native (managed `ByteArray`), which is why the requirement is a capability rather than a
     * platform fact.
     *
     * @throws QuicNativeMemoryRequiredException if the connection requires native memory and [buffer]
     *   has none — a caller-contract violation, raised before anything is enqueued; the stream is
     *   unaffected and a correctly allocated write can follow.
     * @throws IllegalStateException after [close] or [shutdownSend].
     * @throws QuicStreamException if the peer aborted this stream (STOP_SENDING / RESET_STREAM).
     * @throws QuicCloseException if the connection closed.
     */
    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        check(!sendFinished) { "QuicByteStream($streamId) send side is finished" }
        return delegate.write(buffer, deadline)
    }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        check(!sendFinished) { "QuicByteStream($streamId) send side is finished" }
        return delegate.writeGathered(buffers, deadline)
    }

    /**
     * Write all of [buffer] and finish the send side, with the FIN carried by the last bytes rather than
     * sent after them: a reply that fits one packet leaves in one. Equivalent to `writeFully` followed by
     * [shutdownSend] otherwise, and the same zero-copy, no-ownership contract as [write].
     *
     * @throws IllegalStateException after [close] or [shutdownSend].
     * @throws QuicStreamException if the peer aborted this stream (STOP_SENDING / RESET_STREAM).
     * @throws QuicCloseException if the connection closed.
     */
    suspend fun writeFullyAndShutdownSend(
        buffer: ReadBuffer,
        deadline: Duration = writePolicy.toDeadline(),
    ) {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        check(!sendFinished) { "QuicByteStream($streamId) send side is finished" }
        when (val sink = delegate) {
            is FinishingWrite -> {
                sink.writeFullyAndShutdownSend(buffer, deadline)
                sendFinished = true
            }
            else -> {
                delegate.writeFully(buffer, deadline)
                shutdownSend()
            }
        }
    }

    override suspend fun shutdownSend() {
        if (closed || sendFinished) return
        sendFinished = true
        // Delegate FINs the send side if it can; otherwise this is a best-effort no-op
        // (e.g. a scripted test stream that records writes). Production delegates
        // (QuicheStreamByteStream) implement the half-close.
        (delegate as? HalfCloseable)?.shutdownSend()
    }

    override suspend fun close() {
        if (closed) return // idempotent
        closed = true
        delegate.close()
    }

    override suspend fun reset(errorCode: Long) {
        if (closed) return // idempotent; also a no-op once close()d
        closed = true
        // Production delegates (QuicheStreamByteStream) send RESET_STREAM +
        // STOP_SENDING; a scripted test delegate that isn't resettable just gets a graceful close.
        (delegate as? Resettable)?.reset(errorCode) ?: delegate.close()
    }
}

/** Whether reads of a [QuicByteStream] wait for the handshake its [QuicStreamOrigin.Early] origin awaits. */
private sealed interface EarlyReads {
    /** Nothing to wait for: the stream is [QuicStreamOrigin.Confirmed]. */
    data object Open : EarlyReads

    /** An early stream; [open] completes when the handshake is confirmed or the stream is marked replay-safe. */
    class Gated(
        private val origin: QuicStreamOrigin.Early,
    ) : EarlyReads {
        val open = CompletableDeferred<Unit>()

        /** Suspend until [open]; throws the connection's [QuicCloseException] if the handshake fails first. */
        suspend fun awaitOpen(): Unit =
            coroutineScope {
                val confirmed = async { origin.awaitConfirmation() }
                select {
                    confirmed.onAwait { open.complete(Unit) }
                    open.onAwait { }
                }
                confirmed.cancel()
            }
    }
}

/**
 * A send side that can carry its last bytes and its FIN in one write. [QuicByteStream.writeFullyAndShutdownSend]
 * uses it when the stream it wraps offers it; an engine's stream implements it.
 */
interface FinishingWrite : HalfCloseable {
    /** Write all of [buffer] and finish the send side with its last bytes. */
    suspend fun writeFullyAndShutdownSend(
        buffer: ReadBuffer,
        deadline: Duration,
    )
}
