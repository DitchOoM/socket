package com.ditchoom.socket.quic.core.conn

import android.os.Build
import com.ditchoom.socket.AndroidLocalNetworkAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.androidLocalNetworkAccess
import com.ditchoom.socket.quic.core.udp.attachedPrefixes

/** `ACCESS_LOCAL_NETWORK` is enforced from API 37; older systems let every app reach the local network. */
internal actual val accessGuardOfThisPlatform: AccessGuard =
    if (Build.VERSION.SDK_INT >= AndroidLocalNetworkAccess.ENFORCED_FROM_API) {
        AccessGuard.AndroidPermission(::localNetworkPermission, ::attachedPrefixes)
    } else {
        AccessGuard.Unguarded
    }

/** Read when a send is refused: the user can grant or revoke it at any time. */
private fun localNetworkPermission(): PermissionRead =
    when (NetworkMonitor.androidLocalNetworkAccess()) {
        is AndroidLocalNetworkAccess.Allowed -> PermissionRead.Held
        is AndroidLocalNetworkAccess.Denied -> PermissionRead.Withheld
        is AndroidLocalNetworkAccess.Unknown -> PermissionRead.Unread
    }
