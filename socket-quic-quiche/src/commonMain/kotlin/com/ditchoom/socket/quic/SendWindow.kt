package com.ditchoom.socket.quic

/**
 * How many bytes of the driver's send buffer quiche may fill on one `quiche_conn_send` — which is the
 * same thing as how far it *expands* a datagram it is required to expand.
 *
 * quiche has no "path validation datagram size" setting. It derives the expansion target from the
 * caller's output buffer: `send()` takes `left = min(out.len(), max_send_udp_payload_size())`, and
 * `send_single()` then fills the remainder with PADDING for exactly one reason —
 * `has_initial || !path.validated()`. A validated path is padded not at all, an unvalidated one is
 * padded to the brim. So the buffer length the driver passes *is* the separation between "how big may
 * ordinary data be" and "how far must a PATH_CHALLENGE/PATH_RESPONSE be expanded", and this type is the
 * driver saying which of the two it is asking for rather than a bare `Int` that reads as a capacity.
 *
 * #637: sizing both at [QuicheDriver.MAX_DATAGRAM_SIZE] made every link whose effective MTU falls
 * between the RFC floor and that number permanently unvalidatable, while carrying the connection
 * perfectly. RFC 9000 §8.2.1/§8.2.2 ask for **at least** 1200 bytes — a floor, not a target.
 */
internal sealed interface SendWindow {
    val bytes: Int

    /** Every path quiche may send on is validated: nothing is expanded, so ordinary data gets the full size. */
    data object Full : SendWindow {
        override val bytes: Int get() = QuicheDriver.MAX_DATAGRAM_SIZE
    }

    /**
     * A path is awaiting validation, so the next datagram may be one quiche expands: the RFC floor and
     * not a byte more.
     *
     * Ordinary data rides the same window while this holds, because a single `quiche_conn_send` does
     * not say in advance which path it is about to write for. That costs at most
     * [QuicheDriver.MAX_DATAGRAM_SIZE] − [PATH_VALIDATION_EXPANSION] bytes per datagram, only for the
     * length of a validation attempt, and it buys back the handoffs a constrained link used to make
     * impossible.
     */
    data object ValidationFloor : SendWindow {
        override val bytes: Int get() = PATH_VALIDATION_EXPANSION
    }
}

/**
 * RFC 9000 §8.2.1: "An endpoint MUST expand datagrams that contain a PATH_CHALLENGE frame to at least
 * the smallest allowed maximum datagram size of 1200 bytes"; §8.2.2 says the same of PATH_RESPONSE.
 * The same 1200 as §14's minimum maximum datagram size, and the same value [QuicOptions] already
 * refuses to let `maxUdpPayloadSize` fall below.
 */
internal const val PATH_VALIDATION_EXPANSION = 1200

/**
 * Where one of quiche's paths stands, read off `quiche_path_stats.validation_state` — the three answers
 * that decide a [SendWindow], not a re-spelling of quiche's five-variant `PathState`.
 *
 * The split is by consequence: quiche expands every datagram it sends on a path it has not validated,
 * it expands nothing on one it has, and it schedules nothing at all on one whose validation failed.
 */
internal sealed interface PathValidation {
    /** `Validated`. quiche expands nothing here; the full window is free. */
    data object Complete : PathValidation

    /** `Unknown`, `Validating` or `ValidatingMTU`. quiche expands whatever it sends here to fill the window. */
    data object Expanding : PathValidation

    /** `Failed`. quiche will not schedule on this path again unless it is probed afresh. */
    data object Abandoned : PathValidation

    companion object {
        /** quiche's `PathState::to_c`. An unrecognised code reads as [Expanding] — the safe half. */
        fun from(validationState: Long): PathValidation =
            when (validationState) {
                QUICHE_PATH_STATE_VALIDATED -> Complete
                QUICHE_PATH_STATE_FAILED -> Abandoned
                else -> Expanding
            }
    }
}

/** `PathState::Failed`, as `quiche_path_stats.validation_state` reports it. */
private const val QUICHE_PATH_STATE_FAILED = -1L

/** `PathState::Validated`, as `quiche_path_stats.validation_state` reports it. */
private const val QUICHE_PATH_STATE_VALIDATED = 3L
