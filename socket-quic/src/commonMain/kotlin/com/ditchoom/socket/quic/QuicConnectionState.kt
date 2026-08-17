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

    /**
     * Terminal state. [reason] says why, as an exhaustive [QuicCloseReason] — including
     * [QuicCloseReason.Unspecified] for a teardown the protocol never explained, which the previous
     * nullable [error] silently reported as a clean shutdown.
     */
    data class Closed(
        val reason: QuicCloseReason,
    ) : QuicConnectionState {
        /**
         * Compatibility constructor for the old `Closed(error)` shape. `null` maps to
         * [QuicCloseReason.Graceful] because that is what `null` used to mean at construction sites;
         * a non-null error maps to [QuicCloseReason.ByLocal], since a bare [QuicError] carries no
         * indication of which side closed.
         */
        @Deprecated(
            "Construct with a QuicCloseReason: null could not distinguish a graceful close from an " +
                "unexplained teardown, and a bare QuicError cannot say which side closed.",
            ReplaceWith("QuicConnectionState.Closed(QuicCloseReason.ByLocal(error))"),
            DeprecationLevel.WARNING,
        )
        constructor(error: QuicError?) : this(
            if (error == null) QuicCloseReason.Graceful else QuicCloseReason.ByLocal(error),
        )

        /**
         * The close error, or `null`.
         *
         * **Deliberately bug-compatible.** [QuicCloseReason.Unspecified] reports `null` here exactly as
         * an unexplained teardown always did, so existing callers keep their current behaviour rather
         * than silently acquiring new one through a deprecated accessor. That also means this property
         * cannot tell [QuicCloseReason.Graceful] from [QuicCloseReason.Unspecified] — recovering that
         * distinction is the whole point of migrating to [reason].
         */
        @Deprecated(
            "Match on `reason` instead: this cannot distinguish a graceful close (Graceful) from an " +
                "unexplained teardown (Unspecified), nor a peer close from a local one.",
            ReplaceWith("reason"),
            DeprecationLevel.WARNING,
        )
        val error: QuicError? get() =
            when (val r = reason) {
                is QuicCloseReason.ByPeer -> r.error
                is QuicCloseReason.ByLocal -> r.error
                QuicCloseReason.Graceful, QuicCloseReason.Unspecified -> null
            }

        /**
         * Whether this was a clean shutdown, by the **old** definition (`error == null || NoError`).
         *
         * Bug-compatible for the same reason as [error]: an [QuicCloseReason.Unspecified] teardown still
         * answers `true` here, which is precisely the conflation this release exists to fix. Match on
         * [reason] to get the truthful answer — `reason is QuicCloseReason.Graceful`.
         */
        @Deprecated(
            "Use `reason is QuicCloseReason.Graceful`: this reports true for an Unspecified teardown, " +
                "which was never actually a clean shutdown.",
            ReplaceWith("reason is QuicCloseReason.Graceful"),
            DeprecationLevel.WARNING,
        )
        val isCleanShutdown: Boolean get() =
            when (val r = reason) {
                QuicCloseReason.Graceful, QuicCloseReason.Unspecified -> true
                is QuicCloseReason.ByPeer -> r.error is QuicError.NoError
                is QuicCloseReason.ByLocal -> r.error is QuicError.NoError
            }
    }
}
