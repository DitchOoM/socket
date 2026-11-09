package com.ditchoom.socket

import kotlinx.coroutines.withContext

/**
 * Opens the platform's best default [NetworkMonitor]: reactive and event-driven where a zero-argument
 * construction is possible, and a polling or no-op ([NetworkMonitor.AlwaysAvailable]) monitor otherwise.
 *
 * | Platform | Default |
 * |----------|---------|
 * | Apple (iOS/macOS/tvOS/watchOS) | `NWPathMonitor` (reactive) |
 * | Linux native | netlink socket (reactive) |
 * | Desktop JVM, JDK 21+ | FFM routing socket — netlink (Linux) / `PF_ROUTE` (macOS), reactive; polling on Windows |
 * | Desktop JVM, JDK 8–20 | interface polling |
 * | Node.js | interface polling — **browser JS**: `online`/`offline` (reactive) |
 * | Android | `ConnectivityManager.NetworkCallback` (reactive) — the `Context` comes from `NetworkMonitorInitializer` (androidx.startup) |
 * | Wasm (browser) | [NetworkMonitor.AlwaysAvailable] |
 *
 * Safe to call from any thread, including the Android main thread or a desktop UI thread: starting a
 * monitor can open a routing socket and load a native library, and that work runs on an IO dispatcher,
 * never on the caller's thread. A monitor that finishes starting just as the caller is cancelled is
 * closed, not leaked.
 *
 * The returned monitor owns platform resources; call [NetworkMonitor.close] when finished. To share one
 * monitor across the process instead of owning one, use [shared].
 *
 * This lives in `com.ditchoom:network-monitor`, the module that also owns the [NetworkMonitor] contract
 * and every implementation of it. That is not tidiness — `expect`/`actual` cannot span a module
 * boundary, so the platform factory and all of its actuals must share one module, which is why the module
 * carries its own netlink and `NWPathMonitor` cinterops; `:socket` re-exports all of it via
 * `api(project(":network-monitor"))`.
 */
suspend fun NetworkMonitor.Companion.open(): NetworkMonitor =
    buildOffTheCallersThread(onAbandoned = {
        it.close()
    }) { platformNetworkMonitor() }

/**
 * The process's shared monitor: the [NetworkMonitor.installProcessDefault] override if one was installed,
 * else one platform monitor (see [open]) created on first use and then shared by every caller. A path
 * monitor answers a process-wide question ("what's my network?"), so a single shared instance is all any
 * number of connections need. QUIC auto-migration and any other process-level network-aware behavior use
 * it. Do not close it: the process owns it.
 *
 * Safe to call from any thread: the first call builds the platform monitor on an IO dispatcher; later
 * calls return the existing instance without a thread hop.
 *
 * On Android this is where the `ConnectivityManager.NetworkCallback` is actually registered — App
 * Startup only captures the `Context`, so an app that links the library but never reads [shared] pays
 * nothing.
 */
suspend fun NetworkMonitor.Companion.shared(): NetworkMonitor {
    installedProcessDefaultOrNull()?.let { return it }
    if (platformDefault.isInitialized()) return platformDefault.value
    return withContext(blockingWork) { platformDefault.value }
}

/** The platform's default monitor, built on the calling thread. [open] is the public, non-blocking way in. */
internal expect fun platformNetworkMonitor(): NetworkMonitor

/**
 * Returns the platform's best default [NetworkMonitor], built on the calling thread. See [open], which
 * this is the blocking predecessor of.
 */
@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
expect fun NetworkMonitor.Companion.default(): NetworkMonitor

/** The one platform monitor [shared] hands out when nothing was installed, created lazily on first use. */
private val platformDefault: Lazy<NetworkMonitor> = lazy { platformNetworkMonitor() }

/**
 * The process default monitor, built on the calling thread on first use. See [shared], which this is the
 * blocking predecessor of.
 */
@Deprecated(
    "Blocks the calling thread on first use while it starts the platform monitor (a routing socket, a " +
        "native library load). Call the suspend NetworkMonitor.shared(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.shared()", "com.ditchoom.socket.shared"),
)
fun NetworkMonitor.Companion.processDefault(): NetworkMonitor = installedProcessDefaultOrNull() ?: platformDefault.value
