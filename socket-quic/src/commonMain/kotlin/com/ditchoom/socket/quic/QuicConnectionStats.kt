package com.ditchoom.socket.quic

import kotlin.time.Duration

/** What one statistics read of a connection answered. */
sealed interface QuicStatsRead {
    /** The connection's counters and its active path's estimates at the moment of the read. */
    class Read(
        val stats: QuicConnectionStats,
    ) : QuicStatsRead

    /** The connection had ended before the read reached it. */
    data object Ended : QuicStatsRead
}

/**
 * A connection's counters since it began, and its active path, as one immutable value. Packets are QUIC packets;
 * bytes are UDP payload bytes; datagrams are RFC 9221 DATAGRAM frames.
 */
class QuicConnectionStats(
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
    /** The datagrams that were refused or dropped, by where. */
    val datagramDrops: QuicDatagramDrops,
)

/** A connection's RFC 9221 datagrams that did not reach the receiving application, counted by where they stopped. */
class QuicDatagramDrops(
    /** Refused by a send because the send queue was full; [QuicConnectionStats.datagramsSent] counts only those the queue took. */
    val refusedQueueFull: Long,
    /** Received and dropped, oldest first, because the receive queue was full; [QuicConnectionStats.datagramsReceived] counts them. */
    val receiveQueue: Long,
    /** Queued and dropped, oldest first, to make room for a newer send under [DatagramOverflow.EvictOldest]. */
    val evicted: Long,
    /** Queued and dropped unsent because they were older than [DatagramOptions.outgoingMaxAge]. */
    val expiredOutgoing: Long,
    /** Queued and not taken at the head: the path had shrunk below their size, or the connection was ending on an error. */
    val unsendable: Long,
    /** Received and dropped undelivered because they were older than [DatagramOptions.incomingMaxAge]. */
    val expiredIncoming: Long,
    /** Accepted by a send and dropped unsent because the connection reached its close point first. */
    val atClose: Long,
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
