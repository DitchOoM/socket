package com.ditchoom.socket.quic

/**
 * Whether the path the connection is **living on** is still answering — the data-plane second opinion
 * [wireAutoMigration] holds beside [com.ditchoom.socket.NetworkMonitor]'s control-plane one (#574).
 *
 * ## Why a connection needs a second opinion at all
 * Auto-migration's only trigger used to be the platform's reachability signal, and that signal can be
 * wrong for a long time. Measured on the 71h Android walk (2026-09-06, SM-F956U1): on each of the
 * three real Wi-Fi→cellular handoffs the heartbeat immediately before the outage reported
 * `net=wifi validated=true`, and `ConnectivityManager` went on saying so for **11.5s / 11.5s / 11.9s**
 * — 17 consecutive failed echoes each time — before the reactor was handed anything. The migration
 * that followed took 338ms–1954ms. So the user-visible outage was ~12.5s, of which QUIC was under
 * two. Apple's `NWPathMonitor` reports the same handoff in ~1s, which is the whole of a 10× platform
 * asymmetry the connection can simply decline to inherit: the driver already knows the path has gone
 * quiet, because it is the thing holding the unanswered packets.
 *
 * ## Two states, and why neither is a `Boolean`
 * "Is the path answering" is a question about *evidence*, and the two answers are reached by
 * different reasoning — one is the absence of a run of failures, the other is the presence of one.
 * A `Boolean` would carry the fact and lose the vocabulary; a third state (a `Degrading` rung from
 * loss/RTT, which is Chrome's other migration signal) is a plausible addition here and would be a
 * silent widening of a boolean. This is the same reason [QuicPathState] is a sealed family rather
 * than a phase enum with two meaningless endpoints.
 *
 * ## What the driver publishes, and when
 * [QuicheDriver] resamples the **active** path's `quiche_path_stats` on its own timer wakes — the
 * wakes it already has, since a PTO firing *is* one — and moves between these two values. There is
 * exactly one transition per episode of silence in each direction, which is what keeps a trigger
 * queued behind an in-flight migration from outliving the evidence that produced it.
 */
internal sealed interface PathLiveness {
    /**
     * The active path has received something since the last time its PTO fired — or has nothing
     * outstanding for a PTO to fire about.
     *
     * The second half is deliberate and is the honest reading of an idle connection: a path carrying
     * no unacknowledged data is indistinguishable from a working one, so silence over it is not
     * evidence of anything. RFC 9002 §6.2.1 arms the PTO only while there are ack-eliciting packets in
     * flight, so this state is what an idle connection sits in until it has something to say — or
     * until [QuicOptions.keepAliveInterval] says it for it.
     */
    data object Answering : PathLiveness

    /**
     * The active path has run [SILENT_PATH_PTO_THRESHOLD] consecutive PTOs without a single packet
     * arriving on it. Not "some packets went missing": every retransmission the path's own loss
     * recovery scheduled in that window went unanswered too.
     *
     * Deliberately carries no counter. It is a single value per episode, so the [kotlinx.coroutines.flow.StateFlow]
     * publishing it emits once when the path goes dark and once when it comes back, and a reactor that
     * was busy migrating when the transition happened cannot later drain a backlog of stale evidence.
     * The count that produced it is a property of the threshold, not of the observation.
     */
    data object Silent : PathLiveness
}

/**
 * How many consecutive PTOs on the active path, with **no packet received on it in between**, mean the
 * path has stopped answering.
 *
 * ## Why a count of PTOs and not a duration
 * A duration would be the settle window #385 was closed for not having a principled value for: *"The
 * 2s I was going to propose had exactly one justification: it is larger than the single 1.02s
 * excursion in the trace quoted above."* A PTO is the path's own
 * `smoothed_rtt + max(4·rttvar, kGranularity) + max_ack_delay` (RFC 9002 §6.2.1) and doubles on each
 * consecutive fire, so a count of them is a window that scales with the path instead of with a guess —
 * short on a fast link, patient on a slow one, and the same code either way. It is the same reasoning,
 * and the same refusal to add a [QuicOptions] knob, as [QuicheDriver]'s RFC 9000 §8.2.4 abandon budget.
 *
 * ## Why four
 * Chromium's path-degrading detection uses exactly this shape and exactly this number
 * (`kNumRetransmissionDelaysForPathDegradingDelay = 4`): it alarms four exponentially-backed-off
 * retransmission delays after the last packet received. Four fires is `(2⁴−1) = 15 × PTO` of proven
 * silence.
 *
 * Both ends of that are measured rather than argued. On the migration sim's default 120ms round trip
 * the fires land at 245ms, 735ms, 1715ms and 3675ms after the last packet received, so:
 *  - #385's recorded 1.02s excursion costs exactly **two** unanswered fires — measured by lowering
 *    this constant until `aBlipTheLengthOfThe385ExcursionCostsNoMigration` goes red, which happens at
 *    two, so the shipped value clears it by a full doubling and the path would have to stay dark
 *    3.6× longer before anything is attempted. That guard passed before this trigger existed as well
 *    as after, which is what makes it a guard;
 *  - the fourth fire is under a third of the 11.5s the Android walk spent waiting to be told —
 *    measured end to end at 3.695s from the path going dark to `PATH Probing` — and on a real path
 *    (PTO ≈ 130ms rather than 245ms) it is nearer 2s.
 *
 * A fifth fire would double the wait to reclaim one more doubling of margin against a blip whose cost,
 * per #385's own closing measurement, is *"a latency bump, not an outage"*. That trade is the wrong way
 * round: over-damping here is #574 again, and #574 is the one that was measured as an outage.
 */
internal const val SILENT_PATH_PTO_THRESHOLD = 4L
