package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline
import kotlin.jvm.JvmOverloads
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
data class QuicConnectionStats
    @JvmOverloads
    constructor(
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
        /** Datagrams a send refused because the send queue was full; [datagramsSent] counts only those the queue took. */
        val datagramsRefusedQueueFull: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /** Received datagrams dropped, oldest first, because the receive queue was full; [datagramsReceived] counts them. */
        val datagramsDroppedReceiveQueue: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /** Queued datagrams dropped, oldest first, to make room for a newer send under [DatagramOverflow.EvictOldest]. */
        val datagramsEvicted: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /** Queued datagrams dropped unsent because they were older than [DatagramOptions.outgoingMaxAge]. */
        val datagramsExpiredOutgoing: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /**
         * Queued datagrams the engine would not take when they reached the head: the path had shrunk below their size,
         * or the engine refused them with an error that ends the connection.
         */
        val datagramsDroppedUnsendable: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /** Received datagrams dropped undelivered because they were older than [DatagramOptions.incomingMaxAge]. */
        val datagramsExpiredIncoming: QuicDatagramCount = QuicDatagramCount.NotCounted,
        /**
         * Datagrams a send accepted that were dropped unsent because the connection reached its close point first,
         * those already handed to the QUIC stack included.
         */
        val datagramsDroppedAtClose: QuicDatagramCount = QuicDatagramCount.NotCounted,
    )

/** A count of datagrams the engine may not keep: a zero is a measurement only when it is [Counted]. */
sealed interface QuicDatagramCount {
    /** This engine does not count them. */
    data object NotCounted : QuicDatagramCount

    /** The engine counted [datagrams], possibly none. */
    @JvmInline
    value class Counted(
        val datagrams: Long,
    ) : QuicDatagramCount
}

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
