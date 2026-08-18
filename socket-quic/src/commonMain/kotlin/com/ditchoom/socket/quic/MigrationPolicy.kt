package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor

/**
 * Whether — and by whom — this connection's local path may move (RFC 9000 §9 active migration).
 *
 * Replaces two independent booleans on [QuicOptions] (`disableActiveMigration` +
 * `autoMigrateOnNetworkChange`) whose four combinations included one that contradicted itself: setting
 * `disableActiveMigration` **silently forced** auto-migration off, so a caller who asked for both got
 * one of them and no diagnostic. Three cases, one decision, nothing to reconcile.
 */
sealed interface MigrationPolicy {
    /**
     * This endpoint's address is fixed: it never moves its own path, and it says so on the wire.
     * Sets the RFC 9000 §18.2 `disable_active_migration` transport parameter, which tells the **peer**
     * it must not use a new local address either.
     *
     * Both halves are one decision, which is why they are one case: an endpoint that advertises
     * `disable_active_migration` while migrating itself is lying to its peer, and that pair was
     * representable before this type existed.
     *
     * [QuicScope.migrate] answers [MigrationResult.Unmoved.Impossible.PolicyForbids] here.
     */
    data object Forbidden : MigrationPolicy

    /**
     * Migration is permitted, but only the application initiates it — via [QuicScope.migrate].
     * Nothing observes the network on the connection's behalf.
     *
     * On a **server** this is the accurate description of RFC 9000 v1 behaviour (only clients migrate),
     * and it does not advertise `disable_active_migration`, so clients may still migrate to it.
     */
    data object Manual : MigrationPolicy

    /**
     * Migrate automatically when the observed network path changes — the Wi-Fi↔cellular handoff that is
     * the reason to run QUIC over TCP — as well as on explicit [QuicScope.migrate] calls.
     *
     * The connection watches [QuicOptions.networkMonitor]'s identity-keyed path changes. Genuine no-op
     * (and free) wherever the monitor cannot identify the link.
     *
     * **Client-only.** A server-accepted connection has no local client path to move, so this behaves as
     * [Manual] there — the same role-scoping [QuicOptions.closeLinger] already documents in reverse.
     *
     * Takes **no damping parameter**, deliberately. [QuicScope.migrate] suspends until the new path has
     * validated and the active path has switched (or failed), so the reactor cannot start a second
     * migration while one is in flight, and its rate is bounded by path validation rather than by a
     * guessed constant. While it is suspended the monitor's `StateFlow` conflates, so intermediate flaps
     * are dropped and it resumes on whichever link is current then. A quiet period on top could only
     * refuse a genuine handoff arriving inside the window — leaving the connection on a dead path for
     * the remainder of it, which is the outage active migration exists to prevent.
     */
    data object Automatic : MigrationPolicy
}

/**
 * Which [NetworkMonitor] a connection observes — for [MigrationPolicy.Automatic], for
 * [QuicConnection.networkAtClose], and (when [com.ditchoom.socket.quic.trace.QuicTraceCapture]
 * asks) for the trace's NET/NET_CAP lines. One monitor per connection, resolved once at connect and
 * shared by all three, so the observation sequence they report indexes the same stream.
 *
 * Replaces `networkMonitor: NetworkMonitor?`, in which `null` meant *use the process default* rather
 * than the "off" every reader assumed — the meaning was documented, not typed, and the two readings
 * differ by an entire background monitor.
 */
sealed interface NetworkMonitorSource {
    /**
     * [NetworkMonitor.processDefault], resolved **once per connection, at connect**. One process-shared
     * monitor serves every connection; it is created lazily, so it costs at most a single background
     * socket/thread for the whole process.
     *
     * Functional out of the box on every platform that can identify a link, including Android:
     * `ConnectivityManager` needs a `Context`, and `NetworkMonitorInitializer` supplies the application
     * one via androidx.startup before app code runs. An app that strips that initializer from its merged
     * manifest, and does not call `NetworkMonitor.installAndroidContext(applicationContext)` itself, gets
     * [NetworkMonitor.AlwaysAvailable] — which never changes identity, so automatic migration is a clean
     * no-op and [NetworkAtClose.NotObserved] is the honest correlation.
     */
    data object ProcessDefault : NetworkMonitorSource

    /**
     * A caller-owned monitor — a test double, or a pre-built Android one. **Nothing here closes it.**
     *
     * To observe nothing at all, supply [NetworkMonitor.AlwaysAvailable]: there is no `None` case
     * because it would only duplicate a monitor that already exists, and `Automatic` + `None` would be a
     * fresh cross-field contradiction of exactly the kind this type deletes.
     */
    data class Supplied(
        val monitor: NetworkMonitor,
    ) : NetworkMonitorSource
}
