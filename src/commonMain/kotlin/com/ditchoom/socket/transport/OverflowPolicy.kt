package com.ditchoom.socket.transport

/**
 * What a [CodecConnection] does when its outbound queue is full (#382).
 *
 * Once `send` is a hand-off to the connection's own writer, a slow peer stops blocking its callers and
 * becomes queue depth instead — which means the queue is bounded, which means there is a decision to
 * make when it fills. The library guarantees the invariant (a frame reaches the wire whole or not at
 * all, and concurrent sends cannot interleave); **the caller states the policy**, because the right
 * answer depends on whether the traffic is recoverable at a higher layer.
 *
 * The primary [CodecConnection] constructor deliberately has no default, so a new consumer has to
 * answer this. The deprecated pre-#382 overloads and the scoped `withMux` entry points do default it,
 * to [Suspend] — the only arm that never discards a message — so that existing callers receive the fix
 * without a migration. That is a compatibility affordance, not the recommendation: a caller that has
 * not chosen has not thought about what a lagging peer should cost it.
 *
 * A lagging peer is the case worth thinking about concretely. Evicting it with an untyped close reads
 * to that peer as a normal close and produces a reconnect loop, so a consumer whose state re-snapshots
 * on reconnect usually wants to degrade rather than flap — [DropOldest] with a counter. A consumer
 * with an at-least-once layer above it usually wants [Suspend] and back-pressure. [Fail] is for the
 * caller that would rather find out at the send site.
 */
sealed interface OverflowPolicy<in T> {
    /**
     * Suspend the caller until the queue has room.
     *
     * Back-pressure without message loss. Note what the caller waits for: space in **this
     * connection's queue**, never the peer's socket — so a stalled peer delays this sender only once
     * the whole queue has filled, and never blocks a *different* connection's sender.
     */
    data object Suspend : OverflowPolicy<Any?>

    /**
     * Drop the **oldest** queued message to make room, handing it to [onOverflow].
     *
     * The right shape for state that supersedes itself — telemetry, presence, a position update —
     * where the newest message is the one worth keeping. `send` never suspends and never fails under
     * this policy.
     */
    class DropOldest<T>(
        val onOverflow: (T) -> Unit,
    ) : OverflowPolicy<T>

    /**
     * Reject the **newest** message, handing it to [onOverflow].
     *
     * The right shape when the queue is a work backlog whose order matters and the tail is what
     * should be shed. `send` never suspends and never fails under this policy.
     */
    class DropNewest<T>(
        val onOverflow: (T) -> Unit,
    ) : OverflowPolicy<T>

    /**
     * Throw [com.ditchoom.socket.OutboundQueueFullException] at the send site.
     *
     * For a caller that treats a full queue as a genuine error rather than something to shed, and
     * wants it surfaced where it can still act on the message it was holding.
     */
    data object Fail : OverflowPolicy<Any?>
}
