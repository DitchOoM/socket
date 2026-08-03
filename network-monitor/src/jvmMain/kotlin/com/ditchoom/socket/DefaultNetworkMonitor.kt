package com.ditchoom.socket

/**
 * JVM actual for [NetworkMonitor.Companion.default].
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
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = defaultJvmNetworkMonitor()
