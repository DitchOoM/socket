package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * A [ByteStream] that can finish its **send** side independently of its read side.
 *
 * [shutdownSend] sends a QUIC stream FIN (so the peer sees end-of-request) while leaving
 * the read side open to receive the response — the half-close HTTP/3 request/response needs
 * (RFC 9114 §4: the client FINs the request stream, then reads the response). After
 * [shutdownSend], [read] still works; [write] throws.
 */
interface HalfCloseableByteStream : ByteStream {
    /** Send a send-side FIN. Idempotent; a no-op once the stream is fully [close]d. */
    suspend fun shutdownSend()
}

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
) : HalfCloseableByteStream {
    @Volatile
    private var closed = false

    @Volatile
    private var sendFinished = false

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
        check(!sendFinished) { "QuicByteStream($streamId) send side is finished" }
        return delegate.write(buffer, timeout)
    }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): BytesWritten {
        check(!closed) { "QuicByteStream($streamId) is closed" }
        check(!sendFinished) { "QuicByteStream($streamId) send side is finished" }
        return delegate.writeGathered(buffers, timeout)
    }

    override suspend fun shutdownSend() {
        if (closed || sendFinished) return
        sendFinished = true
        // Delegate FINs the send side if it can; otherwise this is a best-effort no-op
        // (e.g. a scripted test stream that records writes). Production delegates
        // (QuicheStreamByteStream / NWQuicByteStream) implement the half-close.
        (delegate as? HalfCloseableByteStream)?.shutdownSend()
    }

    override suspend fun close() {
        if (closed) return // idempotent
        closed = true
        delegate.close()
    }
}
