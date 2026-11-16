package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicOptions

/**
 * How a core endpoint sizes its datagrams. The core's sockets set DF, so [Probed] starts at the 1200 bytes every path
 * carries and grows toward the options' maximum as probes are acknowledged; [Configured] follows the options as given.
 */
internal enum class DatagramSize {
    Probed,
    Configured,
}

internal fun QuicOptions.sizedBy(size: DatagramSize): QuicOptions =
    when (size) {
        DatagramSize.Probed -> copy(enablePmtuDiscovery = true)
        DatagramSize.Configured -> this
    }
