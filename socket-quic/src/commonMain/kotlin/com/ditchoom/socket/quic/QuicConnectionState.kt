package com.ditchoom.socket.quic

/**
 * QUIC connection lifecycle states. Sealed hierarchy ensures exhaustive handling
 * and prevents impossible state combinations.
 *
 * Valid transitions:
 *   Idle → Handshaking → Established → Draining → Closed
 *                      ↘ Closed (on handshake failure)
 *            Established → Closed (on immediate close/error)
 */
sealed interface QuicConnectionState {
    /** Connection created but not yet started. */
    data object Idle : QuicConnectionState

    /** TLS 1.3 handshake in progress. */
    data object Handshaking : QuicConnectionState

    /** Handshake complete, streams can be opened. */
    data class Established(
        val negotiatedAlpn: String,
    ) : QuicConnectionState

    /** Graceful close in progress (CONNECTION_CLOSE sent, waiting for acknowledgment). */
    data object Draining : QuicConnectionState

    /** Terminal state. [error] is null for clean shutdown, non-null for error. */
    data class Closed(
        val error: QuicError?,
    ) : QuicConnectionState {
        val isCleanShutdown: Boolean get() = error == null || error is QuicError.NoError
    }
}
