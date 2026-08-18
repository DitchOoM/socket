package com.ditchoom.socket.quic

/**
 * The [QuicError] this close carries, or `null` when it carries none.
 *
 * A deliberately **lossy projection**, kept `internal` and confined to the few places that genuinely
 * want "the error, if there was one" — trace recording and the datagram adapter's fallback. It is not a
 * reintroduction of the old nullable: [QuicConnectionState.Closed] still stores an exhaustive
 * [QuicCloseReason], so the ambiguity this collapses ([QuicCloseReason.Graceful] vs
 * [QuicCloseReason.Unspecified]) is discarded per use site rather than baked into the state.
 *
 * Anything making a *decision* about how the connection ended should match on the reason instead.
 */
internal val QuicCloseReason.errorOrNull: QuicError?
    get() =
        when (this) {
            is QuicCloseReason.ByPeer -> error
            is QuicCloseReason.ByLocal -> error
            QuicCloseReason.Graceful, QuicCloseReason.Unspecified -> null
        }
