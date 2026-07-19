package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/*
 * Turns the public auto-migration opt-out (QuicOptions.autoMigrateOnNetworkChange, on by default)
 * into a live reactor on the client connection: a NetworkMonitor's networkId changes become
 * QuicScope.migrate() calls, so a Wi-Fi↔cellular handoff re-homes the QUIC connection with no caller
 * code. The mirror of TraceCaptureWiring's wireClientConnectivityTap — same one-hop shape, wired from
 * the three QuicheEngine actuals' connect() paths (never bind(): a server has no local client path).
 */

/**
 * Unless auto-migration is disabled, launch a child of [connection] that observes the resolved
 * [NetworkMonitor]'s [NetworkMonitor.networkId] and actively migrates ([QuicScope.migrate] with
 * defaults — a fresh ephemeral socket on the new default interface) on each change to a new link. The
 * collector is a child of the connection scope, so it stops when the connection closes.
 *
 * The monitor is [QuicOptions.networkMonitor] when supplied (caller-owned), else the process-shared
 * [NetworkMonitor.processDefault] (owned by whoever installed/created it) — this function never closes
 * either. On Android that shared default is functional only if the app installed a `Context` via
 * `NetworkMonitor.installAndroidContext`; without it the default is [NetworkMonitor.AlwaysAvailable],
 * whose network identity never changes, so auto-migration is a clean no-op (short-circuited below).
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
        monitor.networkId
            .filter { it != NetworkId.Unidentified } // ignore "no/unknown link" states — nothing to migrate onto
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
