package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.networkId
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Turns the public migration policy (QuicOptions.migration, Automatic by default) into a live reactor on
 * the client connection: a NetworkMonitor's path changes become QuicScope.migrate() calls, so a
 * Wi-Fi↔cellular handoff re-homes the QUIC connection with no caller code. The mirror of
 * TraceCaptureWiring's wireClientConnectivityTap — same one-hop shape, wired from the three QuicheEngine
 * actuals' connect() paths (never bind(): a server has no local client path).
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
 * Under [MigrationPolicy.Automatic], launch a child of [connection] that watches [monitor]'s
 * identity-keyed path changes and actively migrates ([QuicScope.migrate] to
 * [MigrationTarget.FreshLocalEndpoint] — a fresh platform-chosen local endpoint) on each change to a new
 * link. The collector is a child of the connection scope, so it stops when the connection closes.
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
 * **`attachedTo`, not `drop(1)`** — the first identified link is the connect-time baseline, same
 * contract as before, but *recorded* rather than discarded. That record is what makes the two decisions
 * below possible: a flap that returns to the link we are already on is free, and a link we failed to
 * migrate onto is not mistaken for the one we live on.
 *
 * **`Impossible` cancels; `Failed` is retried on a bounded backoff** —
 * [MigrationResult.Unmoved.Impossible] is by definition the family where every later call answers the
 * same, whatever the network does, so the observer stops. A [MigrationResult.Unmoved.Failed] attempt
 * leaves `attachedTo` alone and is re-attempted in place, because the emission that would otherwise be
 * the next new information **never arrives** (#453, and see [retryableWithoutNewInformation]).
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
 * exceeds what [migrationAttemptBudget] allows, so every attempt in the budget is a probe that
 * actually goes out; its KDoc carries the measurement.
 *
 * **No quiet period between handoffs, deliberately — and the backoff is not one.** [QuicScope.migrate]
 * suspends until the new path has validated and the active path has switched (or the attempt has
 * failed), so a second migration cannot start while one is in flight and the rate is bounded by path
 * validation rather than by a guessed constant. While this collector is suspended the upstream
 * `StateFlow` conflates: intermediate flaps are dropped and it resumes on whichever link is current
 * *then*. Coalescing is therefore already present, keyed to the real cost of the operation. A quiet
 * period on top could only refuse a genuine handoff arriving inside the window — leaving the
 * connection on a dead path for the remainder of it, which is precisely the outage active migration
 * exists to prevent. The retry backoff is the opposite of such a window and must stay that way: it is
 * abandoned the instant a different routable link appears ([awaitRetrySlot]), because that link is
 * new information and the collector is about to be handed it.
 *
 * No-op unless [QuicOptions.migration] is [MigrationPolicy.Automatic], and a genuine no-op — nothing is
 * even launched — for [NetworkMonitor.AlwaysAvailable], whose network identity never changes.
 */
internal fun wireAutoMigration(
    quicOptions: QuicOptions,
    connection: QuicConnection,
    monitor: NetworkMonitor,
) {
    when (quicOptions.migration) {
        MigrationPolicy.Forbidden, MigrationPolicy.Manual -> return
        MigrationPolicy.Automatic -> Unit
    }
    // AlwaysAvailable never changes network identity (Android without an installed Context, Wasm) —
    // nothing to observe, so don't even launch a collector.
    if (monitor === NetworkMonitor.AlwaysAvailable) return
    val attemptBudget = migrationAttemptBudget(quicOptions)
    connection.launch {
        // `null` here means "the baseline emission has not arrived yet" — and it stays a nullable on
        // purpose, against this campaign's usual rule. It is a local in one function that nothing outside
        // observes; `NetworkId.Unidentified` would be an actively *wrong* sentinel (it is a link the
        // monitor saw but could not name, which the filter above already drops); and a sealed wrapper for
        // a three-line local is noise, not precision.
        var attachedTo: NetworkId? = null
        monitor.state
            .filter { it.canRouteOffLink }
            .map { it.networkId }
            .filter { it != NetworkId.Unidentified }
            .distinctUntilChanged()
            .collect { id ->
                if (attachedTo == null) {
                    // The first identified link is the connect-time baseline, not a change.
                    attachedTo = id
                    return@collect
                }
                if (id == attachedTo) return@collect // a flap that came home costs nothing
                var attempt = 1
                while (true) {
                    when (val result = connection.migrate(MigrationTarget.FreshLocalEndpoint)) {
                        is MigrationResult.Succeeded -> {
                            attachedTo = id
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
                            if (attempt >= attemptBudget) return@collect
                            if (!awaitRetrySlot(monitor, id, backoffBeforeAttempt(attempt))) return@collect
                            attempt++
                        }
                    }
                }
            }
    }
}

/**
 * How many times one handoff may ask [QuicScope.migrate], the first attempt included — **derived from
 * the deadline that actually ends the connection**, not chosen.
 *
 * A handoff away from a dead path is racing [QuicOptions.idleTimeout]: nothing arrives on the old
 * path, so the idle timer runs to the end and kills the connection (measured in the field at exactly
 * that, #453). Every attempt inside that window is worth making and every attempt outside it is
 * addressed to a connection that no longer exists, so the honest budget is "as many as fit", and this
 * walks the schedule to find it: each attempt costs one RFC 9000 §8.2.4 abandon budget
 * ([QuicheDriver.PATH_VALIDATION_FLOOR]) and every attempt after the first also costs its
 * [backoffBeforeAttempt].
 *
 * At the defaults that lands on 6 attempts spending 25.75s of a 30s window — the same number the
 * constant here used to be, and the same completion times the deterministic sim measures, which is
 * the check that the arithmetic below describes the real loop. A caller who shortens `idleTimeout`
 * now gets a reactor that stops when their connection does, and one who lengthens it gets the extra
 * attempts their window pays for; neither used to happen.
 *
 * ⚠️ **The floor, not the actual cost.** [QuicheDriver.pathValidationBudget] widens with the current
 * path's RTT and the reactor cannot see any individual path's PTO, so on a slow path each attempt
 * costs more than assumed here and the budget overshoots. That direction is the safe one: the extra
 * attempts land on an already-closed connection, answer
 * [MigrationResult.Unmoved.Impossible.ConnectionClosed], and cancel the observer — which is the
 * correct end state anyway.
 *
 * [ceiling] is the second bound, and the one that applies when `idleTimeout` is zero (its documented
 * "no timeout"): there is no point asking more times than the spare connection id pool can turn into
 * probes, plus a little slack for the leaves that consume no id at all
 * ([MigrationResult.Unmoved.Failed.HandshakeNotConfirmed], [MigrationResult.Unmoved.Failed.AlreadyInProgress]).
 */
internal fun migrationAttemptBudget(quicOptions: QuicOptions): Int {
    val sparePool = (quicOptions.activeConnectionIdLimit - 1).coerceAtLeast(1)
    val ceiling = (sparePool + CHEAP_FAILURE_SLACK).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val deadline = quicOptions.idleTimeout
    if (deadline <= Duration.ZERO) return ceiling
    var attempts = 0
    var spent = Duration.ZERO
    while (attempts < ceiling) {
        val backoff = if (attempts == 0) Duration.ZERO else backoffBeforeAttempt(attempts)
        val next = spent + backoff + QuicheDriver.PATH_VALIDATION_FLOOR
        if (next > deadline) break
        spent = next
        attempts++
    }
    // A handoff always gets one try, however short the caller's idle timeout: refusing to attempt at
    // all would make a small `idleTimeout` silently disable automatic migration.
    return attempts.coerceAtLeast(1)
}

/**
 * Attempts allowed above the spare connection id pool, for the [MigrationResult.Unmoved.Failed] leaves
 * that resolve on their own and consume no id (`HandshakeNotConfirmed`, `AlreadyInProgress`), so a run
 * of those cannot eat the budget the probes need.
 */
private const val CHEAP_FAILURE_SLACK = 2L

/**
 * How long to wait before attempt number `attempt + 1`, given [attempt] has just failed.
 *
 * 250ms doubling to a 4s ceiling. The floor is a spin guard, sized for the leaves that return
 * *immediately* (`AlreadyInProgress` resolves as the in-flight move completes; `HandshakeNotConfirmed`
 * as the handshake confirms) — for the leaf that matters most, `PathNotValidated`, the attempt has
 * already spent ~3s inside quiche's abandon timer and the wait here is a rounding error on top. The
 * ceiling stops the waits from crowding out the probes: at the default idle timeout they account for
 * 7.75s of the handoff's 25.75s, and the rest is spent inside quiche waiting for an answer. The
 * schedule is walked by [migrationAttemptBudget], so changing it changes how many attempts fit rather
 * than pushing them past the deadline.
 */
private fun backoffBeforeAttempt(attempt: Int): Duration = minOf(250.milliseconds * (1 shl (attempt - 1)), 4.seconds)

/**
 * Wait out [backoff] before re-attempting a migration onto [attempting], and report whether the retry
 * is still the right thing to do.
 *
 * Returns `true` when the backoff elapsed with no better idea available — retry. Returns `false` when
 * a *different* routable, identified link appeared first, which makes this retry stale: that link is
 * new information, the collector is about to be handed it, and it should be migrated onto instead of
 * whatever we were failing to reach. Collecting [NetworkMonitor.state] a second time here is free (it
 * is a `StateFlow`) and is what keeps the backoff from becoming the quiet period this reactor
 * deliberately does not have — a genuine handoff arriving mid-backoff is acted on at once rather than
 * waiting it out.
 *
 * The filters mirror the main pipeline exactly: a link the monitor cannot name, or says traffic will
 * not cross, is not a reason to abandon the retry.
 */
private suspend fun awaitRetrySlot(
    monitor: NetworkMonitor,
    attempting: NetworkId,
    backoff: Duration,
): Boolean =
    withTimeoutOrNull(backoff) {
        monitor.state
            .filter { it.canRouteOffLink }
            .map { it.networkId }
            .first { it != NetworkId.Unidentified && it != attempting }
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
        // move already under way), so they are worth one more bounded ask.
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
