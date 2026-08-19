package com.ditchoom.socket.quic

/**
 * Why a single QUIC stream was aborted by the peer while the connection itself stays healthy
 * (RFC 9000 §19.4-19.5).
 *
 * Every backend resolves the peer's [applicationErrorCode] on a real abort, so it is **non-null**:
 * The quiche driver reports the **direction** ([StopSending] vs [ResetStream], from the
 * `QUICHE_ERR_STREAM_STOPPED` / `QUICHE_ERR_STREAM_RESET` sentinels) and the peer's
 * [applicationErrorCode] via `quiche_conn_stream_send`'s `out_error_code` out-parameter. This is
 * uniform across every platform, because quiche is the engine on every platform: FFM on JDK 21+,
 * JNI on JDK < 21 / Android, cinterop on Linux and Apple K/N. quiche fills the out-param (0 if the
 * peer used 0) only on STREAM_STOPPED / STREAM_RESET.
 *
 * The code is a typed [QuicAppErrorCode] (a 62-bit RFC 9000 §19.4-19.5 varint). Higher layers that
 * speak a narrower space (e.g. WebTransport's 32-bit code) decode its raw value themselves.
 */
sealed interface QuicStreamAbort {
    /** The peer's QUIC application error code (RFC 9000 §19.4-19.5). */
    val applicationErrorCode: QuicAppErrorCode

    /** Peer sent STOP_SENDING (RFC 9000 §19.5): it no longer wants what we're sending. */
    data class StopSending(
        override val applicationErrorCode: QuicAppErrorCode,
    ) : QuicStreamAbort

    /** Peer sent RESET_STREAM (RFC 9000 §19.4): it abruptly ended its send side. */
    data class ResetStream(
        override val applicationErrorCode: QuicAppErrorCode,
    ) : QuicStreamAbort

    /** The backend signalled a stream abort but couldn't resolve the direction. */
    data class Unspecified(
        override val applicationErrorCode: QuicAppErrorCode,
    ) : QuicStreamAbort
}

/**
 * Thrown when a single QUIC stream is aborted by the peer, while the connection itself stays healthy:
 *
 * - **STOP_SENDING** (RFC 9000 §19.5) — the peer no longer wants what we are sending and asked us to
 *   stop. A legitimate, routine event: e.g. an HTTP/3 client cancelling a server PUSH it didn't want
 *   (RFC 9114 §7.2.3).
 * - **RESET_STREAM** (RFC 9000 §19.4) — the peer abruptly terminated the stream it was sending.
 *
 * This is deliberately **not** a [QuicCloseException] (nor a `SocketClosedException`): a stopped/reset
 * stream says nothing about the connection, which keeps carrying every other stream. Conflating the two
 * — mapping a stream-level abort to a connection-close — tears down a perfectly good connection when a
 * peer cancels one stream. Callers should abandon just this stream and continue.
 *
 * [streamId] is the affected stream. [abort] is the typed reason — match on it to distinguish
 * STOP_SENDING from RESET_STREAM and read the peer application error code where available.
 */
class QuicStreamException(
    val streamId: Long,
    val abort: QuicStreamAbort,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
