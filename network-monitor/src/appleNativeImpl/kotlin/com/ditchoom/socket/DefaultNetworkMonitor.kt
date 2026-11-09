package com.ditchoom.socket

/**
 * Apple actual for [platformNetworkMonitor].
 *
 * [AppleNetworkMonitor] wraps `NWPathMonitor` (event-driven) and is zero-arg
 * constructible, so it is the natural reactive default on every Apple target.
 *
 * Lives in `appleNativeImpl` (not `appleMain`) because it references
 * [AppleNetworkMonitor], which depends on the per-target `NWHelpers` cinterop.
 */
internal actual fun platformNetworkMonitor(): NetworkMonitor = AppleNetworkMonitor()

@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = platformNetworkMonitor()
