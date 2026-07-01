@file:JvmName("JvmNetworkMonitorSelectorKt")

package com.ditchoom.socket

/**
 * Base (JDK 8–20) network-monitor selector for the JVM.
 *
 * This is the *shadowed* half of a multi-release JAR: the `jvm21Main` source set
 * ships a same-named class under `META-INF/versions/21` that the JVM loads instead
 * on JDK 21+, returning a reactive FFM routing-socket monitor. On older JDKs there
 * is no event-driven network-change API, so we fall back to interface polling.
 */
internal fun defaultJvmNetworkMonitor(): NetworkMonitor = PollingNetworkMonitor()
