package com.ditchoom.socket

/**
 * Thrown when [AndroidNetworkMonitor] cannot register its `ConnectivityManager.NetworkCallback` because
 * the app lacks `ACCESS_NETWORK_STATE`.
 *
 * `:network-monitor`'s own manifest declares the permission, so manifest merging grants it to every
 * consuming app by default; reaching this means the app explicitly removed it:
 *
 * ```xml
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove"/>
 * ```
 *
 * This is deliberately loud, unlike the missing-`Context` case (which degrades to
 * [PollingNetworkMonitor], see the Android `NetworkMonitor.default()`). Without the permission the
 * callback is registered-but-never-invoked: [NetworkMonitor.availability] would sit at
 * [NetworkAvailability.UNKNOWN] forever while [NetworkMonitor.mechanism] advertised
 * [MonitorMechanism.PlatformSignalled] — a monitor claiming push while nothing can ever push. A caller
 * that would rather be late than absent can catch this and use [PollingNetworkMonitor], which reads
 * `java.net.NetworkInterface` and needs no permission.
 *
 * `ACCESS_NETWORK_STATE` is a `normal` permission: it is granted at install time and cannot be revoked
 * at runtime, so this either always throws for a given app or never does.
 */
class NetworkMonitorPermissionException(
    message: String =
        "ACCESS_NETWORK_STATE is required to observe network changes. com.ditchoom:network-monitor " +
            "declares it, so this app removed it from the merged manifest. Restore it, or use " +
            "PollingNetworkMonitor(), which needs no permission.",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
