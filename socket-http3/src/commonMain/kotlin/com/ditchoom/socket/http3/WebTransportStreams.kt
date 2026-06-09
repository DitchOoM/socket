package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicByteStream
import kotlin.time.Duration

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
    private val readTimeout: Duration,
    private val writeTimeout: Duration,
) {
    /** The underlying QUIC stream id (RFC 9000 §2.1). */
    val streamId: Long get() = stream.streamId.id

    /** Read the next chunk of stream data; [ReadResult.End] at the peer's FIN, [ReadResult.Reset] on reset. */
    suspend fun read(timeout: Duration = readTimeout): ReadResult = readWithPending(stream, { pending }, { pending = it }, timeout)

    /** Write [buffer]'s remaining bytes to the stream (zero-copy; the caller retains ownership). */
    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = writeTimeout,
    ): BytesWritten = stream.write(buffer, timeout)

    /** Half-close the send side (FIN) while keeping the read side open for the peer's data. */
    suspend fun shutdownSend() = stream.shutdownSend()

    /** Abort the stream in both directions with [errorCode] (RESET_STREAM + STOP_SENDING). */
    suspend fun reset(errorCode: Long = 0) = stream.reset(errorCode)

    /** Gracefully close the stream (FIN both directions) and release any buffered prefix. */
    suspend fun close() {
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

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = writeTimeout,
    ): BytesWritten = stream.write(buffer, timeout)

    /** Finish the stream cleanly (FIN). */
    suspend fun close() = stream.close()

    /** Abort the stream with [errorCode] (RESET_STREAM). */
    suspend fun reset(errorCode: Long = 0) = stream.reset(errorCode)
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

    /** Ask the peer to stop sending (STOP_SENDING with [errorCode]) and release any buffered prefix. */
    suspend fun cancel(errorCode: Long = 0) {
        pending?.freeIfNeeded()
        pending = null
        stream.reset(errorCode)
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
