package com.ditchoom.socket.quic

/** Whether a server connection accepted the client's 0-RTT before its handshake completed. */
internal sealed interface EarlyDataAcceptance {
    /** Not accepted: none offered, declined, not decided yet, or a client connection. */
    data object None : EarlyDataAcceptance

    /** Accepted while the handshake was in progress, which had already settled [negotiatedAlpn] and [resumption]. */
    data class Accepted(
        val negotiatedAlpn: String,
        val resumption: QuicResumptionOutcome,
    ) : EarlyDataAcceptance
}

/** How a connection's handshake ended. */
internal sealed interface HandshakeSettled {
    data object Confirmed : HandshakeSettled

    /** The connection closed, or its driver was torn down, before the handshake completed. */
    data object Failed : HandshakeSettled
}

/** When a server connection's handler starts — see [SharedQuicheServer.connections]. */
internal sealed interface HandlerStart {
    /** Neither 0-RTT accepted nor the handshake settled yet. */
    data object NotYet : HandlerStart

    /** Whether the handler runs at all, decided. */
    sealed interface Decided : HandlerStart

    /** The client's 0-RTT was accepted, or the handshake completed. */
    data object Now : Decided

    /** The connection closed before either: there is nothing to serve. */
    data object Never : Decided

    companion object {
        fun of(
            state: QuicConnectionState,
            earlyData: EarlyDataAcceptance,
        ): HandlerStart =
            when (state) {
                is QuicConnectionState.Established -> Now
                QuicConnectionState.Idle, QuicConnectionState.Handshaking ->
                    when (earlyData) {
                        is EarlyDataAcceptance.Accepted -> Now
                        EarlyDataAcceptance.None -> NotYet
                    }
                QuicConnectionState.Draining, is QuicConnectionState.Closed -> Never
            }
    }
}
