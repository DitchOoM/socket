package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * two. Apple's `NWPathMonitor` reports the same handoff in ~1s (0.9s and 1.1s of silence, 1 failed
 * echo, same walk), which is the whole of a 10× platform asymmetry the connection can simply decline
 * to inherit: the driver already knows the path has gone quiet, because it is the thing holding the
 * unanswered packets.
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
 * wakes it already has, since a loss-detection timer expiring *is* one — and moves between these two
 * values. There is exactly one transition per episode of silence in each direction, which is what
 * keeps a trigger queued behind an in-flight migration from outliving the evidence that produced it.
 *
 * ⚠️ That makes this an **edge** on a level condition, and the reactor is responsible for the
 * difference: a dead path produces no datagrams, so nothing can re-arm the edge while it stays dead.
 * [wireAutoMigration]'s retry ladder therefore re-reads this flow rather than waiting to be told
 * again — see `awaitRetrySlot`.
 */
internal sealed interface PathLiveness {
    /**
     * The active path has received something since the last time its loss-detection timer expired —
     * or has nothing outstanding for that timer to be armed about.
     *
     * The second half is deliberate and is the honest reading of an idle connection: a path carrying
     * no unacknowledged data is indistinguishable from a working one, so silence over it is not
     * evidence of anything. RFC 9002 §6.2.1 arms the PTO only while there are ack-eliciting packets in
     * flight, so this state is what an idle connection sits in until it has something to say — or
     * until [QuicOptions.keepAliveInterval] says it for it.
     */
    data object Answering : PathLiveness

    /**
     * The active path has met **both** halves of [pathHasStoppedAnswering]: a run of unanswered
     * loss-detection expiries, over a stretch of time long enough that a blip cannot have produced it.
     * Not "some packets went missing": every retransmission the path's own loss recovery scheduled in
     * that window went unanswered too.
     *
     * Deliberately carries no counter. It is a single value per episode, so the
     * [kotlinx.coroutines.flow.StateFlow] publishing it emits once when the path goes dark and once
     * when it comes back, and a reactor that was busy migrating when the transition happened cannot
     * later drain a backlog of stale evidence. The numbers that produced it are properties of the
     * threshold, not of the observation.
     */
    data object Silent : PathLiveness
}

/**
 * The whole of the threshold: the active path has stopped answering once [unansweredExpiries]
 * consecutive loss-detection timer expiries have gone unanswered **and** [silentFor] has elapsed since
 * the first of them.
 *
 * ## Why it has to be a conjunction, measured
 * The first draft was the count alone, on the argument that a count of PTOs is "a window that scales
 * with the path" and therefore not the arbitrary constant #385 was closed for lacking. That argument
 * is **false at low RTT**, and the failure is not subtle. RFC 9002 §6.2.1's PTO is
 * `smoothed_rtt + max(4·rttvar, kGranularity) + max_ack_delay`, and in the Application epoch
 * `max_ack_delay` is the peer's advertised value — quiche's default, which this library never
 * overrides, is **25ms**. So the PTO has a hard floor near 26ms however fast the path is, and
 * `(2⁴−1) = 15` of them bottom out around 390ms: a count-only threshold degenerates into a ~0.4s
 * wall-clock constant that nobody chose and that is *below* the excursion #385 recorded.
 *
 * Measured, by sweeping only `primaryImpairment` in the #385 guard
 * (`aBlipTheLengthOfThe385ExcursionCostsNoMigration`) with the time floor set to zero — i.e. the
 * count-only rule — against the same 1.02s excursion:
 *
 * | one-way latency | 2ms | 10ms | 20ms | 40ms | 60ms | 120ms |
 * |---|---|---|---|---|---|---|
 * | migrations bought, count-only | 1 | 1 | 0 | 0 | 0 | 0 |
 * | migrations bought, shipped | 0 | 0 | 0 | 0 | 0 | 0 |
 *
 * (An independent run of the same sweep also lost the 20ms arm; the boundary moves with the seeded
 * arrival order, which is the point — it is a boundary at all only because the floor exists.)
 *
 * #385's trace was **an iPhone on Wi-Fi**, which is 10–30ms to a CDN edge — squarely in the left half
 * of that table. The single 120ms row is the only one the suite used to run, which is exactly why it
 * was invisible; the guard now sweeps [com.ditchoom.socket.quic.MigrationSimTests] over all six.
 *
 * No pure count can satisfy both ends: clearing 1.02s at the 26ms floor needs about six expiries, and
 * `(2⁶−1) = 63` PTOs on a 120ms path is ~9s — within sight of the 11.5s this exists to remove. The two
 * conditions therefore do different jobs and neither is redundant: **the count dominates on slow paths
 * and the time floor dominates on fast ones.**
 *
 * ## Why four expiries
 * Chromium's path-degrading detection uses exactly this shape and exactly this number
 * (`kNumRetransmissionDelaysForPathDegradingDelay = 4`): it alarms four exponentially-backed-off
 * retransmission delays after the last packet received.
 *
 * ⚠️ It is a count of **loss-detection timer expiries**, not strictly of PTOs, and the difference is
 * quiche's: `Path::on_loss_detection_timeout` increments `total_pto_count` on *every* expiry, the
 * time-threshold loss branch included, and that branch does not back off. (quiche's own header comment
 * — "PTO count measures the number of loss events and provides a normalized loss metric" — is wrong
 * about this.) So four expiries can be reached sooner than `15 × PTO` under loss, which is precisely
 * the other reason the time floor is not optional: on an ordinarily lossy but working path the count
 * runs ahead while [silentFor] does not, and `aLossyPathIsNotASilentOne` measures that at 10 %, 20 %
 * and 30 % loss.
 *
 * ## Why two seconds
 * A duration here is a chosen constant and there is no honest way to pretend otherwise, so the choice
 * is stated rather than dressed up. It is bounded on both sides by measurements:
 *  - it must clear the longest excursion on record that healed itself — #385's **1.02s** — with
 *    margin, and 2s is twice it;
 *  - it must stay far under the lag it replaces — the walk's **11.5s** — and 2s is 5.75× under it;
 *  - `NWPathMonitor`, the platform signal nobody complains about, declares the same handoff after
 *    ~1s of silence, so this is deliberately the more conservative of the two: a migration is ours to
 *    pay for and a false one is pure loss.
 *
 * And the asymmetry of being wrong is measured, which is what makes "nearer the excursion than the
 * lag" the right side to land on: #385's own closing analysis found that migrating on a blip costs a
 * latency bump rather than an outage (13ms vs 3ms max RTT; 40/40 consecutive migrations at every
 * `activeConnectionIdLimit` from 2 to 32, so no pool pressure either), while failing to migrate cost
 * the 11.5s outage this issue is about.
 *
 * It is evaluated only when an expiry wakes the driver, and expiries are exponentially spaced, so the
 * effective detection point is the first expiry past both conditions: measured at 3.695s on the sim's
 * 120ms round trip (count-bound) and in the same 3–4s band on a fast path (time-bound). Roughly
 * NWPathMonitor's job done three times slower, and Android's done three times faster.
 */
internal fun pathHasStoppedAnswering(
    unansweredExpiries: Long,
    silentFor: Duration,
): Boolean = unansweredExpiries >= SILENT_PATH_EXPIRY_THRESHOLD && silentFor >= SILENT_PATH_MINIMUM_SILENCE

/**
 * Consecutive unanswered loss-detection timer expiries on the active path that, **together with**
 * [SILENT_PATH_MINIMUM_SILENCE], mean it has stopped answering. Chromium's
 * `kNumRetransmissionDelaysForPathDegradingDelay`; see [pathHasStoppedAnswering] for the whole
 * argument, including why neither half of the conjunction can be dropped.
 */
internal const val SILENT_PATH_EXPIRY_THRESHOLD = 4L

/**
 * How long the run in [SILENT_PATH_EXPIRY_THRESHOLD] must have been going before it counts. Twice the
 * longest self-healing excursion on record (#385's 1.02s), 5.75× under the lag it replaces (11.5s);
 * see [pathHasStoppedAnswering].
 */
internal val SILENT_PATH_MINIMUM_SILENCE: Duration = 2.seconds
