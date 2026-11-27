package com.ditchoom.socket.quic

/** This driver's connection statistics, read on its loop; [QuicStatsRead.Ended] once the connection is torn down. */
internal suspend fun QuicheDriver.publicStats(): QuicStatsRead =
    when (val read = inspect { api, conn -> api.readStats(conn) }) {
        is Inspected.Read -> read.value
        Inspected.ConnectionGone -> QuicStatsRead.Ended
    }
