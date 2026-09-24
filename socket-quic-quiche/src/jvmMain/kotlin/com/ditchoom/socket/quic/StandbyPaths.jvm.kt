package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor

/** A desktop host has no standby radio to hold, so [QuicOptions.standbyLink] has nothing to do here. */
internal actual fun standbyPathsFor(
    options: QuicOptions,
    monitor: NetworkMonitor,
    connection: JvmQuicConnection,
): StandbyPaths = StandbyPaths.None
