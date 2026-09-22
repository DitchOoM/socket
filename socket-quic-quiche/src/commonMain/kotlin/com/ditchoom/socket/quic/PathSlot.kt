package com.ditchoom.socket.quic

/**
 * Where one of the driver's network paths stands in its life, **and the destination connection ID it
 * is holding while it stands there** — one value, because those two facts are only ever true
 * together.
 *
 * ## Why the DCID lives here and not beside it
 * quiche links a spare DCID to a path when that path is created (`create_path_on_client` →
 * `link_dcid_to_path_id`) and unlinks it only when the application calls `quiche_conn_retire_dcid`.
 * Nothing else ever returns it: `on_failed_validation()` sets the path to `Failed` and leaves
 * `active_dcid_seq` exactly where it was, so a path the driver has walked away from keeps its id
 * forever and — because `Path::unused()` demands `active_dcid_seq.is_none()` — is not even evictable
 * from quiche's path table.
 *
 * A sequence number held anywhere but the path's own state can be leaked: a probe's id written into
 * scratch and never read gives the three failure exits (`FailedValidation`, the RFC 9000 §8.2.4
 * abandon timer, and a `quiche_conn_migrate` that refuses a validated path) no value to forget — they
 * *cannot* retire what they have leaked, and one unanswered PATH_CHALLENGE on real cellular then
 * disables migration for the rest of the connection.
 *
 * Keeping the id in the path's own state makes the leak unwritable:
 * [QuicheDriver.PathEntry.transitionTo] retires whatever the previous state held whenever the next
 * state does not carry it forward, so "this path stopped holding this id" and "this id was retired"
 * are the same event, at one site, for every exit.
 *
 * ## The lifecycle
 * ```
 *   Probed ─┬─▶ Probing(seq) ─────────────▶ Validated(seq) ──▶ Active(seq) ──┐
 *           │     │     ▲                        │                          │
 *           │     │     │ probed again           │ switch refused           │ superseded by
 *           │     ▼     │                        ▼                          │ the next migration
 *           │   Unanswered(seq) ──▶ Answered(seq) ──▶ Active(seq)           │
 *           │     │                    │                                    │
 *           │     └── replaced ────────┴───────────────────────────▶  Abandoned ◀┘
 *           └── (Rejected: no path, no id) ───────────────────────────▶  Abandoned
 * ```
 * The connection's original path starts at [Active] on sequence 0 — RFC 9000 §5.1.1's initial
 * destination CID — which is why a first migration's §9.5 retirement names `0`.
 *
 * ## Why an unanswered probe keeps its id
 * A spare comes back only when a `RETIRE_CONNECTION_ID` reaches the peer and it answers with a
 * `NEW_CONNECTION_ID`, and both ride the active path. When that path is dead — the case a handoff exists
 * for — retiring an unanswered probe's id returns nothing, so a retry that binds a fresh socket spends a
 * spare the peer can never replace, and the pool (`min(peer limit, own limit) - 1`, three against a peer
 * at 4) is gone after that many probes. A [Kept] path holds its socket and its id instead, and a retry on
 * the same local address probes it again with that same id, which RFC 9000 §9.5 permits: it forbids
 * reusing an id only when sending from more than one local address.
 */
internal sealed interface PathSlot {
    /**
     * The states in which quiche has a destination CID linked to this path. Leaving one of these for
     * anything that does not carry [dcidSeq] forward owes the connection a
     * [QuicheApi.connRetireDcid] — see [QuicheDriver.PathEntry.transitionTo], which is the only place
     * that transition is expressible.
     */
    sealed interface Linked : PathSlot {
        val dcidSeq: Long
    }

    /** A PATH_CHALLENGE is in flight; the peer has neither answered nor been given up on. */
    class Probing(
        override val dcidSeq: Long,
    ) : Linked

    /**
     * A probe path no migration is waiting on, kept — socket, reader and id — for the next migration on
     * the same local address to use instead of spending a spare. At most one path is in one of these
     * states: a migration settles the kept path (probes it again, switches to it, or replaces it)
     * before it probes anything else.
     */
    sealed interface Kept : Linked {
        /** Where the path's socket is bound — what a migration that lands here reports. */
        val localEndpoint: QuicLocalEndpoint
    }

    /** The RFC 9000 §8.2.4 abandon budget, or quiche's own, ran out with the challenge unanswered. */
    class Unanswered(
        override val dcidSeq: Long,
        override val localEndpoint: QuicLocalEndpoint,
    ) : Kept

    /**
     * quiche validated the path after the migration had stopped waiting — it goes on probing a path
     * whose id is still linked. quiche reports a path's validation once, so probing it again would never
     * be answered; the next migration switches to it instead.
     */
    class Answered(
        override val dcidSeq: Long,
        override val localEndpoint: QuicLocalEndpoint,
    ) : Kept

    /**
     * The peer answered the challenge (`PathEvent::Validated`), but the connection has not switched
     * to this path yet. A real state, not a formality: `quiche_conn_migrate` can still refuse a
     * validated path (`SwitchRejected`), and that exit leaks exactly like the others unless it is
     * expressible as a transition out of *this*.
     */
    class Validated(
        override val dcidSeq: Long,
    ) : Linked

    /** The path the connection is living on. Exactly one [PathEntry] is in this state at a time. */
    class Active(
        override val dcidSeq: Long,
    ) : Linked

    /**
     * Terminal: the path holds no destination CID. Either it never had one (a probe quiche rejected
     * outright, which allocates nothing) or the transition into this state retired it.
     */
    data object Abandoned : PathSlot
}
