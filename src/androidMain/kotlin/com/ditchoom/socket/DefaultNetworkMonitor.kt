package com.ditchoom.socket

/**
 * Android actual for [NetworkMonitor.Companion.default].
 *
 * The reactive [AndroidNetworkMonitor] requires a [android.content.Context]
 * (for `ConnectivityManager`), which cannot be obtained from a zero-arg factory,
 * so this zero-arg default is the no-op [NetworkMonitor.AlwaysAvailable].
 *
 * To make reactive monitoring (and QUIC auto-migration) work on Android, install a
 * `Context`-backed monitor once at startup via
 * [NetworkMonitor.installAndroidContext][installAndroidContext] — that becomes the
 * [NetworkMonitor.processDefault] the rest of the library uses.
 */
actual fun NetworkMonitor.Companion.default(): NetworkMonitor = NetworkMonitor.AlwaysAvailable
