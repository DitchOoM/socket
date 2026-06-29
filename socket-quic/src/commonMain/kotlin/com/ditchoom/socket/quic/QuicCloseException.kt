package com.ditchoom.socket.quic

import com.ditchoom.socket.SocketClosedException

/**
 * Thrown when a QUIC stream or connection operation fails because the connection (or stream) is gone.
 *
 * Extends [SocketClosedException] so it is caught uniformly alongside TCP/TLS connection-lost errors
 * (`catch (e: SocketClosedException)`, or `catch (e: IOException)` on JVM/Android), while additionally
 * carrying the structured [QuicError] reason — so callers recover the protocol-level cause without
 * parsing a message string. This keeps the thrown channel as type-faithful as the state channel
 * ([QuicConnectionState.Closed.error]).
 */
class QuicCloseException(
    val quicError: QuicError,
    message: String,
    cause: Throwable? = null,
) : SocketClosedException(withReason(message, quicError), cause) {
    private companion object {
        /**
         * Append the typed [QuicError] to the human [message] when it carries a real reason, so every
         * throw site gets a helpful message ("…connection closed [ProtocolViolation (0xa)]") without
         * duplicating formatting. A clean shutdown ([QuicError.NoError]) adds nothing — the bare message
         * already reads correctly and "[NoError]" would be noise.
         */
        fun withReason(
            message: String,
            error: QuicError,
        ): String = if (error is QuicError.NoError) message else "$message [${error.describe()}]"
    }
}
