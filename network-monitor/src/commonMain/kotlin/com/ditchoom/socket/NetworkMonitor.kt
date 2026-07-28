package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes platform network availability and exposes it as a [StateFlow].
 *
 * Platform-specific implementations use native APIs for best responsiveness. Rather than trusting this
 * list, ask the monitor you were handed: [mechanism] reports [MonitorMechanism.PlatformSignalled] vs
 * [MonitorMechanism.Polled] as a sealed value.
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
 * Scope: this answers *"is the network up, and what link am I on"* ([availability] + [networkId]) and
 * deliberately never *"which local addresses exist"* — no interface enumeration, no `InetAddress`.
 * Address enumeration is a separate concern with a different lifetime and cost (`../webrtc`'s ICE owns
 * its own enumerator); the name is narrower than it reads.
 *
 * The platform's best default is `NetworkMonitor.default()`, and the process-shared instance is
 * `NetworkMonitor.processDefault()` — both `expect`/extension functions provided by the owning
 * platform module (`com.ditchoom:socket`), because a functional native monitor needs the same
 * platform interop (`LinuxSockets` / `NWHelpers` cinterop) as `:socket`'s sockets. This module holds
 * the portable contract plus the JVM/Android/JS monitors; the native (Linux/Apple) monitors live in
 * `:socket`. Use [AlwaysAvailable] when monitoring is not needed.
 */
interface NetworkMonitor {
    /** Current network availability, updated as the platform detects changes. */
    val availability: StateFlow<NetworkAvailability>

    /**
     * Typed identity of the current primary network path ([NetworkId], sealed — never a string or
     * null), updated on the same platform callbacks as [availability]. This is the producer for
     * [com.ditchoom.socket.TransportConfig.networkId] and the transport-selection layer's
     * per-network [com.ditchoom.socket.transport.CapabilityCache] scope: wire it as
     * `FallbackTransport(chain, networkId = { monitor.networkId.value })`.
     *
     * Defaults to a constant [NetworkId.Unidentified] — the explicit "no cheap network identity"
     * state (RFC_TRANSPORT_FALLBACK §12) — which is what monitors keep on platforms with no reliable
     * link-kind API (desktop JVM, Linux native, Node.js, Wasm). Overridden with real identity by:
     * - **Apple** — `NWPathMonitor` primary interface type + index → [NetworkId.Link]
     * - **Android** — `ConnectivityManager` transports + `networkHandle` → [NetworkId.Link]
     * - **Browser JS** — `navigator.connection.type` → [NetworkId.KindOnly] (Chromium-only;
     *   [NetworkId.Unidentified] elsewhere)
     */
    val networkId: StateFlow<NetworkId> get() = UnidentifiedNetworkId

    /**
     * How this monitor learns about changes — pushed by the OS ([MonitorMechanism.PlatformSignalled]),
     * re-read on an interval ([MonitorMechanism.Polled]), or never changing at all
     * ([MonitorMechanism.Static]). Constant for the monitor's lifetime.
     *
     * Read this instead of reflecting on the concrete class or re-deriving the answer from `os.name`
     * and the JDK version: on the JVM alone the resolved monitor is a 2×3 matrix (multi-release JAR —
     * JDK 8–20 polling vs JDK 21+ FFM — crossed with Linux / macOS / Windows), and a re-derivation
     * drifts silently from what this library actually picked.
     *
     * Defaults to [MonitorMechanism.Unknown] so third-party monitors written before this property still
     * compile — the same explicit-unknown default [networkId] takes. Every monitor in this library
     * overrides it.
     */
    val mechanism: MonitorMechanism get() = MonitorMechanism.Unknown

    /** Releases platform resources (unregisters callbacks, closes sockets, cancels polling). */
    fun close()

    companion object {
        /** A no-op monitor that always reports [NetworkAvailability.AVAILABLE]. */
        val AlwaysAvailable: NetworkMonitor =
            object : NetworkMonitor {
                override val availability: StateFlow<NetworkAvailability> =
                    MutableStateFlow(NetworkAvailability.AVAILABLE)

                override val mechanism: MonitorMechanism = MonitorMechanism.Static

                override fun close() {}
            }

        /** Process-wide override installed via [installProcessDefault]; null → use the platform default. */
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

/** Shared constant flow for monitors that cannot identify the network (the [NetworkMonitor.networkId] default). */
private val UnidentifiedNetworkId: StateFlow<NetworkId> = MutableStateFlow(NetworkId.Unidentified)
