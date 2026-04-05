package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Congestion control algorithm for QUIC connections. */
enum class CongestionControlAlgorithm(
    internal val value: Int,
) {
    RENO(0),
    CUBIC(1),
    BBR2(4),
}

/**
 * QUIC-specific transport configuration.
 * All fields have safe defaults per RFC 9000, but [alpnProtocols] must be specified
 * since QUIC mandates ALPN negotiation.
 */
data class QuicOptions(
    /** Application-Layer Protocol Negotiation identifiers. Must not be empty. */
    val alpnProtocols: List<String>,
    /** Maximum data the peer may send across all streams (bytes). */
    val initialMaxData: Long = 10_485_760,
    /** Max data on a locally-initiated bidirectional stream (bytes). */
    val initialMaxStreamDataBidiLocal: Long = 1_048_576,
    /** Max data on a remotely-initiated bidirectional stream (bytes). */
    val initialMaxStreamDataBidiRemote: Long = 1_048_576,
    /** Max data on a unidirectional stream (bytes). */
    val initialMaxStreamDataUni: Long = 1_048_576,
    /** Maximum concurrent bidirectional streams the peer may open. */
    val initialMaxStreamsBidi: Long = 100,
    /** Maximum concurrent unidirectional streams the peer may open. */
    val initialMaxStreamsUni: Long = 100,
    /** Connection idle timeout. Zero means no timeout. */
    val idleTimeout: Duration = 30.seconds,
    /** Maximum UDP payload size (bytes). Must be >= 1200 per RFC 9000. */
    val maxUdpPayloadSize: Int = 1350,
    /** Disable active connection migration. */
    val disableActiveMigration: Boolean = false,
    /** Verify the peer's TLS certificate. */
    val verifyPeer: Boolean = true,
    /** Enable send pacing (enabled by default in quiche). */
    val enablePacing: Boolean = true,
    /** Maximum pacing rate in bytes/sec. Null means no limit (quiche default). */
    val maxPacingRate: Long? = null,
    /** Congestion control algorithm. Null uses quiche default (CUBIC). */
    val congestionControlAlgorithm: CongestionControlAlgorithm? = null,
    /** Enable HyStart++ for improved slow start. Null uses quiche default (enabled). */
    val enableHystart: Boolean? = null,
    /** Initial congestion window in packets. Null uses quiche default. */
    val initialCongestionWindowPackets: Long? = null,
    /** Maximum connection-level flow control window (bytes). Null uses quiche default. */
    val maxConnectionWindow: Long? = null,
    /** Maximum stream-level flow control window (bytes). Null uses quiche default. */
    val maxStreamWindow: Long? = null,
    /** Enable Path MTU Discovery. Null uses quiche default (disabled). */
    val enablePmtuDiscovery: Boolean? = null,
    /** Enable 0-RTT early data. */
    val enableEarlyData: Boolean = false,
    /** Enable GREASE (Generate Random Extensions And Sustain Extensibility). Null uses quiche default. */
    val enableGrease: Boolean? = null,
) {
    init {
        require(alpnProtocols.isNotEmpty()) { "QUIC requires at least one ALPN protocol" }
        require(initialMaxData >= 0) { "initialMaxData must be non-negative" }
        require(initialMaxStreamDataBidiLocal >= 0) { "initialMaxStreamDataBidiLocal must be non-negative" }
        require(initialMaxStreamDataBidiRemote >= 0) { "initialMaxStreamDataBidiRemote must be non-negative" }
        require(initialMaxStreamDataUni >= 0) { "initialMaxStreamDataUni must be non-negative" }
        require(initialMaxStreamsBidi >= 0) { "initialMaxStreamsBidi must be non-negative" }
        require(initialMaxStreamsUni >= 0) { "initialMaxStreamsUni must be non-negative" }
        require(!idleTimeout.isNegative()) { "idleTimeout must be non-negative" }
        require(maxUdpPayloadSize >= 1200) { "maxUdpPayloadSize must be >= 1200 per RFC 9000" }
        require(maxPacingRate == null || maxPacingRate > 0) { "maxPacingRate must be positive" }
        require(initialCongestionWindowPackets == null || initialCongestionWindowPackets > 0) {
            "initialCongestionWindowPackets must be positive"
        }
        require(maxConnectionWindow == null || maxConnectionWindow > 0) { "maxConnectionWindow must be positive" }
        require(maxStreamWindow == null || maxStreamWindow > 0) { "maxStreamWindow must be positive" }
    }
}
