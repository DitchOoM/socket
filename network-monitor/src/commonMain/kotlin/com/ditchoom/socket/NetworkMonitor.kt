package com.ditchoom.socket

import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes platform network availability and exposes it as a [StateFlow].
 *
 * Platform-specific implementations use native APIs for best responsiveness:
 * - **Apple**: `NWPathMonitor` (event-driven)
 * - **Android**: `ConnectivityManager.NetworkCallback` (event-driven)
 * - **JVM desktop**: `NetworkInterface` polling
 * - **Node.js**: `os.networkInterfaces()` polling
 * - **Linux native**: netlink sockets (event-driven)
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

    /** Releases platform resources (unregisters callbacks, closes sockets, cancels polling). */
    fun close()

    companion object {
        /** A no-op monitor that always reports [NetworkAvailability.AVAILABLE]. */
        val AlwaysAvailable: NetworkMonitor =
            object : NetworkMonitor {
                override val availability: StateFlow<NetworkAvailability> =
                    MutableStateFlow(NetworkAvailability.AVAILABLE)

                override fun close() {}
            }

        /** Process-wide override installed via [installProcessDefault]; null → use the platform default. */
        private var installed: NetworkMonitor? = null

        /**
         * Install a process-wide [NetworkMonitor] that every subsystem resolving
         * `NetworkMonitor.processDefault()` (e.g. QUIC auto-migration) will use. Call once at startup.
         *
         * This is the injection seam for platforms whose zero-arg `NetworkMonitor.default()` cannot
         * build a reactive monitor by itself — **Android**, where `ConnectivityManager` needs a
         * `Context`. Android apps call the `Context`-typed
         * `NetworkMonitor.installAndroidContext(applicationContext)` (which routes here); there is no
         * way to obtain a functional Android monitor without a `Context`, so the requirement is enforced
         * by that entry point. Other platforms never need to call this — their `default()` is already
         * functional, so `processDefault()` just works at no extra cost.
         *
         * The installed monitor is caller-owned and long-lived (install one, not one per connection);
         * nothing here closes it.
         */
        fun installProcessDefault(monitor: NetworkMonitor) {
            installed = monitor
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
