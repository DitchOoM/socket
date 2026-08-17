package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.canRouteOffLink
import com.ditchoom.socket.networkId
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
 * **`Impossible` cancels; `Failed` does not** — [MigrationResult.Unmoved.Impossible] is by definition
 * the family where every later call answers the same, whatever the network does, so the observer stops.
 * A [MigrationResult.Unmoved.Failed] attempt leaves `attachedTo` alone and keeps watching; it is
 * deliberately **not** retried on a timer, because the next network change is the next new information
 * and re-attempting at a fixed cadence with nothing changed is a spin. A connection left on a dead path
 * is arbitrated by the idle timeout and reported as a typed `QuicCloseReason.ByLocal(IdleTimeout)`.
 *
 * **No quiet period, deliberately.** [QuicScope.migrate] suspends until the new path has validated and
 * the active path has switched (or the attempt has failed), so a second migration cannot start while one
 * is in flight and the rate is bounded by path validation rather than by a guessed constant. While this
 * collector is suspended the upstream `StateFlow` conflates: intermediate flaps are dropped and it
 * resumes on whichever link is current *then*. Coalescing is therefore already present, keyed to the
 * real cost of the operation. A quiet period on top could only refuse a genuine handoff arriving inside
 * the window — leaving the connection on a dead path for the remainder of it, which is precisely the
 * outage active migration exists to prevent.
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
                when (connection.migrate(MigrationTarget.FreshLocalEndpoint)) {
                    is MigrationResult.Succeeded -> attachedTo = id
                    is MigrationResult.Unmoved.Impossible -> {
                        cancel()
                        return@collect
                    }
                    // Not this time — keep watching, and do NOT claim the link we failed to reach.
                    is MigrationResult.Unmoved.Failed -> Unit
                }
            }
    }
}
