package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the path the connection is **living on** is still answering — the data-plane second opinion
 * [wireAutoMigration] holds beside [com.ditchoom.socket.NetworkMonitor]'s control-plane one.
 *
 * ## Why a connection needs a second opinion at all
 * The platform's reachability signal can be wrong for a long time. On a real Wi-Fi→cellular handoff
 * Android's `ConnectivityManager` can go on reporting the Wi-Fi network validated for over ten seconds
 * after it stops carrying anything, while the migration that follows takes well under two; Apple's
 * `NWPathMonitor` reports the same handoff in about a second. That is a 10× platform asymmetry the
 * connection can simply decline to inherit: the driver already knows the path has gone quiet, because
 * it is the thing holding the unanswered packets.
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
     * The active path has met **both** halves of [SilenceThreshold.isMetBy]: a run of unanswered
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
 * the first of them — or once [silentFor] alone has reached [patience], whichever comes first.
 *
 * Three numbers, doing three different jobs, none of them redundant: the count dominates on slow paths,
 * the [silence] floor on fast ones, and [patience] on paths whose round trip quiche cannot yet estimate,
 * where the count is denominated in a PTO four times too large. [SILENT_PATH_PATIENCE] carries that
 * third argument; the two below are the pair.
 *
 * ## Why it has to be a conjunction
 * A count of PTOs looks like "a window that scales with the path" rather than an arbitrary constant.
 * That argument is **false at low RTT**, and the failure is not subtle. RFC 9002 §6.2.1's PTO is
 * `smoothed_rtt + max(4·rttvar, kGranularity) + max_ack_delay`, and in the Application epoch
 * `max_ack_delay` is the peer's advertised value — quiche's default, which this library never
 * overrides, is **25ms**. So the PTO has a hard floor near 26ms however fast the path is, and
 * `(2⁴−1) = 15` of them bottom out around 390ms: a count-only threshold degenerates into a ~0.4s
 * wall-clock constant that nobody chose and that is *below* a self-healing excursion of about a
 * second — an iPhone on Wi-Fi is 10–30ms to a CDN edge, squarely where a count-only rule buys a
 * migration for a blip. The sim sweeps one-way latency from 2ms to 120ms to pin that boundary.
 *
 * No pure count can satisfy both ends: clearing a one-second excursion at the 26ms floor needs about
 * six expiries, and `(2⁶−1) = 63` PTOs on a 120ms path is ~9s — within sight of the platform lag this
 * exists to remove. The two conditions therefore do different jobs and neither is redundant: **the
 * count dominates on slow paths and the time floor dominates on fast ones.**
 *
 * ## Why the count needs a ceiling as well as a floor
 * The floor bounds the run from below; the count cannot bound it from above, because `15 × PTO` is a
 * duration only if a PTO is one — and on a path
 * quiche has no ack from, a PTO is the RFC 9002 initial `333 + 4·166.5 + 25 = 1024ms` rather than the
 * 246ms of a sampled 120ms path, so the same four expiries cost **15.36s instead of 3.695s**. That is
 * not a corner: it is what a connection looks like for the first round trip after it migrates. See
 * [SILENT_PATH_PATIENCE] for why the ceiling is four seconds.
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
 * is stated rather than dressed up. It is bounded on both sides:
 *  - it must clear the longest self-healing excursion on record — about **1s** — with margin, and 2s
 *    is twice it;
 *  - it must stay far under the platform lag it guards against — over **10s** on Android;
 *  - `NWPathMonitor`, the platform signal nobody complains about, declares the same handoff after
 *    ~1s of silence, so this is deliberately the more conservative of the two: a migration is ours to
 *    pay for and a false one is pure loss.
 *
 * The asymmetry of being wrong is what makes "nearer the excursion than the lag" the right side to
 * land on: migrating on a blip costs a latency bump rather than an outage, and no connection-id pool
 * pressure even at the RFC minimum limit, while failing to migrate costs the whole outage.
 *
 * It is evaluated only when an expiry wakes the driver, and expiries are exponentially spaced, so the
 * effective detection point is the first expiry past both conditions: about 3.7s on the sim's 120ms
 * round trip (count-bound) and in the same 3–4s band on a fast path (time-bound). Roughly
 * NWPathMonitor's job done three times slower, and Android's done three times faster.
 *
 * An application writing faster than the backed-off PTO fires no further expiry (RFC 9002 §6.2.1 times
 * the PTO from the latest ack-eliciting send), so there the count never completes and
 * [isMetByUnansweredSends] decides instead: [patience] after the first write the path did not answer.
 *
 * Public only because [QuicheDriver]'s constructor is, and a parameter has to name a type its caller
 * could see. Nothing outside this module constructs one — production passes [SILENT_PATH_THRESHOLD],
 * which stays internal.
 */
class SilenceThreshold(
    val expiries: Long,
    val silence: Duration,
    val patience: Duration,
) {
    /**
     * The two-argument threshold, taking [SILENT_PATH_PATIENCE] for the ceiling.
     *
     * Part of the published API of a public class; a default parameter would replace this signature
     * rather than add to it. Nothing in this module calls it — [SILENT_PATH_THRESHOLD] names all three
     * — so it exists purely so an already-linked caller keeps resolving.
     */
    constructor(expiries: Long, silence: Duration) : this(expiries, silence, SILENT_PATH_PATIENCE)

    init {
        // A threshold of zero expiries would declare every idle connection dead, and a zero floor is
        // the count-only rule that declares a healthy fast path dead. Neither is a configuration
        // anyone should be able to express, so neither is constructible — and the refusal names which
        // half was wrong as a value, not as a sentence.
        if (expiries < 1) throw InvalidSilenceThreshold(InvalidSilenceThreshold.Problem.NotEnoughExpiries, expiries.toString())
        if (silence <= Duration.ZERO) throw InvalidSilenceThreshold(InvalidSilenceThreshold.Problem.NoTimeFloor, silence.toString())
        // A ceiling at or under the floor is not a ceiling: it would answer the whole question before
        // the floor could refuse a blip, which is the count-only rule again by a different route.
        if (patience <= silence) {
            throw InvalidSilenceThreshold(InvalidSilenceThreshold.Problem.PatienceUnderFloor, "$patience <= $silence")
        }
    }

    /**
     * Whether a run of [unansweredExpiries] that has been going for [silentFor] means the path has
     * stopped answering.
     *
     * Read in the order it is written. A run with **no** unanswered expiry in it is refused outright,
     * whatever the elapsed time: silence over a path that has not failed to answer anything is not
     * evidence, it is an idle connection. Then the ceiling, which is the count-free clause. Then the
     * original conjunction, which is the only one that can be met early.
     *
     * The first line is unreachable from production — [QuicheDriver]'s sampler only builds a run out of
     * expiries that did happen — and is here anyway, because the ceiling is the one clause that would
     * otherwise answer `true` for a path nothing has ever gone unanswered on, and a predicate whose
     * safety comes from its caller stops being safe the moment it acquires a second one.
     *
     * ⚠️ Being met is necessary and not sufficient: the sampler additionally requires the read in hand
     * to have carried a new expiry, so that a run which has stopped growing cannot age into the ceiling.
     * That is a property of *when this is asked*, not of the threshold, so it lives there.
     */
    fun isMetBy(
        unansweredExpiries: Long,
        silentFor: Duration,
    ): Boolean {
        if (unansweredExpiries < 1) return false
        if (silentFor >= patience) return true
        return unansweredExpiries >= expiries && silentFor >= silence
    }

    /**
     * Whether ack-eliciting data left unanswered for [unansweredFor] means the path has stopped
     * answering: the [patience] ceiling, needing no expiry at all.
     *
     * The count cannot bound an application that keeps writing. RFC 9002 §6.2.1 times the PTO from the
     * most recent ack-eliciting send, so writes more frequent than the backed-off PTO hold the count
     * where it is. A working path answers ack-eliciting data within one PTO, and [patience] sits above
     * every PTO of a working path.
     */
    fun isMetByUnansweredSends(unansweredFor: Duration): Boolean = unansweredFor >= patience
}

/**
 * Consecutive unanswered loss-detection timer expiries on the active path that, **together with**
 * [SILENT_PATH_MINIMUM_SILENCE], mean it has stopped answering. Chromium's
 * `kNumRetransmissionDelaysForPathDegradingDelay`; see [SilenceThreshold.isMetBy] for the whole
 * argument, including why neither half of the conjunction can be dropped.
 */
internal const val SILENT_PATH_EXPIRY_THRESHOLD = 4L

/**
 * How long the run in [SILENT_PATH_EXPIRY_THRESHOLD] must have been going before it counts. Twice the
 * longest self-healing excursion on record (about 1s), far under the platform lag it guards against
 * (over 10s); see [SilenceThreshold.isMetBy].
 */
internal val SILENT_PATH_MINIMUM_SILENCE: Duration = 2.seconds

/**
 * How long a run of unanswered expiries is allowed to go on before the **count is no longer required**
 * — the ceiling to [SILENT_PATH_MINIMUM_SILENCE]'s floor, and the bound that makes detection time
 * finite at all.
 *
 * ## Why a count of expiries cannot bound detection time on its own
 * [SILENT_PATH_EXPIRY_THRESHOLD] is a count, and `(2⁴−1) = 15` PTOs is only a duration if a PTO is.
 * It is not. RFC 9002 §5.1 starts a path at `kInitialRtt = 333ms` with `rttvar = 166.5ms`, and quiche
 * takes an RTT sample only from an **ack**, so a path can be carrying traffic and still be estimated
 * at the initial value. `333 + 4·166.5 + 25 = 1024ms`, four times the 246ms of a sampled 120ms path,
 * and the four-expiry budget stretches with it: 15.36s of dead air on an unsampled path against 3.7s
 * on a sampled one.
 *
 * The un-sampled case is not a corner: it is what a connection that has *just migrated* looks like,
 * because the peer is still replying to the address it was last told about, so no ack has reached the
 * new path yet. Without the ceiling, detection time is the spread of a budget denominated in an
 * estimate rather than in time.
 *
 * The same estimate inflates *upward* without bound after any outage (an `rttvar` near a second on a
 * path that has been dark), so the second stall of a pair costs four times the first. A count is a
 * clock whose tick length is set by the thing being measured.
 *
 * ## Why four seconds
 * The ceiling binds where the count is slowest, and the arithmetic says exactly where that is: a run's
 * elapsed time at its *k*-th expiry is `(2^k − 2)·PTO`, so the third expiry lands at `6·PTO` and this
 * ceiling first bites at `PTO > 667ms`. That is above every PTO of a working path — real round trips
 * sit well under 200ms, and the sim's slowest arm is a 240ms round trip at `PTO ≈ 465ms` — and 1.5×
 * below the 1024ms of an un-sampled one. So it changes nothing on a path the count already handles
 * and rescues the one it cannot: detection in the two-migration scenario is the same at every
 * ceiling from 2.5s to 6s, and 15.36s without one. The value is not tuning a boundary; it is picking
 * which side of a 1.5× gap to sit on.
 *
 * ⚠️ It is never armed as a timer off a run: a run that has stopped growing must never be declared on
 * elapsed time alone. It is armed off an answer the path owes for ack-eliciting data handed to quiche
 * ([SilenceThreshold.isMetByUnansweredSends]), which is what bounds detection when an application
 * writing faster than the backed-off PTO keeps the count still. `QuicheDriver.floorWake` and
 * `QuicheDriver.unansweredWake` carry the argument.
 */
internal val SILENT_PATH_PATIENCE: Duration = 4.seconds

/**
 * The shipped threshold — [SILENT_PATH_EXPIRY_THRESHOLD] expiries over [SILENT_PATH_MINIMUM_SILENCE].
 *
 * The driver takes a [SilenceThreshold] rather than reading these constants directly so the margin
 * around them is **testable**: a `const val` is inlined at every use site, so no test could vary it.
 * With the seam, `MigrationSimTestSuite` asserts where the boundary *is* — the guards hold at 4 and
 * at 3 and break at 2 — on every backend that runs the shared suite, so a change that moves the
 * boundary fails a test instead of passing quietly.
 */
internal val SILENT_PATH_THRESHOLD =
    SilenceThreshold(SILENT_PATH_EXPIRY_THRESHOLD, SILENT_PATH_MINIMUM_SILENCE, SILENT_PATH_PATIENCE)

/**
 * A [SilenceThreshold] that cannot mean anything, and which half of it was wrong.
 *
 * The [problem] is the payload; [rejected] is the offending value rendered for a human reading a stack
 * trace, never the thing a caller is expected to parse. An `IllegalArgumentException` carrying only a
 * sentence would make "which half" un-inspectable, which is the shape this repository does not want
 * even for a construction precondition.
 */
internal class InvalidSilenceThreshold(
    val problem: Problem,
    val rejected: String,
) : IllegalArgumentException("$problem: $rejected") {
    internal enum class Problem {
        /** Fewer than one unanswered expiry — every idle connection would read as dead. */
        NotEnoughExpiries,

        /** A zero or negative time floor — the count-only rule that declares a healthy fast path dead. */
        NoTimeFloor,

        /** A ceiling at or below the floor, which would decide every run before the floor could. */
        PatienceUnderFloor,
    }
}
