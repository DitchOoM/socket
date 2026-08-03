package com.ditchoom.socket

/**
 * Apple actual for [NetworkMonitor.Companion.default].
 *
 * [AppleNetworkMonitor] wraps `NWPathMonitor` (event-driven) and is zero-arg
 * constructible, so it is the natural reactive default on every Apple target.
 *
 * Lives in `appleNativeImpl` (not `appleMain`) because it references
 * [AppleNetworkMonitor], which depends on the per-target `NWHelpers` cinterop.
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = AppleNetworkMonitor()
