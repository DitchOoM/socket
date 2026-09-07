package com.ditchoom.socket.quic

/**
 * Why quiche refused to probe a proposed path — the driver's own reading of the error code it gets
 * back, used to decide whether the refusal is worth another bind.
 *
 * Enumerated rather than passed around as a number, because the two rejections that actually happen
 * call for opposite responses and a number cannot say which: one is retryable from a *different local
 * port* and the other is not retryable at all. #583 is what that costs — a stale-path collision was
 * reported by an assertion narrating #447's exhausted connection-id pool, which is a different defect
 * with a different fix, and the retry that could have absorbed it only recognised the other kind of
 * failure.
 *
 * ⚠️ **Internal on purpose, for now.** [MigrationResult.Unmoved.Failed.ProbeRejected] still publishes
 * the raw code, because replacing it is source- and binary-incompatible and this library is not taking
 * a major yet. The driver therefore acts on the distinction while callers cannot — see that type's
 * KDoc for what a caller is missing and what the next major owes them.
 */
internal sealed interface ProbeRejection {
    /**
     * The 4-tuple already exists as a path on this connection, and that path has no destination
     * connection id bound to it.
     *
     * Reached when a previous probe on the same local port was abandoned: the socket is closed and the
     * kernel is free to hand the same ephemeral port back, but the stack still holds the old path —
     * with its connection id retired. **Retryable, but only from a different local port**: probing the
     * same 4-tuple again fails identically forever, which is why a backoff cannot absorb it.
     */
    data object StalePathCollision : ProbeRejection

    /**
     * The stack had no spare connection id to bind to the new path.
     *
     * Distinct from `MigrationResult.Unmoved.Failed.NoSpareConnectionId`, which this library decides
     * for itself before probing: this one is the stack reaching the same conclusion during the probe,
     * and the race between the two is real — a pool that was non-empty when checked can be empty a
     * moment later. Retryable once a `RETIRE_CONNECTION_ID` has been answered.
     */
    data object OutOfConnectionIds : ProbeRejection

    /**
     * The stack refused for a reason this library does not model, carrying its own [code] verbatim.
     *
     * Deliberately not a silent fallback: a caller must be able to see that the refusal was
     * unrecognised rather than have it flattened into one of the cases above, which is how a new
     * failure mode ends up being treated as a stale path and retried forever.
     */
    data class Unrecognised(
        val code: Int,
    ) : ProbeRejection
}
