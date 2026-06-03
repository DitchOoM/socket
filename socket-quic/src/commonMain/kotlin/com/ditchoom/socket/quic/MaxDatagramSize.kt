package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline

/**
 * Result of [QuicScope.maxDatagramSize]. Sealed instead of a nullable `Int?` so the
 * "datagrams unavailable" case is explicit and a `when` forces callers to handle it —
 * consistent with [DatagramReceiveResult] / [QuicError] / [MigrationResult].
 */
sealed interface MaxDatagramSize {
    /** A datagram of up to [bytes] can be sent right now. Tracks the current path MTU. */
    @JvmInline
    value class Bytes(
        val bytes: Int,
    ) : MaxDatagramSize {
        init {
            require(bytes > 0) { "max datagram size must be positive" }
        }
    }

    /**
     * Datagrams cannot be sent: either [QuicOptions.datagrams] was not set locally, or the peer
     * never advertised `max_datagram_frame_size`.
     */
    data object Unavailable : MaxDatagramSize
}
