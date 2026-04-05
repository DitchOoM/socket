package com.ditchoom.socket.quic

/**
 * Result of a quiche stream read operation.
 *
 * Replaces the JNI convention of packing bytes + FIN flag into a single Long.
 * Each [QuicheApi] implementation decodes platform-specific formats into this hierarchy,
 * so the driver never deals with raw packed values.
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
}
