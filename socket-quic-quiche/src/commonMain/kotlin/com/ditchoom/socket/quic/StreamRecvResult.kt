package com.ditchoom.socket.quic

/**
 * Result of a quiche stream read operation.
 *
 * Replaces the JNI convention of packing bytes + FIN flag into a single Long.
 * Each [QuicheApi] implementation decodes platform-specific formats into this hierarchy,
 * so the driver never deals with raw packed values. [Reset] and [ConnectionGone] separate the two
 * things the old catch-all [Error] used to lump together: a peer-initiated RESET_STREAM (a real
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
        val applicationErrorCode: Long,
    ) : StreamRecvResult

    /**
     * Driver teardown sentinel: the connection was gone before quiche could answer. Replaces the
     * old magic `Error(-2)`, which shared a bucket with real quiche codes.
     */
    data object ConnectionGone : StreamRecvResult
}
