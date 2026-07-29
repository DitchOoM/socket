package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.networkId
import com.ditchoom.socket.pathChanges
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Turns the public auto-migration opt-out (QuicOptions.autoMigrateOnNetworkChange, on by default)
 * into a live reactor on the client connection: a NetworkMonitor's path changes become
 * QuicScope.migrate() calls, so a Wi-Fi↔cellular handoff re-homes the QUIC connection with no caller
 * code. The mirror of TraceCaptureWiring's wireClientConnectivityTap — same one-hop shape, wired from
 * the three QuicheEngine actuals' connect() paths (never bind(): a server has no local client path).
 */

/**
 * Unless auto-migration is disabled, launch a child of [connection] that observes the resolved
 * [NetworkMonitor]'s identity-keyed path changes — [pathChanges] with the Unidentified filter applied
 * *before* the baseline drop, see the body — and actively migrates ([QuicScope.migrate] with defaults —
 * a fresh ephemeral socket on the new default interface) on each change to a new link. The collector is
 * a child of the connection scope, so it stops when the connection closes.
 *
 * The monitor is [QuicOptions.networkMonitor] when supplied (caller-owned), else the process-shared
 * [NetworkMonitor.processDefault] (owned by whoever installed/created it) — this function never closes
 * either. On Android that shared default is reactive out of the box (`NetworkMonitorInitializer`
 * supplies the `Context` via androidx.startup); only if an app strips that initializer and installs no
 * `Context` itself does the default fall back to [NetworkMonitor.AlwaysAvailable], whose network
 * identity never changes, making auto-migration a clean no-op (short-circuited below).
 *
 * Trigger contract: [NetworkId.Unidentified] emissions are filtered out (a monitor with no link
 * identity — desktop/Node — never fires, and a link momentarily vanishing is not a migrate target),
 * and the first identified link is the connect-time baseline, not a change. If the connection reports
 * [MigrationResult.Unsupported] (a non-quiche backend), the observer stops — reacting further is
 * pointless. No-op when [QuicOptions.autoMigrateOnNetworkChange] is false or migration is disabled.
 */
internal fun wireAutoMigration(
    quicOptions: QuicOptions,
    connection: QuicConnection,
) {
    if (quicOptions.disableActiveMigration || !quicOptions.autoMigrateOnNetworkChange) return
    val monitor = quicOptions.networkMonitor ?: NetworkMonitor.processDefault()
    // AlwaysAvailable never changes network identity (Android without an installed Context, Wasm) —
    // nothing to observe, so don't even launch a collector.
    if (monitor === NetworkMonitor.AlwaysAvailable) return
    connection.launch {
        // Identity-keyed, exactly like pathChanges() — the dedupe is load-bearing, because the monitor's
        // state also changes when reachability firms up, so collecting it raw would see Android's ~1s
        // Pending → Confirmed window on a single Wi-Fi network as a handoff and migrate a perfectly good
        // path (RFC_NETWORK_REACHABILITY §5, isTransient).
        //
        // Written out rather than reusing pathChanges() for one reason: the filter must come BEFORE the
        // baseline drop. pathChanges() drops the first value it sees, whatever it is; a monitor that has
        // not identified the link yet reports Unidentified first (Apple's NWPathMonitor and the polling
        // JVM monitor are both briefly Unknown after construction — RFC §5), so the baseline drop would
        // be spent on Unidentified and the *first real link* would read as a handoff. A connection
        // opened in that window would migrate itself the instant identity resolved. Filtering first
        // makes the dropped baseline the first **identified** link, which is what this reactor's
        // contract says and what a connect-time baseline actually means.
        monitor.state
            .map { it.networkId }
            .filter { it != NetworkId.Unidentified } // ignore "no/unknown link" states — nothing to migrate onto
            .distinctUntilChanged()
            .drop(1) // the first identified link is the connect-time baseline, not a change
            .collect {
                // migrate() defaults (null host, port 0): re-bind to a fresh ephemeral socket on the
                // new default interface. Succeeded/Failed → keep watching for the next handoff.
                if (connection.migrate() is MigrationResult.Unsupported) {
                    // This connection can't migrate at all (non-quiche backend); stop this observer.
                    cancel()
                }
            }
    }
}
