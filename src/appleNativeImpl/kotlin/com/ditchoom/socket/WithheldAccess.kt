package com.ditchoom.socket

/** Whether Network.framework finds no path because the user withholds a kind of access from this app. */
internal sealed interface WithheldAccess {
    /** The path is satisfied, or unsatisfied for a reason that is not a user's setting. */
    data object None : WithheldAccess

    data class Known(
        val access: NetworkAccess,
    ) : WithheldAccess {
        fun connectFailure(hostname: String): SocketConnectionException =
            SocketConnectionException.Other(ConnectionFailureReason.AccessDenied(access), "$hostname: ${access.description}")
    }
}

/** [reason] is an `nw_path_unsatisfied_reason_t`; 4 (a required VPN is inactive) is a missing route. */
internal fun withheldAccess(reason: Int): WithheldAccess =
    when (reason) {
        NW_UNSATISFIED_CELLULAR_DENIED -> WithheldAccess.Known(NetworkAccess.CellularData)
        NW_UNSATISFIED_WIFI_DENIED -> WithheldAccess.Known(NetworkAccess.WifiData)
        NW_UNSATISFIED_LOCAL_NETWORK_DENIED -> WithheldAccess.Known(NetworkAccess.LocalNetwork)
        else -> WithheldAccess.None
    }

internal const val NW_UNSATISFIED_CELLULAR_DENIED = 1
internal const val NW_UNSATISFIED_WIFI_DENIED = 2
internal const val NW_UNSATISFIED_LOCAL_NETWORK_DENIED = 3
