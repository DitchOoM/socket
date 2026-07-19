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
 * Each platform provides its own factory on [Companion] because constructor
 * signatures differ (e.g., Android requires `Context`). Use [AlwaysAvailable]
 * when monitoring is not needed.
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

        /** Process-wide override installed via [installProcessDefault]; null → use [platformDefault]. */
        private var installed: NetworkMonitor? = null

        /**
         * The one platform [default] monitor for the whole process, created lazily on first
         * [processDefault] use. A path monitor answers a process-wide question ("what's my network?"),
         * so a single shared instance is all any number of connections need — and it is untouched (zero
         * cost) unless something actually reads [processDefault].
         */
        private val platformDefault: NetworkMonitor by lazy { default() }

        /**
         * Install a process-wide [NetworkMonitor] that every subsystem resolving [processDefault]
         * (e.g. QUIC auto-migration) will use. Call once at startup.
         *
         * This is the injection seam for platforms whose zero-arg [default] cannot build a reactive
         * monitor by itself — **Android**, where `ConnectivityManager` needs a `Context`. Android apps
         * call the `Context`-typed `NetworkMonitor.installAndroidContext(applicationContext)` (which
         * routes here); there is no way to obtain a functional Android monitor without a `Context`, so
         * the requirement is enforced by that entry point. Other platforms never need to call this —
         * their [default] is already functional, so [processDefault] just works at no extra cost.
         *
         * The installed monitor is caller-owned and long-lived (install one, not one per connection);
         * nothing here closes it.
         */
        fun installProcessDefault(monitor: NetworkMonitor) {
            installed = monitor
        }

        /**
         * The process default monitor: the [installProcessDefault] override if one was installed, else
         * the shared lazily-created platform [default]. Used by auto-migration and any other
         * process-level network-aware behavior so they share a single monitor.
         */
        fun processDefault(): NetworkMonitor = installed ?: platformDefault
    }
}

/** Shared constant flow for monitors that cannot identify the network (the [NetworkMonitor.networkId] default). */
private val UnidentifiedNetworkId: StateFlow<NetworkId> = MutableStateFlow(NetworkId.Unidentified)

/**
 * Returns the platform's best default [NetworkMonitor]: reactive and event-driven where a
 * zero-argument construction is possible, and a polling or no-op ([NetworkMonitor.AlwaysAvailable])
 * monitor otherwise.
 *
 * | Platform | Default |
 * |----------|---------|
 * | Apple (iOS/macOS/tvOS/watchOS) | `NWPathMonitor` (reactive) |
 * | Linux native | netlink socket (reactive) |
 * | Desktop JVM, JDK 21+ | FFM routing socket — netlink (Linux) / `PF_ROUTE` (macOS), reactive; polling on Windows |
 * | Desktop JVM, JDK 8–20 | interface polling |
 * | Node.js | interface polling — **browser JS**: `online`/`offline` (reactive) |
 * | Android | [NetworkMonitor.AlwaysAvailable] — reactive monitoring needs a `Context` (use `NetworkMonitor.android(context)`) |
 * | Wasm (browser) | [NetworkMonitor.AlwaysAvailable] |
 *
 * The returned monitor owns platform resources; call [NetworkMonitor.close] when finished.
 */
expect fun NetworkMonitor.Companion.default(): NetworkMonitor
