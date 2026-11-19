package com.ditchoom.socket.quic

import kotlin.time.Duration

/** What one [QuicScope.stats] read answered. */
sealed interface QuicStatsRead {
    /** The connection's counters and its active path's estimates at the moment of the read. */
    data class Read(
        val stats: QuicConnectionStats,
    ) : QuicStatsRead

    /** The connection had ended before the read reached it. */
    data object Ended : QuicStatsRead

    /** The engine behind this connection does not report statistics. */
    data object NotReported : QuicStatsRead
}

/**
 * A connection's counters since it began, and its active path, as one immutable value. Packets are QUIC packets;
 * bytes are UDP payload bytes; datagrams are RFC 9221 DATAGRAM frames.
 */
data class QuicConnectionStats(
    val packetsSent: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    /** Lost packets later acknowledged after all. */
    val packetsSpuriouslyLost: Long,
    /** Sent packets that carried retransmitted data. */
    val packetsRetransmitted: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val bytesAcknowledged: Long,
    val bytesLost: Long,
    val streamBytesRetransmitted: Long,
    val datagramsSent: Long,
    val datagramsReceived: Long,
    /** Paths the connection knows, validated or not. */
    val paths: Int,
    val activePath: QuicActivePathStats,
)

/** The path the connection sends on, or none while it switches paths. */
sealed interface QuicActivePathStats {
    /** The active path's round-trip estimates (RFC 9002 §5), congestion window, path MTU and delivery rate. */
    data class Measured(
        val smoothedRtt: Duration,
        val minRtt: Duration,
        val maxRtt: Duration,
        val rttVariance: Duration,
        val congestionWindowBytes: Long,
        val pathMtuBytes: Long,
        val deliveryRateBytesPerSecond: Long,
        /** Probe timeouts that fired on this path. */
        val probeTimeouts: Long,
        val packetsSent: Long,
        val packetsReceived: Long,
        val packetsLost: Long,
    ) : QuicActivePathStats

    /** No path reads as active: the connection is between paths. */
    data object BetweenPaths : QuicActivePathStats
}
