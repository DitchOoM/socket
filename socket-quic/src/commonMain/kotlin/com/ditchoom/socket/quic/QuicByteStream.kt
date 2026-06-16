package com.ditchoom.socket.quic

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

// Half-close and reset are buffer-flow's cross-platform capability markers
// ([HalfCloseable] / [Resettable]) — reset is deliberately orthogonal (not a [ByteStream]) so it
// mixes onto send-only / receive-only streams too. socket-quic no longer defines its own duplicates;
// every QUIC/WebTransport stream speaks the one capability vocabulary, so an `is`-smart-cast to a
// capability works uniformly across native and browser backings.

/**
 * A single QUIC stream exposed as a [ByteStream].
 *
 * Wraps a bidirectional or unidirectional QUIC stream. Read/write operations
 * delegate to the underlying QUIC engine (quiche or Network.framework).
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
 */
class QuicByteStream(
    val streamId: QuicStreamId,
    private val delegate: ByteStream,
) : ByteStream,
    HalfCloseable,
    Resettable {
    @Volatile
    private var closed = false

    @Volatile
    private var sendFinished = false

    override val isOpen: Boolean get() = !closed && delegate.isOpen

    override val readPolicy: ReadPolicy get() = delegate.readPolicy

    override val writePolicy: WritePolicy get() = delegate.writePolicy

    override suspend fun read(deadline: Duration): ReadResult {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        return delegate.read(deadline)
    }

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

    override suspend fun shutdownSend() {
        if (closed || sendFinished) return
        sendFinished = true
        // Delegate FINs the send side if it can; otherwise this is a best-effort no-op
        // (e.g. a scripted test stream that records writes). Production delegates
        // (QuicheStreamByteStream / NWQuicByteStream) implement the half-close.
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
        // Production delegates (QuicheStreamByteStream / NWQuicByteStream) send RESET_STREAM +
        // STOP_SENDING; a scripted test delegate that isn't resettable just gets a graceful close.
        (delegate as? Resettable)?.reset(errorCode) ?: delegate.close()
    }
}
