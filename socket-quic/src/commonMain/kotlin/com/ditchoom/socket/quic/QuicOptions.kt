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
 * Unreliable DATAGRAM frame support (RFC 9221). Present (non-null on [QuicOptions.datagrams])
 * means the endpoint advertises `max_datagram_frame_size` and maintains datagram queues.
 *
 * Both queues are bounded; when full, quiche drops the **oldest** datagram — the unreliable,
 * lossy semantics RFC 9221 and WebTransport expect. This is also the backpressure mechanism:
 * a slow receiver simply loses old datagrams rather than growing memory without bound.
 */
data class DatagramOptions(
    /** Max datagrams buffered for receive before quiche drops the oldest. */
    val recvQueueLen: Int = 1024,
    /** Max datagrams buffered for send before quiche drops the oldest. */
    val sendQueueLen: Int = 1024,
) {
    init {
        require(recvQueueLen > 0) { "recvQueueLen must be positive" }
        require(sendQueueLen > 0) { "sendQueueLen must be positive" }
    }
}

/**
 * On the rare platform where a QUIC datagram flow and inbound (peer-initiated) streams cannot
 * coexist on one connection, this decides which to keep. Today the only such platform is Apple's
 * Network.framework: extracting the connection-group datagram flow makes NW deliver inbound *stream*
 * bytes onto that flow, so inbound streams stop being delivered entirely. Everywhere else (quiche on
 * JVM/Linux/Android, and the browser WebTransport object) the two coexist and this is ignored. It is
 * also ignored when [QuicOptions.datagrams] is null.
 *
 * You almost never set this directly: it defaults to [PreferDatagrams] (preserving datagram-only
 * connections), and the HTTP/3 / WebTransport stack forces [PreferStreams] internally because inbound
 * streams are structurally required by HTTP/3 (control + QPACK encoder/decoder are peer-initiated
 * unidirectional streams). It exists only for raw-QUIC callers that deliberately combine datagrams
 * with inbound streams on Apple and must choose.
 */
enum class DatagramStreamConflictPolicy {
    /** Keep the datagram flow; on Apple this suppresses inbound stream delivery. */
    PreferDatagrams,

    /** Keep inbound streams; on Apple the datagram flow is not extracted, so datagrams report unavailable. */
    PreferStreams,
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
    /**
     * Keepalive interval. When set, the endpoint sends an ack-eliciting packet (a PING) after this much
     * inactivity, resetting both peers' idle timers (RFC 9000 §10.1.2) — so an otherwise-idle connection
     * stays alive past [idleTimeout] with no application traffic. Reactive: the PING is scheduled on the
     * connection's own timer inside the driver's event loop, not by polling. Null (the default) disables
     * keepalive. Must be positive and, when [idleTimeout] is non-zero, strictly less than it (a PING after
     * the connection already idled out is useless).
     */
    val keepAliveInterval: Duration? = null,
    /** Maximum UDP payload size (bytes). Must be >= 1200 per RFC 9000. */
    val maxUdpPayloadSize: Int = 1350,
    /** Initial congestion window in packets. Null uses quiche default. */
    val initialCongestionWindowPackets: Long? = null,
    /** Disable active connection migration. */
    val disableActiveMigration: Boolean = false,
    /**
     * Number of connection IDs the endpoint is willing to maintain (RFC 9000 §5.1.1,
     * `active_connection_id_limit`). Must be >= 2 for active migration: the peer issues
     * up to this many NEW_CONNECTION_ID frames, and migrating to a new path consumes one
     * spare destination CID. Default 4 leaves headroom for a few migrations.
     */
    val activeConnectionIdLimit: Long = 4,
    /** Verify the peer's TLS certificate. */
    val verifyPeer: Boolean = true,
    /**
     * Trusted CA certificates (PEM, one `-----BEGIN CERTIFICATE-----` block per entry)
     * to pin as the accepted trust anchors instead of the system trust store. Empty
     * (the default) uses the platform's default trust evaluation.
     *
     * Use this to talk to a server whose chain roots in a private CA (e.g. a local
     * test harness) without installing that CA into the OS keychain.
     *
     * **Platform support:** wired on all targets. On Apple (Network.framework) a pinned
     * anchor drives the CA-pinning `verify_block` and is also Certificate-Transparency-exempt.
     * On the quiche-backed targets (JVM/Android/Linux) the anchors are loaded via
     * `quiche_config_load_verify_locations_from_file`. Supplying anchors forces peer
     * verification on (overriding [verifyPeer] = false), so validation is real chain
     * evaluation against the pinned anchors — not a bypass. (#99)
     */
    val trustedCaCertificatesPem: List<String> = emptyList(),
    /**
     * Pinned server **leaf**-certificate hashes (W3C WebTransport `serverCertificateHashes`). When
     * non-empty, the peer's TLS leaf certificate is accepted iff the hash of its DER encoding matches one
     * of these. Empty (the default) disables leaf-hash pinning. See [certificateHashVerification] for how
     * this combines with chain validation.
     *
     * Unlike [trustedCaCertificatesPem] (CA-anchor pinning), this pins the leaf itself, so it can
     * authenticate a self-signed or short-lived certificate with no CA — the canonical WebTransport use.
     * By default ([certificateHashVerification] = [CertificateHashVerification.HashOnly]) the hash match
     * is the sole trust check, matching the browser.
     *
     * Beyond the hash match, the W3C `serverCertificateHashes` certificate *constraints* (leaf validity
     * <= 14 days, currently within the validity window, ECDSA P-256 key) are enforced on every platform
     * with a native X.509 parser — JVM/Android (`java.security`), Linux (BoringSSL), macOS
     * (Security.framework). iOS/tvOS/watchOS lack a public cert-validity API and so check the leaf hash
     * only. Branch on [serverCertificateConstraintSupport] to see what the current platform enforces.
     */
    val serverCertificateHashes: List<CertificateHash> = emptyList(),
    /**
     * How [serverCertificateHashes] combines with ordinary chain validation. Ignored when
     * [serverCertificateHashes] is empty. Defaults to [CertificateHashVerification.HashOnly] (browser
     * parity — the leaf hash is the sole trust check); set [CertificateHashVerification.RequireBoth] to
     * additionally require the chain to validate (native-only, defense in depth for CA-issued leaves).
     */
    val certificateHashVerification: CertificateHashVerification = CertificateHashVerification.HashOnly,
    /** Enable Path MTU Discovery. */
    val enablePmtuDiscovery: Boolean = false,
    /** Enable 0-RTT early data. */
    val enableEarlyData: Boolean = false,
    /** Enable GREASE (Generate Random Extensions And Sustain Extensibility). */
    val enableGrease: Boolean = true,
    /**
     * Unreliable DATAGRAM frame support (RFC 9221). Null (the default) leaves datagrams disabled,
     * so [QuicScope.sendDatagram] throws and [QuicScope.maxDatagramSize] returns null. Set a
     * [DatagramOptions] to advertise `max_datagram_frame_size` and enable the datagram queues.
     */
    val datagrams: DatagramOptions? = null,
    /**
     * Which to keep when a platform cannot carry a datagram flow and inbound streams on the same QUIC
     * connection — see [DatagramStreamConflictPolicy]. Defaults to [DatagramStreamConflictPolicy.PreferDatagrams]
     * and is ignored when [datagrams] is null or on platforms where both coexist. The HTTP/3 /
     * WebTransport stack overrides this to [DatagramStreamConflictPolicy.PreferStreams] for you.
     */
    val datagramStreamConflictPolicy: DatagramStreamConflictPolicy = DatagramStreamConflictPolicy.PreferDatagrams,
) {
    init {
        require(alpnProtocols.isNotEmpty()) { "QUIC requires at least one ALPN protocol" }
        require(!idleTimeout.isNegative()) { "idleTimeout must be non-negative" }
        keepAliveInterval?.let { ka ->
            require(ka.isPositive()) { "keepAliveInterval must be positive" }
            require(idleTimeout == Duration.ZERO || ka < idleTimeout) {
                "keepAliveInterval ($ka) must be less than idleTimeout ($idleTimeout)"
            }
        }
        require(maxUdpPayloadSize >= 1200) { "maxUdpPayloadSize must be >= 1200 per RFC 9000" }
        require(initialCongestionWindowPackets == null || initialCongestionWindowPackets > 0) {
            "initialCongestionWindowPackets must be positive"
        }
    }
}
