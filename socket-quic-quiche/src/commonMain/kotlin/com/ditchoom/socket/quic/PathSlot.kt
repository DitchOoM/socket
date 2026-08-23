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
 * Before this type the sequence number lived in a separate `ActivePath(entry, dcidSeq)` holder that
 * only the **successful** migration path ever populated. A probe's id was written into native
 * scratch and never read, so the three failure exits (`FailedValidation`, the RFC 9000 §8.2.4 abandon
 * timer, and a `quiche_conn_migrate` that refuses a validated path) had no value to forget — they
 * *could not* retire what they had leaked. That is #447, and it is the reason one unanswered
 * PATH_CHALLENGE on real cellular disables migration for the rest of the connection.
 *
 * Keeping the id in the path's own state makes the leak unwritable rather than merely fixed:
 * [QuicheDriver.PathEntry.transitionTo] retires whatever the previous state held whenever the next
 * state does not carry it forward, so "this path stopped holding this id" and "this id was retired"
 * are the same event, at one site, for every exit.
 *
 * ## The lifecycle
 * ```
 *   Probed ─┬─▶ Probing(seq) ──▶ Validated(seq) ──▶ Active(seq) ──┐
 *           │        │                 │                          │
 *           │        └── abandoned ────┴──── switch refused ──┐    │ superseded by
 *           │                                                 │    │ the next migration
 *           └── (Rejected: no path, no id) ─────────────────▶  Abandoned ◀───────┘
 * ```
 * The connection's original path starts at [Active] on sequence 0 — RFC 9000 §5.1.1's initial
 * destination CID — which is why a first migration's §9.5 retirement names `0`.
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
