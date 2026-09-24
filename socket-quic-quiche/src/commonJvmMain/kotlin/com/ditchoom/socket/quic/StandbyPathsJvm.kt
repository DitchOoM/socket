package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor

/**
 * The standby link [connection] may move onto under [options] ([QuicOptions.standbyLink]), held for as
 * long as the connection is open. Android holds cellular data attached; the desktop JVM has no standby
 * radio and answers [StandbyPaths.None].
 */
internal expect fun standbyPathsFor(
    options: QuicOptions,
    monitor: NetworkMonitor,
    connection: JvmQuicConnection,
): StandbyPaths
