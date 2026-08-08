package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlin.concurrent.Volatile

/**
 * Observes the platform network and exposes it as a single [StateFlow] of [NetworkState].
 *
 * Platform-specific implementations use native APIs for best responsiveness. Rather than trusting the
 * list below, ask the monitor you were handed: [capability] reports both what pushes changes
 * ([MonitorMechanism]) and which rungs of the link → route → internet ladder it can ever report
 * ([ReachResolution]), as sealed values.
 * - **Apple**: `NWPathMonitor` (event-driven)
 * - **Android**: `ConnectivityManager.NetworkCallback` (event-driven); `NetworkInterface` polling if the
 *   app stripped `NetworkMonitorInitializer` (androidx.startup) from its merged manifest
 * - **JVM desktop, JDK 21+**: FFM routing socket — `AF_NETLINK` on Linux, `PF_ROUTE` on macOS
 *   (event-driven); `NetworkInterface` polling on Windows
 * - **JVM desktop, JDK 8–20**: `NetworkInterface` polling (the base half of the multi-release JAR — no
 *   event-driven network-change API exists in the JDK before FFM)
 * - **Node.js**: `os.networkInterfaces()` polling; **browser JS**: `online`/`offline` (event-driven)
 * - **Linux native**: netlink sockets (event-driven)
 *
 * Scope: this answers *"what network am I on, and how far does it reach"* ([state]) and deliberately
 * never *"which local addresses exist"* — no interface enumeration, no `InetAddress`. Address
 * enumeration is a separate concern with a different lifetime and cost (`../webrtc`'s ICE owns its own
 * enumerator); the name is narrower than it reads.
 *
 * The platform's best default is `NetworkMonitor.default()`, and the process-shared instance is
 * `NetworkMonitor.processDefault()` — both `expect`/extension functions provided by the owning
 * platform module (`com.ditchoom:socket`), because a functional native monitor needs the same
 * platform interop (`LinuxSockets` / `NWHelpers` cinterop) as `:socket`'s sockets. This module holds
 * the portable contract plus the JVM/Android/JS monitors; the native (Linux/Apple) monitors live in
 * `:socket`. Use [AlwaysAvailable] when monitoring is not needed.
 */
interface NetworkMonitor {
    /**
     * The single source of truth: one value, always coherent, updated as the platform detects changes.
     *
     * This replaced separate `availability` and `networkId` flows, which nothing kept coherent —
     * two flows cannot be sampled atomically, one value can (RFC_NETWORK_REACHABILITY §1.2). Read
     * identity off it with [NetworkState.networkId] (a total function) and answer the common questions
     * with [canRouteOffLink] / [supportsLinkLocal] / [needsUserAction] / [isTransient] rather than an
     * exhaustive `when`.
     *
     * A consumer watching for a **path change** (QUIC auto-migration, the transport-selection layer's
     * per-network [com.ditchoom.socket.transport.CapabilityCache] scope) must key on identity, not on
     * the whole value:
     * ```
     * monitor.state.map { it.networkId }.distinctUntilChanged().drop(1)
     * ```
     * Without the [kotlinx.coroutines.flow.distinctUntilChanged] a reachability transition on the *same*
     * network — the ~1s `Pending` → `Confirmed` window — would read as a migration.
     */
    val state: StateFlow<NetworkState>

    /**
     * What this monitor can observe at all — **read once, at configuration time**. Constant for the
     * monitor's lifetime.
     *
     * Read this instead of reflecting on the concrete class or re-deriving the answer from `os.name` and
     * the JDK version: on the JVM alone the resolved monitor is a 2×3 matrix (multi-release JAR — JDK
     * 8–20 polling vs JDK 21+ FFM — crossed with Linux / macOS / Windows), and a re-derivation drifts
     * silently from what this library actually picked.
     *
     * Defaults to `MonitorCapability(MonitorMechanism.Unknown, ReachResolution.LinkOnly)` so
     * third-party monitors written before this property still compile — the same explicit-unknown stance
     * [NetworkId.Unidentified] takes for identity, and deliberately the *least* capable resolution so an
     * undeclared monitor is never over-trusted. Every monitor in this library overrides it.
     */
    val capability: MonitorCapability get() = UndeclaredCapability

    /**
     * Monotonic count of platform observations folded into [state] — **including the ones that dedupe
     * to no visible state change**.
     *
     * [state] is deliberately lossy in one dimension: it is a [StateFlow], so an OS callback whose
     * folded [NetworkState] equals the current value is invisible to every collector. That is correct
     * for consumers reacting to *what* the network is — but it erases *how often the platform is
     * talking*, and callback density is itself a signal: a link about to be lost (weak radio, roaming
     * between access points, a flapping route) typically produces a burst of capability/path chatter
     * before any rung actually changes. This counter preserves that signal: every platform-delivered
     * observation bumps it exactly once, whether or not the folded state changed, so a consumer can
     * sample its rate as an early instability warning — without registering a second OS observer of
     * its own.
     *
     * Interpretation is gated on [capability]:
     *  - [MonitorMechanism.PlatformSignalled] monitors bump per OS callback — the rate is the
     *    platform's own chatter, the intended signal.
     *  - [MonitorMechanism.Polled] monitors leave the **default** (never advances): a poll's cadence
     *    is configuration, not a property of the network, so reporting it as density would be a lie
     *    shaped like a measurement.
     *  - The default (never advances) is also what an implementation predating this property reports —
     *    the same explicit-unknown stance as [UndeclaredCapability]: "not reported", never "quiet".
     *
     * The count is per-monitor, starts at zero, and never resets or goes backwards while the monitor
     * is open. Only deltas and rates are meaningful; the absolute value depends on when the monitor
     * was constructed.
     */
    val observationCount: StateFlow<Long> get() = NoObservations

    /** Releases platform resources (unregisters callbacks, closes sockets, cancels polling). */
    fun close()

    companion object {
        /**
         * A no-op monitor that reports `Routable(Unidentified, Unobserved)` forever.
         *
         * Its capability is `(Static, Asserted)`, which is the point: it **declares that it never looked**
         * ([ReachResolution.Asserted]), so a consumer gating on reachability can refuse to trust it —
         * which it could not detect while this claimed plain availability. A consumer that just wants to
         * opt out of monitoring still gets [canRouteOffLink] `== true` and proceeds.
         */
        val AlwaysAvailable: NetworkMonitor =
            object : NetworkMonitor {
                override val state: StateFlow<NetworkState> =
                    MutableStateFlow(NetworkState.Routable(NetworkId.Unidentified, InternetAccess.Unobserved))

                override val capability: MonitorCapability =
                    MonitorCapability(MonitorMechanism.Static, ReachResolution.Asserted)

                override fun close() {}
            }

        /**
         * Process-wide override installed via [installProcessDefault]; null → use the platform default.
         * Written once at startup and read from any thread resolving `processDefault()`; [Volatile] is
         * the memory-visibility guarantee, and no atomicity is needed because installing is a single
         * reference store.
         */
        @Volatile
        private var installed: NetworkMonitor? = null

        /**
         * Install a process-wide [NetworkMonitor] that every subsystem resolving
         * `NetworkMonitor.processDefault()` (e.g. QUIC auto-migration) will use. Call once at startup.
         *
         * This is the injection seam for overriding the platform default — a test double, or a monitor
         * built from a `Context` an Android app chose itself
         * (`NetworkMonitor.installAndroidContext(applicationContext)` routes here). No platform
         * *requires* it: `NetworkMonitor.default()` is functional everywhere, including Android, where
         * `NetworkMonitorInitializer` supplies the `Context` `ConnectivityManager` needs via
         * androidx.startup before app code runs.
         *
         * The installed monitor is caller-owned and long-lived (install one, not one per connection);
         * nothing here closes it.
         */
        fun installProcessDefault(monitor: NetworkMonitor) {
            installed = monitor
        }

        /**
         * Clears the [installProcessDefault] override, restoring "no override installed".
         *
         * **Test-only.** Installing is one-way by design — an app installs once at startup and every
         * subsystem shares that instance — so there is deliberately no production "uninstall". But a
         * process-global with no way back makes the *absence* case untestable: whichever test ran first
         * decides what every later test observes, and a suite that wants to exercise the un-installed
         * path has no way to reach it. Downstream consumers were resorting to reflection over this
         * module's internals, which breaks silently on a rename — no compile error, just a test that
         * stops testing anything.
         *
         * Does **not** close the monitor being dropped; the caller owns it, as with [installProcessDefault].
         */
        fun resetProcessDefaultForTesting() {
            installed = null
        }

        /**
         * The [installProcessDefault] override, or `null` if none was installed. Read by
         * `NetworkMonitor.processDefault()` (an extension in the owning platform module, `:socket`),
         * which falls back to the shared platform `NetworkMonitor.default()` when this is `null`.
         */
        fun installedProcessDefaultOrNull(): NetworkMonitor? = installed
    }
}

/**
 * The [NetworkMonitor.capability] default for a monitor that does not declare one — unknown mechanism
 * paired with the least capable resolution, so an undeclared monitor is never over-trusted.
 */
private val UndeclaredCapability = MonitorCapability(MonitorMechanism.Unknown, ReachResolution.LinkOnly)

/**
 * The [NetworkMonitor.observationCount] default for a monitor that does not report observation density —
 * a counter that never advances, meaning "not reported", never "the platform is quiet". Shared and
 * effectively immutable: nothing holds a reference through which it could be bumped.
 */
private val NoObservations: StateFlow<Long> = MutableStateFlow(0L)

/**
 * Changes of the **network path itself** — the identity-keyed projection of [NetworkMonitor.state], with
 * the current value dropped so only genuine changes arrive.
 *
 * This is the flow QUIC auto-migration, the per-network
 * [CapabilityCache][com.ditchoom.socket.transport.CapabilityCache] scope, a reconnect-backoff race, and
 * `../webrtc`'s ICE restart policy all actually want. It exists as one function because collapsing
 * availability and identity into a single [NetworkState] made the naive reading *wrong* in a new way: the
 * whole state now changes on reachability transitions too, so a consumer collecting `state` directly
 * would see the ~1s [InternetAccess.Observed.Pending] → [InternetAccess.Observed.Confirmed] window on a
 * single Wi-Fi network as a **migration**, and tear down a perfectly good path. The
 * [kotlinx.coroutines.flow.distinctUntilChanged] on identity is what prevents that, and it should be
 * written once rather than in every consumer.
 *
 * A monitor that cannot identify links reports a constant [NetworkId.Unidentified], so this never emits
 * and every path-change behaviour is inert — the same "no cheap identity" degradation
 * RFC_TRANSPORT_FALLBACK §12 already defines, not a special case.
 *
 * To react to *reachability* rather than identity — waiting out a validation window, surfacing a captive
 * portal — collect [state][NetworkMonitor.state] and branch on [isTransient] / [needsUserAction] instead.
 */
fun NetworkMonitor.pathChanges(): Flow<NetworkId> =
    state
        .map { it.networkId }
        .distinctUntilChanged()
        .drop(1)
