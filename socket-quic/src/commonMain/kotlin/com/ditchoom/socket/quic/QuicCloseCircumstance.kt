package com.ditchoom.socket.quic

/**
 * What the local endpoint was doing when a connection ended, as a type — the part of a close that
 * [QuicCloseReason] does not say. Read it from [QuicCloseException.circumstance]; the exception's
 * message is only a rendering of it.
 */
sealed interface QuicCloseCircumstance {
    /** The connection ended before the handshake completed. */
    data object HandshakeFailed : QuicCloseCircumstance

    /** Processing the connection threw; the exception is the [QuicCloseException.cause]. */
    data object ProcessingFailed : QuicCloseCircumstance

    /** The connection's loop thread failed; the throwable is the [QuicCloseException.cause]. */
    data object LoopThreadFailed : QuicCloseCircumstance

    /** A caller's thread failed while inside the connection; the throwable is the [QuicCloseException.cause]. */
    data object CallerThreadFailed : QuicCloseCircumstance

    /** The connection was released before the operation ran. */
    data object Released : QuicCloseCircumstance

    /** The connection ended and the operation found it closed. */
    data object Ended : QuicCloseCircumstance

    /**
     * The operation came after the connection's close: a write or an open of a stream is refused at once, and nothing of
     * it is sent. [QuicCloseException.end] is the end that close asked for.
     */
    data object Closing : QuicCloseCircumstance

    /** The thrower described the close in its own message; nothing more is known by type. */
    data object Unstated : QuicCloseCircumstance
}

internal fun QuicCloseCircumstance.summary(): String =
    when (this) {
        QuicCloseCircumstance.HandshakeFailed -> "QUIC handshake failed"
        QuicCloseCircumstance.ProcessingFailed -> "processing the connection failed"
        QuicCloseCircumstance.LoopThreadFailed -> "the connection's loop thread failed"
        QuicCloseCircumstance.CallerThreadFailed -> "a caller's thread failed inside the connection"
        QuicCloseCircumstance.Released -> "connection released"
        QuicCloseCircumstance.Ended -> "connection closed"
        QuicCloseCircumstance.Closing -> "the connection was closing"
        QuicCloseCircumstance.Unstated -> "connection closed"
    }
