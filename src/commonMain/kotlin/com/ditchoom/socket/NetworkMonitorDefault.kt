package com.ditchoom.socket

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
 * | Android | `ConnectivityManager.NetworkCallback` (reactive) — the `Context` comes from `NetworkMonitorInitializer` (androidx.startup) |
 * | Wasm (browser) | [NetworkMonitor.AlwaysAvailable] |
 *
 * This lives in `:socket` (not the `com.ditchoom:network-monitor` module that owns the [NetworkMonitor]
 * contract) because the native Linux/Apple monitors need the same platform interop (`LinuxSockets` /
 * `NWHelpers` cinterop) as `:socket`'s sockets, so they stay here and `default()` can construct them
 * directly on every platform.
 *
 * The returned monitor owns platform resources; call [NetworkMonitor.close] when finished.
 */
expect fun NetworkMonitor.Companion.default(): NetworkMonitor

/**
 * The one platform [default] monitor for the whole process, created lazily on first [processDefault]
 * use. A path monitor answers a process-wide question ("what's my network?"), so a single shared
 * instance is all any number of connections need — and it is untouched (zero cost) unless something
 * actually reads [processDefault].
 */
private val platformDefault: NetworkMonitor by lazy { NetworkMonitor.default() }

/**
 * The process default monitor: the [NetworkMonitor.installProcessDefault] override if one was
 * installed, else the shared lazily-created platform [default]. Used by QUIC auto-migration and any
 * other process-level network-aware behavior so they share a single monitor.
 *
 * On Android this is where the `ConnectivityManager.NetworkCallback` is actually registered — App
 * Startup only captures the `Context`, so an app that links the library but never reads
 * [processDefault] pays nothing.
 */
fun NetworkMonitor.Companion.processDefault(): NetworkMonitor = installedProcessDefaultOrNull() ?: platformDefault
