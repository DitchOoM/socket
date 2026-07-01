package com.ditchoom.socket

/**
 * Android actual for [NetworkMonitor.Companion.default].
 *
 * The reactive [AndroidNetworkMonitor] requires a [android.content.Context]
 * (for `ConnectivityManager`), which cannot be obtained from a zero-arg factory.
 * Callers who want reactive monitoring must construct [AndroidNetworkMonitor]
 * (or use `NetworkMonitor.android(context)`) explicitly; the zero-arg default
 * is the no-op [NetworkMonitor.AlwaysAvailable].
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = NetworkMonitor.AlwaysAvailable
