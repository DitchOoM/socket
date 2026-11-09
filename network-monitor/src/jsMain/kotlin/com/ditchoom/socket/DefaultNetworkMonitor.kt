package com.ditchoom.socket

/**
 * JS actual for [platformNetworkMonitor].
 *
 * [JsNetworkMonitor] is reactive in the browser (`online`/`offline` events) and
 * polls `os.networkInterfaces()` under Node.js, and is zero-arg constructible.
 */
internal actual fun platformNetworkMonitor(): NetworkMonitor = JsNetworkMonitor()

@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = platformNetworkMonitor()
