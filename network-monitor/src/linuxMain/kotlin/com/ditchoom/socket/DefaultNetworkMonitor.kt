package com.ditchoom.socket

/**
 * Linux (Kotlin/Native) actual for [platformNetworkMonitor].
 *
 * [LinuxNetworkMonitor] is event-driven (netlink) and zero-arg constructible.
 */
internal actual fun platformNetworkMonitor(): NetworkMonitor = LinuxNetworkMonitor()

@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = platformNetworkMonitor()
