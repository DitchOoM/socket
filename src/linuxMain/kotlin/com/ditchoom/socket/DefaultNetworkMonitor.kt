package com.ditchoom.socket

/**
 * Linux (Kotlin/Native) actual for [NetworkMonitor.Companion.default].
 *
 * [LinuxNetworkMonitor] is event-driven (netlink) and zero-arg constructible.
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = LinuxNetworkMonitor()
