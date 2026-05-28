package com.ditchoom.socket.quic

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Congestion control algorithm selection. Exhaustive — `when` requires handling all cases. */
sealed interface CongestionControl {
    data object Reno : CongestionControl

    data class Cubic(
        /** Enable HyStart++ for improved slow start exit (default: true). */
        val enableHystart: Boolean = true,
    ) : CongestionControl

    data object Bbr2 : CongestionControl
}

/** Maps sealed [CongestionControl] to quiche's C enum value. */
internal val CongestionControl.quicheValue: Int
    get() =
        when (this) {
            is CongestionControl.Reno -> 0
            is CongestionControl.Cubic -> 1
            is CongestionControl.Bbr2 -> 4
        }

/** Send pacing configuration. Exhaustive — `when` requires handling all cases. */
sealed interface Pacing {
    /** Pacing disabled — packets sent as fast as the congestion window allows. */
    data object Disabled : Pacing

    /** Pacing enabled with no explicit rate limit (quiche default). */
    data object Unlimited : Pacing

    /** Pacing enabled with an explicit maximum rate. */
    @JvmInline
    value class Limited(
        /** Maximum send rate in bytes per second. */
        val maxBytesPerSec: Long,
    ) : Pacing {
        init {
            require(maxBytesPerSec > 0) { "maxBytesPerSec must be positive" }
        }
    }
}

/** Flow control limits for a QUIC connection. */
data class FlowControl(
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
    /** Maximum connection-level flow control window (bytes). Null uses quiche default. */
    val maxConnectionWindow: Long? = null,
    /** Maximum stream-level flow control window (bytes). Null uses quiche default. */
    val maxStreamWindow: Long? = null,
) {
    init {
        require(initialMaxData >= 0) { "initialMaxData must be non-negative" }
        require(initialMaxStreamDataBidiLocal >= 0) { "initialMaxStreamDataBidiLocal must be non-negative" }
        require(initialMaxStreamDataBidiRemote >= 0) { "initialMaxStreamDataBidiRemote must be non-negative" }
        require(initialMaxStreamDataUni >= 0) { "initialMaxStreamDataUni must be non-negative" }
        require(initialMaxStreamsBidi >= 0) { "initialMaxStreamsBidi must be non-negative" }
        require(initialMaxStreamsUni >= 0) { "initialMaxStreamsUni must be non-negative" }
        require(maxConnectionWindow == null || maxConnectionWindow > 0) { "maxConnectionWindow must be positive" }
        require(maxStreamWindow == null || maxStreamWindow > 0) { "maxStreamWindow must be positive" }
    }
}

/**
 * QUIC-specific transport configuration.
 *
 * Uses sealed interfaces for [congestionControl] and [pacing] so `when` expressions
 * are exhaustive — the compiler enforces handling every case.
 *
 * All fields have safe defaults per RFC 9000, but [alpnProtocols] must be specified
 * since QUIC mandates ALPN negotiation.
 */
data class QuicOptions(
    /** Application-Layer Protocol Negotiation identifiers. Must not be empty. */
    val alpnProtocols: List<String>,
    /** Flow control limits. */
    val flowControl: FlowControl = FlowControl(),
    /** Congestion control algorithm and per-algorithm options. */
    val congestionControl: CongestionControl = CongestionControl.Cubic(),
    /** Send pacing configuration. */
    val pacing: Pacing = Pacing.Unlimited,
    /** Connection idle timeout. Zero means no timeout. */
    val idleTimeout: Duration = 30.seconds,
    /** Maximum UDP payload size (bytes). Must be >= 1200 per RFC 9000. */
    val maxUdpPayloadSize: Int = 1350,
    /** Initial congestion window in packets. Null uses quiche default. */
    val initialCongestionWindowPackets: Long? = null,
    /** Disable active connection migration. */
    val disableActiveMigration: Boolean = false,
    /** Verify the peer's TLS certificate. */
    val verifyPeer: Boolean = true,
    /**
     * Path to a PEM-encoded CA certificate that the Apple
     * Network.framework backend pins as the SOLE trust anchor. When
     * set, a custom `sec_protocol_options_set_verify_block` is
     * installed that:
     *
     *  - loads + parses the cert from this path,
     *  - sets it as the only trust anchor via
     *    `SecTrustSetAnchorCertificates` +
     *    `SecTrustSetAnchorCertificatesOnly(true)`,
     *  - applies `SecPolicyCreateSSL(true, NULL)` so the hostname check
     *    is relaxed (lets a `127.0.0.1`-bound test server present a
     *    `quic.tech` cert and still validate),
     *  - calls `SecTrustEvaluateWithError` for real Apple-side trust
     *    evaluation (not the "complete(true) without evaluation"
     *    pattern that SIGABRTs under macOS TLS hardening — see PR #54
     *    iter 1-5).
     *
     * No effect on non-Apple targets — those use quiche or driver-level
     * options for the same concern (and `verifyPeer = false` works
     * there because their TLS backends don't have Apple's hardening).
     * String path (not ByteArray) keeps the no-ByteArray rule in
     * CLAUDE.md intact; the Apple impl reads the PEM into NSData at
     * connection time. Implementation in `nw_quic_helpers.h::nw_helper_create_quic_connection`.
     */
    val pinnedCaCertPath: String? = null,
    /** Enable Path MTU Discovery. */
    val enablePmtuDiscovery: Boolean = false,
    /** Enable 0-RTT early data. */
    val enableEarlyData: Boolean = false,
    /** Enable GREASE (Generate Random Extensions And Sustain Extensibility). */
    val enableGrease: Boolean = true,
) {
    init {
        require(alpnProtocols.isNotEmpty()) { "QUIC requires at least one ALPN protocol" }
        require(!idleTimeout.isNegative()) { "idleTimeout must be non-negative" }
        require(maxUdpPayloadSize >= 1200) { "maxUdpPayloadSize must be >= 1200 per RFC 9000" }
        require(initialCongestionWindowPackets == null || initialCongestionWindowPackets > 0) {
            "initialCongestionWindowPackets must be positive"
        }
    }
}
