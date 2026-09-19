package com.ditchoom.socket.quic

/**
 * Result of a quiche stream read operation.
 *
 * Each [QuicheApi] implementation decodes platform-specific formats (JNI packs bytes + FIN flag into
 * a single Long) into this hierarchy, so the driver never deals with raw packed values. [Reset] and
 * [ConnectionGone] are distinct from the catch-all [Error]: a peer-initiated RESET_STREAM (a real
 * quiche code, -16, that carries an application error code) versus the driver's own "the connection
 * was already gone" sentinel (not a quiche code at all).
 */
sealed interface StreamRecvResult {
    /** Data was received. [bytesRead] bytes are in the caller's buffer. [fin] indicates stream end. */
    class Data(
        val bytesRead: Int,
        val fin: Boolean,
    ) : StreamRecvResult

    /** No data available yet (QUICHE_ERR_DONE). Caller should wait for a data signal and retry. */
    data object Done : StreamRecvResult

    /** Stream error (reset, etc.). [code] is the quiche error code. */
    class Error(
        val code: Int,
    ) : StreamRecvResult

    /**
     * The peer aborted the stream with RESET_STREAM (quiche STREAM_RESET, -16).
     * [applicationErrorCode] is the peer's code from `out_error_code`.
     */
    class Reset(
        val applicationErrorCode: QuicAppErrorCode,
    ) : StreamRecvResult

    /**
     * Driver teardown sentinel: the connection was gone before quiche could answer. Replaces the
     * old magic `Error(-2)`, which shared a bucket with real quiche codes.
     */
    data object ConnectionGone : StreamRecvResult
}
