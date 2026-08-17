package com.ditchoom.socket.quic

import com.ditchoom.socket.SocketClosedException

/**
 * Thrown when a QUIC stream or connection operation fails because the connection (or stream) is gone.
 *
 * Extends [SocketClosedException] so it is caught uniformly alongside TCP/TLS connection-lost errors
 * (`catch (e: SocketClosedException)`, or `catch (e: IOException)` on JVM/Android), while additionally
 * carrying the structured [QuicError] reason — so callers recover the protocol-level cause without
 * parsing a message string. This keeps the thrown channel as type-faithful as the state channel
 * ([QuicConnectionState.Closed.reason]).
 *
 * The two channels still differ in one way: this carries a bare [QuicError], while the state carries a
 * [QuicCloseReason] that also says whether the peer or the local endpoint closed, and distinguishes a
 * graceful shutdown from one the protocol never explained. Read the state when that distinction
 * matters.
 *
 * They no longer differ in *attribution*. [attribution] names which connection this came from, so a
 * caught-and-logged exception is self-sufficient in a process holding several connections — the gap
 * that left reconnect-cycle incidents unattributable when only a reason was recorded.
 */
class QuicCloseException(
    val quicError: QuicError,
    message: String,
    cause: Throwable? = null,
    /**
     * Which connection this came from, as a value snapshot — see [QuicCloseAttribution].
     *
     * Defaults to [QuicCloseAttribution.Unattributed] so a throw site with no connection in hand stays
     * honest rather than inventing one; every driver-backed site supplies it.
     */
    val attribution: QuicCloseAttribution = QuicCloseAttribution.Unattributed,
) : SocketClosedException(withReason(message, quicError, attribution), cause) {
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
            attribution: QuicCloseAttribution,
        ): String {
            val reason = if (error is QuicError.NoError) message else "$message [${error.describe()}]"
            // Only the session id goes in the string: it is short, stable, and the one identifier that
            // makes a log line attributable. The rest stays typed on [attribution] rather than being
            // stuffed into a message nobody can parse back.
            return when (attribution) {
                QuicCloseAttribution.Unattributed -> reason
                is QuicCloseAttribution.Attributed -> "$reason (session=${attribution.identity.session})"
            }
        }
    }
}
