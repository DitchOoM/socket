package com.ditchoom.socket.quic

/**
 * Whether bytes received on a stream can be a replay (RFC 9001 §9.2, RFC 8446 §8).
 *
 * A server that accepts 0-RTT receives the client's first requests before the handshake proves the
 * client is live: anyone who captured that first flight can send it again, and the server cannot tell the
 * copy from the original until the handshake is confirmed. A stream the client opened in 0-RTT is
 * [Early]; its reads wait for confirmation unless the application calls
 * [QuicByteStream.markReplaySafe] on it.
 */
sealed interface QuicStreamOrigin {
    /**
     * Opened locally, or by the peer on a handshake that was already confirmed: nothing received on it can
     * be a replay.
     */
    data object Confirmed : QuicStreamOrigin

    /**
     * Opened by the peer in 0-RTT, before the handshake was confirmed. [awaitConfirmation] returns once it
     * is, and throws the connection's [QuicCloseException] if the handshake fails instead.
     *
     * Built by an engine for each stream the peer opened early; [confirmation] is that connection's wait.
     */
    class Early(
        private val confirmation: suspend () -> Unit,
    ) : QuicStreamOrigin {
        /** Suspend until the handshake is confirmed; throws [QuicCloseException] if it fails. */
        suspend fun awaitConfirmation(): Unit = confirmation()

        override fun toString(): String = "Early"
    }
}
