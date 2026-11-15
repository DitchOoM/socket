package com.ditchoom.socket.quic

import com.ditchoom.socket.AndroidLocalNetworkAccess
import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.androidLocalNetworkAccess
import com.ditchoom.socket.udp.DatagramSendError

/**
 * A send the kernel's packet filter refused, in an app Android denies the local network to, is that
 * denial: the filter is where Android enforces it, and which addresses count as local is the device's
 * decision, so the refusal itself is the only exact signal.
 */
internal actual fun withheldAccess(error: DatagramSendError): WithheldAccess =
    when (error) {
        is DatagramSendError.NotPermitted ->
            when (NetworkMonitor.androidLocalNetworkAccess()) {
                AndroidLocalNetworkAccess.Denied ->
                    WithheldAccess.Known(
                        NetworkAccess.LocalNetwork,
                        "declare ${AndroidLocalNetworkAccess.PERMISSION} in the manifest and request it at runtime",
                    )
                AndroidLocalNetworkAccess.Allowed, AndroidLocalNetworkAccess.Unknown -> WithheldAccess.None
            }
        else -> WithheldAccess.None
    }
