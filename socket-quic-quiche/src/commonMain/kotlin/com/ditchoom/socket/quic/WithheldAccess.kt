package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.udp.DatagramSendError

/** Whether a failed send means the system withholds a kind of network access from this app. */
internal sealed interface WithheldAccess {
    /** An ordinary send failure: loss recovery or migration deals with it. */
    data object None : WithheldAccess

    /** Every send to this peer fails until the app holds [access]; [remedy] says how it gets it. */
    data class Known(
        val access: NetworkAccess,
        val remedy: String,
    ) : WithheldAccess
}

/** What [error] says about this app's network access, on the platforms that can tell. */
internal expect fun withheldAccess(error: DatagramSendError): WithheldAccess
