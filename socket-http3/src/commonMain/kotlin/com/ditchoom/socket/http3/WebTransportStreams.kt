package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicStreamException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A WebTransport stream was aborted by the peer (draft-ietf-webtrans-http3 §4.3): [errorCode] is the
 * peer's WebTransport application error code, **decoded** back out of the HTTP/3 error-code space (or
 * null when the backend can't resolve the code — e.g. the JNI quiche binding). Wraps the underlying
 * [QuicStreamException].
 */
class WebTransportStreamException internal constructor(
    val errorCode: Long?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Translate a QUIC stream abort into a WebTransport one, decoding the peer's application error code from
 * the HTTP/3 error-code space (draft-ietf-webtrans-http3 §4.3). The shared reset-observation seam for
 * the WebTransport stream wrappers below.
 */
internal fun QuicStreamException.toWebTransport(): WebTransportStreamException {
    val wtCode = abort.applicationErrorCode?.let { WebTransportWire.toWebTransportErrorCode(it) }
    return WebTransportStreamException(wtCode, "WebTransport stream $streamId aborted by peer: ${abort::class.simpleName}", this)
}

/**
 * A bidirectional WebTransport stream (draft-ietf-webtrans-http3 §4.2). Rides one QUIC bidirectional
 * stream whose first bytes are the `0x41` signal + the Session ID; everything after is the raw
 * application payload this stream carries (no HTTP/3 framing).
 *
 * Obtained from [WebTransportSession.openBidiStream] (locally opened) or
 * [WebTransportSession.incomingBidiStreams] (peer-opened). For a peer-opened stream the header was
 * already stripped by the connection's router; any bytes the peer packed into the same QUIC packet as
 * the header are returned first by [read] before live stream reads.
 */
class WebTransportStream internal constructor(
    val sessionId: Long,
    private val stream: QuicByteStream,
    // Bytes buffered after the WT header when this stream was demultiplexed (peer-opened streams only);
    // returned by the first read(s) before the underlying QUIC stream is read directly. Null when none.
    private var pending: ReadBuffer?,
) : ByteStream,
    HalfCloseable,
    Resettable {
    /** The underlying QUIC stream id (RFC 9000 §2.1). */
    val streamId: Long get() = stream.streamId.id

    override val isOpen: Boolean get() = stream.isOpen

    /**
     * WebTransport streams are persistent and often idle for long stretches, so the read side delegates
     * liveness to the transport's QUIC idle-timeout rather than imposing a per-read deadline: the no-arg
     * [read] consults [ReadPolicy.UntilClosed]. Pass an explicit bound to [read] when a caller wants one.
     */
    override val readPolicy: ReadPolicy get() = ReadPolicy.UntilClosed

    /** Writes are bounded by an application deadline (the connection's idle-timeout remains the liveness authority). */
    override val writePolicy: WritePolicy get() = WritePolicy.Bounded(15.seconds)

    /**
     * Read the next chunk of stream data; [ReadResult.End] at the peer's FIN, [ReadResult.Reset] on reset.
     *
     * The [deadline] is an *application* deadline, not a liveness timer: the connection's QUIC idle-timeout +
     * keepalive are the liveness authority, and a deadline here neither closes the stream nor drops the
     * connection — it just unblocks the caller, who may read again. The no-arg [read] uses [readPolicy]
     * ([ReadPolicy.UntilClosed]); pass [Duration.INFINITE] (or your own bound) explicitly otherwise.
     */
    override suspend fun read(deadline: Duration): ReadResult = readWithPending(stream, { pending }, { pending = it }, deadline)

    /**
     * Write [buffer]'s remaining bytes to the stream (zero-copy; the caller retains ownership). Throws
     * [WebTransportStreamException] if the peer has aborted the stream (its WebTransport code decoded).
     */
    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.write(buffer, deadline)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Gather-write [buffers] in one operation (zero-copy; the caller retains ownership). */
    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.writeGathered(buffers, deadline)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Half-close the send side (FIN) while keeping the read side open for the peer's data. */
    override suspend fun shutdownSend() = stream.shutdownSend()

    /**
     * Abort the stream in both directions with the WebTransport application [errorCode] (RESET_STREAM +
     * STOP_SENDING). The code is mapped into the HTTP/3 error-code space (draft §4.3) on the wire.
     */
    override suspend fun reset(errorCode: Long) = stream.reset(WebTransportWire.toHttp3ErrorCode(errorCode))

    /** Gracefully close the stream (FIN both directions) and release any buffered prefix. */
    override suspend fun close() {
        pending?.freeIfNeeded()
        pending = null
        stream.close()
    }
}

/**
 * The send half of a unidirectional WebTransport stream (draft-ietf-webtrans-http3 §4.1) we opened —
 * a QUIC unidirectional stream prefixed with the `0x54` type + Session ID. Obtained from
 * [WebTransportSession.openUniStream].
 *
 * Typed as a [ByteSink] + [Resettable]: the honest shape for a send-only stream (no fake [read]). The
 * no-arg [write] consults [writePolicy]; [reset] aborts with a WebTransport application error code and
 * [close] finishes the stream cleanly (FIN).
 */
class WebTransportSendStream internal constructor(
    val sessionId: Long,
    private val stream: QuicByteStream,
) : ByteSink,
    Resettable {
    /** The underlying QUIC stream id (RFC 9000 §2.1). */
    val streamId: Long get() = stream.streamId.id

    override val isOpen: Boolean get() = stream.isOpen

    /** Writes are bounded by an application deadline (the connection's idle-timeout remains the liveness authority). */
    override val writePolicy: WritePolicy get() = WritePolicy.Bounded(15.seconds)

    /**
     * Write [buffer]'s remaining bytes (zero-copy; the caller retains ownership). Throws
     * [WebTransportStreamException] if the peer sent STOP_SENDING (its WebTransport code decoded from
     * the HTTP/3 error-code space, draft §4.3). The no-arg [write] uses [writePolicy].
     */
    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.write(buffer, deadline)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Gather-write [buffers] in one operation (zero-copy; the caller retains ownership). */
    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.writeGathered(buffers, deadline)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Abort the stream with the WebTransport application [errorCode] (RESET_STREAM), mapped per draft §4.3. */
    override suspend fun reset(errorCode: Long) = stream.reset(WebTransportWire.toHttp3ErrorCode(errorCode))

    /** Finish the stream cleanly (FIN) — the [ByteSink.close] contract for a send-only stream. */
    override suspend fun close() = stream.close()
}

/**
 * The receive half of a peer-opened unidirectional WebTransport stream
 * (draft-ietf-webtrans-http3 §4.1). Obtained from [WebTransportSession.incomingUniStreams]. The
 * `0x54` type + Session ID header was stripped by the router; [read] returns any bytes buffered
 * alongside the header before reading the underlying QUIC stream directly.
 *
 * Typed as a [ByteSource] + [Resettable]: the honest shape for a receive-only stream (no fake
 * [write]). Like the bidi stream it delegates liveness to the QUIC idle-timeout — the no-arg [read]
 * consults [ReadPolicy.UntilClosed]. [reset] is the cancel: STOP_SENDING with a WebTransport code.
 */
class WebTransportReceiveStream internal constructor(
    val sessionId: Long,
    private val stream: QuicByteStream,
    private var pending: ReadBuffer?,
) : ByteSource,
    Resettable {
    /** The underlying QUIC stream id (RFC 9000 §2.1). */
    val streamId: Long get() = stream.streamId.id

    override val isOpen: Boolean get() = stream.isOpen

    /** Persistent like the bidi stream: liveness is the QUIC idle-timeout, so reads default to [ReadPolicy.UntilClosed]. */
    override val readPolicy: ReadPolicy get() = ReadPolicy.UntilClosed

    /**
     * Read the next chunk; [ReadResult.End] at the peer's FIN, [ReadResult.Reset] on reset. The no-arg
     * [read] uses [readPolicy] ([ReadPolicy.UntilClosed]); pass an explicit bound to override.
     */
    override suspend fun read(deadline: Duration): ReadResult = readWithPending(stream, { pending }, { pending = it }, deadline)

    /**
     * Cancel the stream: ask the peer to stop sending (STOP_SENDING with the WebTransport application
     * [errorCode], mapped into the HTTP/3 error-code space per draft §4.3) and release any buffered
     * prefix. The [Resettable] contract — cancel is the reset of a receive-only stream.
     */
    override suspend fun reset(errorCode: Long) {
        pending?.freeIfNeeded()
        pending = null
        stream.reset(WebTransportWire.toHttp3ErrorCode(errorCode))
    }
}

/** Drain a one-shot buffered prefix before delegating to the live stream — shared by the read streams. */
private suspend fun readWithPending(
    stream: QuicByteStream,
    getPending: () -> ReadBuffer?,
    setPending: (ReadBuffer?) -> Unit,
    timeout: Duration,
): ReadResult {
    val buffered = getPending()
    if (buffered != null) {
        if (buffered.remaining() > 0) {
            setPending(null)
            return ReadResult.Data(buffered)
        }
        buffered.freeIfNeeded()
        setPending(null)
    }
    return stream.read(timeout)
}
