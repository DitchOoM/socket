package com.ditchoom.socket.quic

/**
 * Result of a quiche stream write operation (`quiche_conn_stream_send`).
 *
 * [result] keeps the raw quiche return convention the driver switches on: `>= 0` bytes accepted,
 * [QuicheDriver.QUICHE_ERR_DONE] (window full / backpressure), or a negative quiche error code —
 * notably [QuicheDriver.QUICHE_ERR_STREAM_STOPPED] / [QuicheDriver.QUICHE_ERR_STREAM_RESET] when the
 * peer aborted the stream.
 *
 * [errorCode] is the peer's QUIC application error code from quiche's `out_error_code` out-parameter,
 * meaningful only when [result] is `STREAM_STOPPED`/`STREAM_RESET`. It is `null` when the backend does
 * not surface it (the JNI binding) so callers can tell "code unknown" from a real code of `0` — see
 * [QuicStreamAbort.applicationErrorCode].
 */
class StreamSendResult(
    val result: Int,
    val errorCode: Long? = null,
)
