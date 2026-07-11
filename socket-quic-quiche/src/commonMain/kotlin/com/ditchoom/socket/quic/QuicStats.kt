package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * Per-path statistics snapshot — the typed view of quiche's `quiche_path_stats` struct
 * (`quiche_conn_path_stats`), bound for RFC_DETERMINISTIC_SIMULATION.md §5.1 item 5: the
 * observation channel that lets a recorded trace assert the driver *responded* to loss/RTT
 * the same way on replay.
 *
 * Field subset (documented omissions from the C struct):
 *  - `local_addr`/`peer_addr` sockaddrs — path identity is already carried as [PathKey] at the
 *    `UdpChannel` seam; duplicating raw sockaddr bytes here would re-open the ByteArray door.
 *  - `dgram_recv`/`dgram_sent` — connection-level datagram counters are on [QuicConnStats].
 *  - `max_bandwidth`/`startup_exit_cwnd` — CC-tuning diagnostics, not needed by the trace tier.
 */
data class QuicPathStats(
    /** Raw `validation_state` (ssize_t) — quiche's path validation progress. */
    val validationState: Long,
    /** Whether this path is active. */
    val active: Boolean,
    /** QUIC packets received on this path. */
    val recv: Long,
    /** QUIC packets sent on this path. */
    val sent: Long,
    /** QUIC packets lost on this path. */
    val lost: Long,
    /** Sent QUIC packets with retransmitted data on this path. */
    val retrans: Long,
    /** Times the PTO (probe timeout) fired — the normalized loss-event metric. */
    val totalPtoCount: Long,
    /** Estimated round-trip time of the path. */
    val rtt: Duration,
    /** Minimum observed round-trip time. */
    val minRtt: Duration,
    /** Maximum observed round-trip time. */
    val maxRtt: Duration,
    /** Estimated round-trip time variation. */
    val rttvar: Duration,
    /** Congestion window, bytes. */
    val cwnd: Long,
    /** Bytes sent on this path. */
    val sentBytes: Long,
    /** Bytes received on this path. */
    val recvBytes: Long,
    /** Bytes lost on this path. */
    val lostBytes: Long,
    /** Stream bytes retransmitted on this path. */
    val streamRetransBytes: Long,
    /** Current PMTU for the path. */
    val pmtu: Long,
    /** Most recent delivery-rate estimate, bytes/s. */
    val deliveryRate: Long,
)

/**
 * Connection-level statistics snapshot — the typed view of quiche's `quiche_stats` struct
 * (`quiche_conn_stats`).
 *
 * Field subset (documented omissions from the C struct):
 *  - reset/stopped stream counts, `*_blocked_*` frame counters, `path_challenge_rx_count`,
 *    `bytes_in_flight_duration_msec`, `tx_buffered_inconsistent` — flow-control/CC diagnostics
 *    outside the trace tier's needs; add when a consumer asserts on them.
 *  - peer transport parameters (`peer_max_idle_timeout`, `peer_max_udp_payload_size`, …) live in
 *    a *different* quiche struct/API (`quiche_conn_peer_transport_params`), deliberately not bound
 *    in this slice — the negotiated values the driver acts on are already visible through the
 *    config/driver seams.
 */
data class QuicConnStats(
    /** QUIC packets received on this connection. */
    val recv: Long,
    /** QUIC packets sent on this connection. */
    val sent: Long,
    /** QUIC packets lost. */
    val lost: Long,
    /** Packets marked lost but later acked. */
    val spuriousLost: Long,
    /** Sent packets carrying retransmitted data. */
    val retrans: Long,
    /** Bytes sent. */
    val sentBytes: Long,
    /** Bytes received. */
    val recvBytes: Long,
    /** Bytes acked. */
    val ackedBytes: Long,
    /** Bytes lost. */
    val lostBytes: Long,
    /** Stream bytes retransmitted. */
    val streamRetransBytes: Long,
    /** DATAGRAM frames received. */
    val dgramRecv: Long,
    /** DATAGRAM frames sent. */
    val dgramSent: Long,
    /** Known paths for the connection (index bound for [QuicheApi.connPathStats]). */
    val pathsCount: Long,
)

/**
 * One on-loop stats read ([QuicheDriver.stats]): connection-level plus active-path stats, either
 * `null` on a backend that has not bound the stats FFI (or once the connection is torn down).
 */
data class QuicStatsSnapshot(
    val connStats: QuicConnStats?,
    val pathStats: QuicPathStats?,
)
