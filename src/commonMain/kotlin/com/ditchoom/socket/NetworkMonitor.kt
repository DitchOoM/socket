package com.ditchoom.socket

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
    }
}

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
