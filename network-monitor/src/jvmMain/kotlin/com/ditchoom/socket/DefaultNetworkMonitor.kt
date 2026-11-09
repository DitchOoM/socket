package com.ditchoom.socket

/**
 * JVM actual for [platformNetworkMonitor].
 *
 * Delegates to [defaultJvmNetworkMonitor], which is multi-release-JAR split:
 * - **JDK 8–20**: [PollingNetworkMonitor] (no event-driven network API in the JDK).
 * - **JDK 21+**: a reactive FFM routing-socket monitor on Linux/macOS, polling on
 *   Windows (see the `jvm21Main` override under `META-INF/versions/21`).
 *
 * This file is deliberately *not* part of the shadowed multi-release unit so the
 * `actual` declaration is compiled exactly once; only the plain
 * [defaultJvmNetworkMonitor] selector is version-shadowed.
 */
internal actual fun platformNetworkMonitor(): NetworkMonitor = defaultJvmNetworkMonitor()

@Deprecated(
    "Blocks the calling thread while it starts the platform monitor (a routing socket, a native library " +
        "load). Call the suspend NetworkMonitor.open(), which builds it on an IO dispatcher.",
    ReplaceWith("NetworkMonitor.open()", "com.ditchoom.socket.open"),
)
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = platformNetworkMonitor()
