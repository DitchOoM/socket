package com.ditchoom.socket

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether Android lets this app exchange packets with local-network addresses.
 *
 * From Android 17 (API 37) an app that targets API 37 or higher reaches the local network only while it
 * holds the `ACCESS_LOCAL_NETWORK` runtime permission. Without it the system drops the app's local
 * traffic in both directions: a UDP send fails with `EPERM` and a TCP connect times out.
 */
sealed interface AndroidLocalNetworkAccess {
    /** The restriction does not apply (an older system or target SDK) or the permission is granted. */
    data object Allowed : AndroidLocalNetworkAccess

    /** The app targets API 37+ on Android 17+ and does not hold [PERMISSION]. */
    data object Denied : AndroidLocalNetworkAccess

    /** No application `Context` was captured, so the permission cannot be read. */
    data object Unknown : AndroidLocalNetworkAccess

    companion object {
        /** The manifest permission an app declares and requests at runtime to reach the local network. */
        const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

        /** The first API level that enforces the permission, for apps targeting it. */
        const val ENFORCED_FROM_API: Int = 37
    }
}

/** This app's local-network access right now; the user can grant or revoke it at any time. */
fun NetworkMonitor.Companion.androidLocalNetworkAccess(): AndroidLocalNetworkAccess =
    when (val context = androidApplicationContext()) {
        null -> AndroidLocalNetworkAccess.Unknown
        else -> context.localNetworkAccess()
    }

private fun Context.localNetworkAccess(): AndroidLocalNetworkAccess {
    val enforced =
        Build.VERSION.SDK_INT >= AndroidLocalNetworkAccess.ENFORCED_FROM_API &&
            applicationInfo.targetSdkVersion >= AndroidLocalNetworkAccess.ENFORCED_FROM_API
    return when {
        !enforced -> AndroidLocalNetworkAccess.Allowed
        checkSelfPermission(AndroidLocalNetworkAccess.PERMISSION) == PackageManager.PERMISSION_GRANTED ->
            AndroidLocalNetworkAccess.Allowed
        else -> AndroidLocalNetworkAccess.Denied
    }
}
