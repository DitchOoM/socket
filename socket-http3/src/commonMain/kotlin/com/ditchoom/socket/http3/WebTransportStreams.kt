package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.HalfCloseableByteStream
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicStreamException
import com.ditchoom.socket.quic.ResettableByteStream
import kotlin.time.Duration

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
) : HalfCloseableByteStream,
    ResettableByteStream {
    /** The underlying QUIC stream id (RFC 9000 §2.1). */
    val streamId: Long get() = stream.streamId.id

    override val isOpen: Boolean get() = stream.isOpen

    /**
     * Read the next chunk of stream data; [ReadResult.End] at the peer's FIN, [ReadResult.Reset] on reset.
     *
     * As a [ByteStream] override this carries the interface's default [timeout]; the no-arg form is bounded
     * (not unbounded). WebTransport streams are often idle for long stretches, so pass [Duration.INFINITE]
     * (or your own bound) explicitly when you want to wait longer than the [ByteStream] default.
     */
    override suspend fun read(timeout: Duration): ReadResult = readWithPending(stream, { pending }, { pending = it }, timeout)

    /**
     * Write [buffer]'s remaining bytes to the stream (zero-copy; the caller retains ownership). Throws
     * [WebTransportStreamException] if the peer has aborted the stream (its WebTransport code decoded).
     */
    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten =
        try {
            stream.write(buffer, timeout)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Gather-write [buffers] in one operation (zero-copy; the caller retains ownership). */
    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): BytesWritten =
        try {
            stream.writeGathered(buffers, timeout)
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
 * a QUIC unidirectional stream prefixed with the `0x54` type + Session ID. Write-only; obtained from
 * [WebTransportSession.openUniStream].
 */
class WebTransportSendStream internal constructor(
    val sessionId: Long,
    private val stream: QuicByteStream,
    private val writeTimeout: Duration,
) {
    val streamId: Long get() = stream.streamId.id

    /**
     * Write [buffer]'s remaining bytes. Throws [WebTransportStreamException] if the peer sent
     * STOP_SENDING (its WebTransport code decoded from the HTTP/3 error-code space, draft §4.3).
     */
    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = writeTimeout,
    ): BytesWritten =
        try {
            stream.write(buffer, timeout)
        } catch (e: QuicStreamException) {
            throw e.toWebTransport()
        }

    /** Finish the stream cleanly (FIN). */
    suspend fun close() = stream.close()

    /** Abort the stream with the WebTransport application [errorCode] (RESET_STREAM), mapped per draft §4.3. */
    suspend fun reset(errorCode: Long = 0) = stream.reset(WebTransportWire.toHttp3ErrorCode(errorCode))
}

/**
 * The receive half of a peer-opened unidirectional WebTransport stream
 * (draft-ietf-webtrans-http3 §4.1). Read-only; obtained from [WebTransportSession.incomingUniStreams].
 * The `0x54` type + Session ID header was stripped by the router; [read] returns any bytes buffered
 * alongside the header before reading the underlying QUIC stream directly.
 */
class WebTransportReceiveStream internal constructor(
    val sessionId: Long,
    private val stream: QuicByteStream,
    private var pending: ReadBuffer?,
    private val readTimeout: Duration,
) {
    val streamId: Long get() = stream.streamId.id

    suspend fun read(timeout: Duration = readTimeout): ReadResult = readWithPending(stream, { pending }, { pending = it }, timeout)

    /**
     * Ask the peer to stop sending (STOP_SENDING with the WebTransport application [errorCode], mapped
     * into the HTTP/3 error-code space per draft §4.3) and release any buffered prefix.
     */
    suspend fun cancel(errorCode: Long = 0) {
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
