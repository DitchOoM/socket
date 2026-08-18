package com.ditchoom.socket.quic

/**
 * *Which* connection a [QuicCloseException] came from.
 *
 * The exception already carries *why* as its [QuicCloseException.quicError]. This adds the other half —
 * identity, and what the network was doing — because a caught exception is what most callers actually
 * log, and in a process holding several connections a reason alone cannot say which one died. That is
 * precisely why consumer issue #1152 (133.5s ± 0.2s reconnect cycles) stayed unresolved: the logs had
 * reasons and no way to attribute them.
 *
 * ## Why this does not carry the reason as well
 * It would duplicate [QuicCloseException.quicError], and two fields describing one fact can disagree —
 * at which point the discrepancy becomes the bug you are debugging. [QuicCloseContext] is the bundle
 * that pairs reason with identity for the *state* channel, where the reason is the richer
 * [QuicCloseReason]; the exception keeps its own error and borrows only the identity half.
 *
 * ## Value snapshot, never a live reference
 * [Attributed] holds plain values. An exception can outlive the connection that produced it and must
 * not keep a driver or connection reachable. Nothing internal crosses this boundary either — no
 * `PathKey` (documented as opaque bits deliberately not reversible into an address), no native
 * connection handle.
 *
 * Sealed rather than nullable so "thrown from a site with no connection in hand" is a statement rather
 * than an absence.
 */
sealed interface QuicCloseAttribution {
    /**
     * Thrown from a site that had no connection to attribute it to — a test double, or a failure raised
     * before a connection existed. Honest, and distinguishable from "attributed to a connection whose
     * identity happens to be empty".
     */
    data object Unattributed : QuicCloseAttribution {
        override fun toString(): String = "unattributed"
    }

    /** Thrown by a real connection, identified. */
    data class Attributed(
        val identity: QuicConnectionIdentity,
        val network: NetworkAtClose,
    ) : QuicCloseAttribution {
        override fun toString(): String = "session=${identity.session} wire=${identity.wire} $network"
    }
}
