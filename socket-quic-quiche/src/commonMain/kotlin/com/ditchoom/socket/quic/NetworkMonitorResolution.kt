package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.shared

/**
 * The one [NetworkMonitor] a connection observes, resolved from [QuicOptions.networkMonitor] once per
 * connection and shared by every consumer of it (route reactor, trace tap, network observation), so
 * their `ObservationSequence`s index the same stream.
 *
 * Neither case is owned here: [NetworkMonitorSource.Supplied] is the caller's and the process default
 * belongs to whoever installed it, so nothing here closes either.
 */
internal suspend fun resolveNetworkMonitor(source: NetworkMonitorSource): NetworkMonitor =
    when (source) {
        NetworkMonitorSource.ProcessDefault -> NetworkMonitor.shared()
        is NetworkMonitorSource.Supplied -> source.monitor
    }
