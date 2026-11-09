package com.ditchoom.socket

/**
 * wasmJs actual for [platformNetworkMonitor].
 *
 * The base TCP socket surface is unavailable in the browser (Wasm), and there is
 * no zero-arg reactive monitor wired for this target yet, so the default is the
 * no-op [NetworkMonitor.AlwaysAvailable]. (`navigator.onLine` could back a reactive
 * monitor later; kept out of scope here to avoid Wasm/JS interop surface.)
 */
internal actual fun platformNetworkMonitor(): NetworkMonitor = NetworkMonitor.AlwaysAvailable

@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = platformNetworkMonitor()
