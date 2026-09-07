package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.networkId
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Turns the public migration policy (QuicOptions.migration, Automatic by default) into a live reactor on
 * the client connection: a NetworkMonitor's path changes — and the connection's own evidence that the
 * path it is on has stopped answering — become QuicScope.migrate() calls, so a Wi-Fi↔cellular handoff
 * re-homes the QUIC connection with no caller code. The mirror of TraceCaptureWiring's
 * wireClientConnectivityTap — same one-hop shape, wired from the three QuicheEngine actuals' connect()
 * paths (never bind(): a server has no local client path).
 */

/**
 * Resolve [QuicOptions.networkMonitor] to the single [NetworkMonitor] instance this connection observes.
 *
 * Called **once per connection**, by the engine, and the result handed to all three consumers — the
 * reactor below, the trace tap, and [ConnectionNetworkObservation]. That sharing is an invariant, not an
 * optimisation: two monitors would report `ObservationSequence`s indexing different streams, so a
 * migration and the close correlation that is supposed to explain it would carry counters that look
 * joinable and are not.
 *
 * Neither case is owned here. [NetworkMonitorSource.Supplied] is the caller's, and the process default
 * belongs to whoever installed it — nothing in this module closes either.
 */
internal fun resolveNetworkMonitor(source: NetworkMonitorSource): NetworkMonitor =
    when (source) {
        NetworkMonitorSource.ProcessDefault -> NetworkMonitor.processDefault()
        is NetworkMonitorSource.Supplied -> source.monitor
    }

/**
 * What the monitor is reporting **right now**, as a snapshot: a routable link it can name, or nothing
 * it can name. Answers one question and only that one — see [Attachment] for the other.
 */
private sealed interface ObservedLink {
    /** Nothing routable and identified is being reported (unresolved, a captive portal, a dead radio). */
    data object None : ObservedLink

    /** The link, as the monitor named it. */
    data class Link(
        val id: NetworkId,
    ) : ObservedLink
}

/**
 * The link the monitor is reporting **now**, under exactly the filters the trigger pipeline applies —
 * one definition, so "what woke us" and "what is true" can never be filtered differently.
 */
private val NetworkMonitor.observedLink: ObservedLink
    get() =
        state.value.let { current ->
            if (current.canRouteOffLink && current.networkId != NetworkId.Unidentified) {
                ObservedLink.Link(current.networkId)
            } else {
                ObservedLink.None
            }
        }

/**
 * What the reactor believes about the link the connection is on.
 *
 * This started as a `NetworkId?` local whose KDoc argued the nullable was acceptable: "a local in one
 * function that nothing outside observes". #574 made that argument stop holding — the value is now read
 * by a second trigger that fires with no link change to name, and by [awaitRetrySlot] to decide what
 * would count as news.
 *
 * ⚠️ It is a **separate type from [ObservedLink]** even though both have two cases and one of them
 * holds a [NetworkId], and the first attempt at this fix shared one type between them. That sharing
 * looked like economy and was an overloaded meaning: `None` had to stand for both "the monitor is
 * naming nothing at this instant" and "no baseline has ever been taken", which are different facts with
 * different consequences. [NetworkId.Unidentified] is not the sentinel either — that is a link the
 * monitor **saw** and could not name, which the pipeline already drops, and reusing it would conflate
 * "no link" with "a nameless one".
 */
private sealed interface Attachment {
    /**
     * No routable, identified link has been reported yet, so the first one is the connect-time
     * baseline rather than a handoff.
     *
     * A data-plane migration taken from here leaves it standing, which means the link the monitor
     * eventually names is still read as the baseline. That is deliberate and it is correct:
     * [MigrationTarget.FreshLocalEndpoint] binds on whatever the platform's current default interface
     * is, so the first link the monitor manages to name *is* the one the connection is now on. If it
     * is not — the device handed off in between — the path we just moved to dies too, and the
     * data-plane trigger fires again; that backstop is exactly what makes leaving this alone safe.
     */
    data object AwaitingBaseline : Attachment

    /** The connection is on the link the monitor named. */
    data class On(
        val id: NetworkId,
    ) : Attachment
}

/** Whether [id] is the link this reactor already believes it is on. */
private fun Attachment.isAlready(id: NetworkId): Boolean = this is Attachment.On && this.id == id

/**
 * Whether [id] is news to a retry predicated on this attachment — one of the two things
 * [awaitRetrySlot] abandons a backoff on.
 *
 * [Attachment.AwaitingBaseline] answers `false` for everything, and only a **data-plane** retry can ask
 * from there (a control-plane one is predicated on the link it is moving onto, which by construction is
 * an [Attachment.On]). That is the right answer for it: the reactor has been told of no link at all, so
 * there is no link it is failing to reach, and each retry already asks the platform for whatever its
 * current default interface is. What ends *that* retry is the path answering again, which is the other
 * clause.
 */
private fun Attachment.isNewsComparedTo(id: NetworkId): Boolean = this is Attachment.On && this.id != id

/**
 * Why the reactor woke. Two sources, one sequential collector — see [wireAutoMigration]'s "two
 * triggers, one lane".
 *
 * Neither case carries the fact that produced it, deliberately. An emission can sit in the merge
 * behind a migration that takes seconds, so a payload here would be a snapshot of a world that has
 * moved on — and acting on it is how a queue of flaps becomes a queue of migrations. These are
 * wake-ups; the facts are re-read from [NetworkMonitor.observedLink] and [PathLiveness] at the moment
 * of use.
 */
private sealed interface MigrationTrigger {
    /** The platform named a different link (the control plane). */
    data object LinkChanged : MigrationTrigger

    /** The path we are on stopped answering, whatever the platform says about it (the data plane). */
    data object PathStoppedAnswering : MigrationTrigger
}

/**
 * Under [MigrationPolicy.Automatic], launch a child of [connection] that watches for the two events
 * that mean this connection should move — [monitor]'s identity-keyed path changes, and [pathLiveness]
 * reporting that the path we are on has stopped answering — and actively migrates
 * ([QuicScope.migrate] to [MigrationTarget.FreshLocalEndpoint], a fresh platform-chosen local endpoint)
 * on each. The collector is a child of the connection scope, so it stops when the connection closes.
 *
 * ## The pipeline, line by line — the reasoning is the load-bearing part
 *
 * **`filter { it.canRouteOffLink }`** — never migrate onto a link the monitor says traffic will not
 * cross (a captive portal, a suspended radio). Probing such a link burns a spare destination connection
 * id and then fails validation. Filtering on the **state**, before `map`, is what makes recovery work: a
 * link that is `Blocked` now and `Confirmed` later produces no emission while blocked, then emits its
 * id, and `distinctUntilChanged` sees a new id. Filtering *after* `map` would swallow the recovery,
 * because the identity never changed. On the `RouteOnly`/`LinkOnly` monitors (Apple, JVM, Linux, Node)
 * `internet` is `Unobserved`, `canRouteOffLink` is `true`, and nothing changes.
 *
 * **`filter { it != NetworkId.Unidentified }` before the baseline** — a monitor reports `Unidentified`
 * before it resolves the link (Apple's `NWPathMonitor` and the polling JVM monitor are both briefly
 * `Unknown`), so a baseline spent on `Unidentified` would make the *first real link* read as a handoff
 * and migrate a brand-new connection.
 *
 * **`distinctUntilChanged()`** — the dedupe that stops Android's ~1s `Pending`→`Confirmed` window on one
 * Wi-Fi network from reading as a handoff (RFC_NETWORK_REACHABILITY §5, `isTransient`).
 *
 * **[Attachment], not `drop(1)`** — the first identified link is the connect-time baseline, same
 * contract as before, but *recorded* rather than discarded. That record is what makes the two decisions
 * below possible: a flap that returns to the link we are already on is free, and a link we failed to
 * migrate onto is not mistaken for the one we live on.
 *
 * **`Impossible` cancels; `Failed` is retried on a decaying backoff** —
 * [MigrationResult.Unmoved.Impossible] is by definition the family where every later call answers the
 * same, whatever the network does, so the observer stops. A [MigrationResult.Unmoved.Failed] attempt
 * leaves the attachment alone and is re-attempted in place, because the emission that would otherwise be
 * the next new information **never arrives** (#453, and see [retryableWithoutNewInformation]).
 *
 * ## Two triggers, one lane (#574)
 *
 * The control-plane signal is not always right, and when it is wrong it is wrong for a long time.
 * Measured on the 71h Android walk: every real Wi-Fi→cellular handoff spent **11.5s / 11.5s / 11.9s**
 * — 17 consecutive failed echoes each — on a path carrying nothing while `ConnectivityManager` still
 * reported `net=wifi validated=true`; the migration that eventually followed took 338ms–1954ms. iOS
 * reports the same handoff in ~1s. So the outage was ~12.5s of which QUIC was under two, and the
 * difference was entirely *which* signal the connection was waiting for. [PathLiveness] is the second
 * one, and [SilenceThreshold.isMetBy] carries the argument for why it cannot re-open #385.
 *
 * The two sources are `merge`d into a **single sequential collector** rather than given a coroutine
 * each. That is not a style choice: `migrate()` suspends for the whole path move, and one collector is
 * what makes "one migration at a time" a property of the shape instead of a lock. Two collectors would
 * race each other into [MigrationResult.Unmoved.Failed.AlreadyInProgress] and then both retry.
 *
 * A [MigrationTrigger.PathStoppedAnswering] is re-checked against `pathLiveness.value` before it is
 * acted on, because a merged emission can be **queued behind a migration that was already in flight** —
 * and the fact it carries may have been settled by that very migration. Re-reading is what keeps
 * "the path was dark a second ago" from becoming a second, pointless move. (The driver publishing one
 * transition per episode, and publishing [PathLiveness.Answering] synchronously with the switch, is the
 * other half of that; neither alone is enough.)
 *
 * ⚠️ **The retry ladder re-reads it too, and has to.** [PathLiveness] is an edge on a level condition:
 * a dead path emits `Silent` once and then produces nothing, because the thing that would re-arm the
 * edge is a datagram arriving on it. So a ladder that consulted only the monitor could neither notice
 * the path recovering — measured, it then probed once a minute forever on a healthy connection — nor
 * be re-entered on a path that is still dark after the ladder gave up. Both are #574 arriving through
 * its own fix, and both are closed in [awaitRetrySlot], where the flow is one of the two things a
 * data-plane backoff is abandoned on.
 *
 * ## Why a failed attempt cannot wait for the next network event (#453)
 *
 * The original of this function answered a `Failed` with `Unit` and a comment saying "keep watching".
 * What it kept watching for was an event already in the past. The gate above the collector is
 * `distinctUntilChanged()` on **network identity**, so a second attempt needs the identity to change
 * *again* — but the handoff has already happened and the device is now sitting still on the new link,
 * so nothing further is emitted for the rest of the connection.
 *
 * Measured on a real Wi-Fi→cellular walk (2026-08-23): the reactor probed cellular once at
 * t=865026ms, the `PATH_CHALLENGE` went unanswered, `PathNotValidated` came back 3009ms later on the
 * RFC 9000 §8.2.4 abandon timer — and nothing tried again. The connection sat on the dead Wi-Fi path
 * through 57 consecutive failed reads and died of `IdleTimeout` 30 seconds after that. An unanswered
 * probe is the *ordinary* case on real cellular, not an exotic one, so one attempt per handoff is not
 * a policy, it is an outage.
 *
 * **What the probes cost, and why the pool is not what bounds them.** Every probe that reaches quiche
 * links a spare destination connection id to the new path, and every exit from that path — validated,
 * failed, abandoned — retires it (`PathSlot`, #447). On a *live* path that is self-replacing: the
 * `RETIRE_CONNECTION_ID` reaches the peer and a `NEW_CONNECTION_ID` comes back, which is why a
 * connection on a working link migrates indefinitely even at the RFC 9000 minimum of two — measured
 * at 40 consecutive migrations for every limit from 2 to 32. On a path that is already **dead**
 * neither frame crosses, so the pool is finite: [QuicOptions.activeConnectionIdLimit] minus the one
 * in use. Past it quiche answers `NoSpareConnectionId` *before* opening a socket, which is a real
 * answer but not a probe — it reaches no network. That default is therefore sized so the pool
 * is deep enough that a run of failed handoffs still has ids to spend; its KDoc carries the
 * measurement. ⚠️ An abandoned probe only gets its id *back* because of #459 — before that fix quiche
 * re-linked the peer's replacement into the dead probe path, so the pool drained once and never
 * refilled, and this retry loop would have been asking a question that could never be answered.
 *
 * **No quiet period between handoffs, deliberately — and the backoff is not one.** [QuicScope.migrate]
 * suspends until the new path has validated and the active path has switched (or the attempt has
 * failed), so a second migration cannot start while one is in flight and the rate is bounded by path
 * validation rather than by a guessed constant. While this collector is suspended, flaps coalesce: the
 * wake-ups queue, but they carry no facts, so the first one delivered resolves against whichever link
 * is current *then* and each one behind it finds the reactor already attached to it. Coalescing is
 * therefore still present and still keyed to the real cost of the operation — but as of #574 it is a
 * property of **re-reading**, not of `StateFlow` conflation, because `merge` buffers where a single
 * `StateFlow` collector conflated. `AutoMigrationReactorTests.changesDuringAnInFlightMigrationCoalesce`
 * is the test that caught the difference and is what pins it. A quiet
 * period on top could only refuse a genuine handoff arriving inside the window — leaving the
 * connection on a dead path for the remainder of it, which is precisely the outage active migration
 * exists to prevent. The retry backoff is the opposite of such a window and must stay that way: it is
 * abandoned the instant new information arrives ([awaitRetrySlot]) — a different routable link, which
 * the collector is about to be handed, or, for a data-plane retry, the path it was predicated on
 * answering again.
 *
 * No-op unless [QuicOptions.migration] is [MigrationPolicy.Automatic], and a genuine no-op — nothing is
 * even launched — for [NetworkMonitor.AlwaysAvailable], whose network identity never changes. Note that
 * the short-circuit is on the *monitor*, not on the whole reactor: it stands because that monitor is
 * the one platforms install when they cannot observe the network at all (Android without a Context,
 * Wasm), and those are exactly the platforms with no migration backend to trigger.
 */
internal fun wireAutoMigration(
    quicOptions: QuicOptions,
    connection: QuicConnection,
    monitor: NetworkMonitor,
    pathLiveness: StateFlow<PathLiveness>,
) {
    when (quicOptions.migration) {
        MigrationPolicy.Forbidden, MigrationPolicy.Manual -> return
        MigrationPolicy.Automatic -> Unit
    }
    // AlwaysAvailable never changes network identity (Android without an installed Context, Wasm) —
    // nothing to observe, so don't even launch a collector.
    if (monitor === NetworkMonitor.AlwaysAvailable) return
    connection.launch {
        var attachedTo: Attachment = Attachment.AwaitingBaseline
        merge(
            monitor.state
                .filter { it.canRouteOffLink }
                .map { it.networkId }
                .filter { it != NetworkId.Unidentified }
                .distinctUntilChanged()
                .map { MigrationTrigger.LinkChanged },
            pathLiveness
                .filter { it is PathLiveness.Silent }
                .map { MigrationTrigger.PathStoppedAnswering },
        ).collect { trigger ->
            // What `attachedTo` becomes if the move succeeds — and what makes a backoff stale. A
            // data-plane trigger changes neither: we are moving to a fresh local endpoint on the link
            // we were already told we are on.
            val attachOnSuccess =
                when (trigger) {
                    MigrationTrigger.LinkChanged -> {
                        // Re-read, never the emission's own payload: see [MigrationTrigger].
                        when (val now = monitor.observedLink) {
                            // Nothing routable to move onto any more — the link that woke us has been
                            // withdrawn while we were busy. Not a handoff, and not a baseline either.
                            ObservedLink.None -> return@collect
                            is ObservedLink.Link -> {
                                if (attachedTo == Attachment.AwaitingBaseline) {
                                    // The first identified link is the connect-time baseline.
                                    attachedTo = Attachment.On(now.id)
                                    return@collect
                                }
                                if (attachedTo.isAlready(now.id)) return@collect // a flap that came home
                                Attachment.On(now.id)
                            }
                        }
                    }

                    MigrationTrigger.PathStoppedAnswering -> {
                        // Re-read: this emission may have been queued behind a migration that has
                        // since answered the very question it is asking. See "two triggers, one lane".
                        if (pathLiveness.value !is PathLiveness.Silent) return@collect
                        attachedTo
                    }
                }
            var attempt = 1
            while (true) {
                when (val result = connection.migrate(MigrationTarget.FreshLocalEndpoint)) {
                    is MigrationResult.Succeeded -> {
                        attachedTo = attachOnSuccess
                        return@collect
                    }

                    is MigrationResult.Unmoved.Impossible -> {
                        cancel()
                        return@collect
                    }

                    // Not this time — and do NOT claim the link we failed to reach. Whether "not
                    // this time" is worth saying again is the leaf's own answer, never a default.
                    is MigrationResult.Unmoved.Failed -> {
                        if (!result.retryableWithoutNewInformation()) return@collect
                        val slot = awaitRetrySlot(trigger, monitor, pathLiveness, attachOnSuccess, backoffBeforeAttempt(attempt))
                        if (!slot) return@collect
                        attempt++
                    }
                }
            }
        }
    }
}

/**
 * How long to wait before attempt number `attempt + 1`, given [attempt] has just failed.
 *
 * **This is the whole of the retry policy.** There is no attempt count, because a count would be a
 * guess at when to stop and there is nothing to guess: the loop already ends on four *observed*
 * facts — it succeeded, a different link arrived ([awaitRetrySlot]), the leaf says asking again cannot
 * help ([retryableWithoutNewInformation]), or the connection is gone and answers
 * [MigrationResult.Unmoved.Impossible.ConnectionClosed], which cancels the observer. A handoff away
 * from a **dead** path is bounded by the last of those, and measured to be: with the retry loop
 * unbounded, a connection on a dead path made 7 attempts and died of `IdleTimeout` at 30.1s — the
 * same instant a bounded one died. RFC 9000 §10.1 restarts the idle timer only on the *first*
 * ack-eliciting packet sent since the last one received, so repeated PATH_CHALLENGEs cannot postpone
 * it and there is no zombie to protect against.
 *
 * What remains for this function to decide is not *whether* to keep asking but *how often*, and the
 * shape is set by the case the deadline does not bound: the old path is **healthy**, so the connection
 * lives indefinitely, while the link the platform says we moved to never answers. Giving up there is
 * wrong — the platform's position is that we have left the link we are sitting on, and a link that is
 * unreachable now may not be in five minutes — but asking at a fixed cadence forever is a probe, a
 * socket and a spare connection id every few seconds for the life of the connection.
 *
 * So the cadence decays instead: 250ms doubling to a [RETRY_BACKOFF_CEILING] ceiling. The early
 * doublings are the ones that matter and are unchanged — five attempts inside the first 19 seconds,
 * six inside 26 — which is the whole of a dead-path handoff's window. Past that it stretches out to
 * roughly one attempt a minute, so *never giving up* costs about as much per hour as the old fixed
 * budget cost per minute.
 *
 * The 250ms floor is a spin guard for the leaves that return *immediately* — `AlreadyInProgress`
 * resolves as the in-flight move completes, `HandshakeNotConfirmed` as the handshake confirms. For the
 * leaf that matters most, `PathNotValidated`, the attempt has already spent its RFC 9000 §8.2.4
 * abandon budget (~3s) inside quiche and the wait here is a rounding error on top.
 */
private fun backoffBeforeAttempt(attempt: Int): Duration =
    minOf(250.milliseconds * (1 shl (attempt - 1).coerceAtMost(BACKOFF_SHIFT_CAP)), RETRY_BACKOFF_CEILING)

/**
 * Ceiling on [backoffBeforeAttempt]. Reached at attempt 9, after which the reactor asks about once a
 * minute for as long as the connection lives — the steady state for a link the platform says we are on
 * and that we cannot reach. Small enough that a link coming good is picked up promptly; large enough
 * that doing so forever is not a cost worth counting.
 */
private val RETRY_BACKOFF_CEILING = 60.seconds

/** Guards the shift in [backoffBeforeAttempt] from overflowing once the ceiling has been reached anyway. */
private const val BACKOFF_SHIFT_CAP = 20

/**
 * Wait out [backoff] before re-attempting a migration, and report whether the retry is still the right
 * thing to do. [attempting] is what the attempt is predicated on: the link being moved onto for a
 * control-plane handoff, or — for a data-plane one, which names no new link — the link the connection
 * was already told it is on.
 *
 * Returns `true` when the backoff elapsed with no better idea available — retry. Returns `false` when
 * something arrived first that makes this retry stale, and there are two such things, one per trigger.
 *
 * **A different routable, identified link (both triggers).** That link is new information, the
 * collector is about to be handed it, and it should be migrated onto instead of whatever we were
 * failing to reach. Collecting [NetworkMonitor.state] a second time here is free (it is a `StateFlow`)
 * and is what keeps the backoff from becoming the quiet period this reactor deliberately does not
 * have — a genuine handoff arriving mid-backoff is acted on at once rather than waiting it out. The
 * filters mirror the main pipeline exactly: a link the monitor cannot name, or says traffic will not
 * cross, is not a reason to abandon the retry.
 *
 * **The path answering again (data-plane trigger only), and this one is not optional.** [PathLiveness]
 * is an edge on a level condition, so once the ladder is running nothing further will be emitted; a
 * ladder that consulted only the monitor could not see the path recover at all. Measured on the first
 * cut of #574, with `PathNotValidated` and the path healing immediately after attempt 1: attempts
 * `1 → 5 → 9 → 14` at 5s, 65s and 365s with `pathLiveness` reading `Answering` throughout, and no
 * termination — one probe, one socket and one spare connection id a minute, for the life of a
 * perfectly healthy connection. The realistic route in is short: a 20ms Wi-Fi blip goes `Silent`, the
 * probe's `PATH_CHALLENGE` is eaten by a middlebox, and the path is back 200ms later.
 *
 * It is also what closes the dual of that defect. Every exit from the ladder other than
 * [MigrationResult.Succeeded] leaves a reactor that has already consumed its one `Silent` edge, so
 * without a clause that can end a *data-plane* ladder on the path's own recovery, the reactor either
 * spins forever (above) or — for the exits that return — sits idle on a path still reporting `Silent`
 * with no trigger left to raise. Both are #574 reached through its own fix.
 */
private suspend fun awaitRetrySlot(
    trigger: MigrationTrigger,
    monitor: NetworkMonitor,
    pathLiveness: StateFlow<PathLiveness>,
    attempting: Attachment,
    backoff: Duration,
): Boolean =
    withTimeoutOrNull(backoff) {
        merge(
            monitor.state
                .filter { it.canRouteOffLink }
                .map { it.networkId }
                .filter { it != NetworkId.Unidentified && attempting.isNewsComparedTo(it) }
                .map { },
            when (trigger) {
                // A control-plane retry is trying to reach a link the platform says we have moved to,
                // and the *old* path coming good says nothing about whether it can. #453's contract —
                // keep asking, on a decaying cadence, for as long as the connection lives — is
                // unchanged for it.
                MigrationTrigger.LinkChanged -> emptyFlow()
                // A data-plane retry exists only because the path had stopped answering. The moment it
                // answers again the premise is gone.
                MigrationTrigger.PathStoppedAnswering -> pathLiveness.filter { it !is PathLiveness.Silent }.map { }
            },
        ).first()
    } == null

/**
 * Whether asking [QuicScope.migrate] the identical question again can plausibly answer differently
 * **with nothing else having changed** — which is the only situation this reactor can create, because
 * the network event that would constitute a change is the one #453 proved never arrives.
 *
 * Exhaustive on purpose. A new [MigrationResult.Unmoved.Failed] leaf must state its own answer here
 * rather than inherit whichever one this function happened to default to; the whole point of the
 * sealed family is that the compiler asks.
 */
private fun MigrationResult.Unmoved.Failed.retryableWithoutNewInformation(): Boolean =
    when (this) {
        // Resolves on its own as the handshake confirms; the next attempt is the entire point.
        MigrationResult.Unmoved.Failed.HandshakeNotConfirmed -> true
        // One path move at a time — the in-flight one completes and frees the lane.
        MigrationResult.Unmoved.Failed.AlreadyInProgress -> true
        // The peer replenishes the pool with NEW_CONNECTION_ID, and at connection start this is
        // routinely a race against the peer's *first* one rather than a verdict (#448).
        MigrationResult.Unmoved.Failed.NoSpareConnectionId -> true
        // The measured #453 case: an unanswered PATH_CHALLENGE is ordinary on cellular, and the next
        // probe is a fresh 4-tuple the peer may well answer.
        MigrationResult.Unmoved.Failed.PathNotValidated -> true
        // A bind that failed or collided with the live path's 4-tuple; a later bind lands elsewhere.
        is MigrationResult.Unmoved.Failed.LocalPathUnavailable -> true
        // quiche refused this probe, or refused to switch onto a path that did validate. Both carry a
        // code rather than a promise, and both have transient sources (a path table at its limit, a
        // move already under way), so they are worth asking again.
        is MigrationResult.Unmoved.Failed.ProbeRejected -> true
        is MigrationResult.Unmoved.Failed.SwitchRejected -> true
        // The only leaf that is deterministic *for this reactor*. It reports that the platform assigns
        // the local endpoint itself, so a named target cannot be bound — and this reactor only ever
        // asks for FreshLocalEndpoint, which every platform serves. Repeating the identical request
        // cannot change the answer; a different target could, and the reactor has none to offer. It
        // stays in `Failed` rather than `Impossible` because a *caller* naming a different target may
        // still succeed.
        MigrationResult.Unmoved.Failed.EndpointNotSelectable -> false
    }
