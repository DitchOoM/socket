package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicTraceCapture
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

/**
 * When a **server** connection may send its CONNECTION_CLOSE after the connection handler returns.
 * Exhaustive — `when` requires handling both cases.
 *
 * RFC 9000 §10.2: once an endpoint sends CONNECTION_CLOSE it enters the closing state and transmits
 * nothing else — including retransmissions. Closing the instant the handler returns therefore makes
 * the last reply *unreliable*: if the datagram carrying it is dropped in flight, the connection that
 * owed the retransmission no longer exists, and the peer's read ends cleanly over data it never got.
 * That is close-after-write truncation, and it is silent — the reply looks sent from both
 * sides of the API.
 */
sealed interface QuicCloseLinger {
    /**
     * Send CONNECTION_CLOSE as soon as the handler returns.
     *
     * Correct only when nothing the handler wrote still needs delivering — a connection that replied
     * nothing, or one whose protocol carries its own acknowledgement. Anything else risks truncation.
     */
    data object Immediate : QuicCloseLinger

    /**
     * Hold the CONNECTION_CLOSE until the connection ends on its own, or at most [bound].
     *
     * The connection keeps running while it lingers: quiche's loss timers stay armed on the driver
     * loop, so a dropped reply datagram is retransmitted exactly as it would be mid-session. The wait
     * ends the moment the connection reaches
     * [com.ditchoom.socket.quic.QuicConnectionState.Closed] — in practice the peer's own
     * CONNECTION_CLOSE, which is the end-to-end evidence that it read what we sent (a peer that has
     * the reply stops needing us and says so), or an idle timeout.
     *
     * [bound] is what keeps a peer that simply vanished from pinning a server connection: when it
     * elapses the close goes out anyway. It is a ceiling, not a delay — a well-behaved exchange
     * finishes in about a round trip and never approaches it.
     */
    data class UntilPeerDone(
        val bound: Duration,
    ) : QuicCloseLinger {
        init {
            require(bound.isPositive()) { "close linger bound must be positive" }
        }
    }

    companion object {
        /**
         * The default: linger up to 3 seconds. Wide enough for several PTO-driven retransmissions on a
         * lossy path, short enough that a vanished peer is reaped promptly.
         */
        val Default: QuicCloseLinger = UntilPeerDone(3.seconds)
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
 * Which to keep on an engine that cannot carry a QUIC datagram flow and inbound (peer-initiated)
 * streams on the same connection.
 */
@Deprecated(
    "No engine reads this: quiche — the QUIC engine on every platform, Apple included — and the " +
        "browser WebTransport object all carry a datagram flow and inbound streams on the same " +
        "connection, so there is nothing to choose between. Removed in 5.0.",
    level = DeprecationLevel.WARNING,
)
enum class DatagramStreamConflictPolicy {
    /** Keep the datagram flow. */
    PreferDatagrams,

    /** Keep inbound streams. */
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
    /**
     * Application-Layer Protocol Negotiation identifiers (RFC 7301) this endpoint offers. Must not be
     * empty.
     *
     * A client connection sending 0-RTT is the one exception: it offers exactly the protocol its session
     * speaks ([QuicResumption.ResumeWithEarlyData]), which this list must contain. So early bytes are
     * read under the protocol they were written for, whatever else is listed here.
     */
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
    /**
     * Read deadline policy for every stream this connection opens or accepts (`QuicByteStream`'s
     * no-arg `read()` — the call shape `CodecConnection` and most protocol layers use). Defaults to
     * `false`: each stream's read is bounded to a fixed 15-second deadline per call — the
     * request/response shape.
     *
     * Set `true` for a **persistent** stream: one carrying a long-lived, continuously-framed protocol
     * (an MQTT-style session, a hand-rolled heartbeat channel) where data legitimately arrives at
     * irregular intervals and the stream has no natural "response" to bound a read by. Under the
     * `false` default such a stream's `read()` throws
     * [kotlinx.coroutines.TimeoutCancellationException] after 15 seconds of *stream-level* silence —
     * even while the connection itself is perfectly healthy. [idleTimeout] and [keepAliveInterval]
     * govern the QUIC *connection*'s idle timer, which a keepalive PING resets indefinitely, but
     * neither one touches this per-stream read bound: a PING carries no stream data, so it never
     * counts as activity on any individual stream. The visible symptom is a connection that tears
     * itself down and redials on a fixed ~15s cadence regardless of [idleTimeout] /
     * [keepAliveInterval] — indistinguishable, from the outside, from the connection idle-timing out
     * early.
     *
     * `true` switches every stream's *read* policy to "wait forever" instead, delegating liveness
     * entirely to the connection's own [idleTimeout] / [keepAliveInterval]. The *write* deadline is
     * unaffected either way — still bounded to 15 seconds per call, matching the precedent already
     * set by the WebTransport streams (`socket-http3`): a write that cannot drain (the peer stopped
     * reading, flow control never reopens) is a real condition worth surfacing as an error, unlike a
     * read simply waiting for the next message on an otherwise-idle persistent stream.
     */
    val persistentStreams: Boolean = false,
    /**
     * **Server-side only**: when an accepted connection may send its CONNECTION_CLOSE after the
     * [QuicScope] handler returns — see [QuicCloseLinger]. Defaults to [QuicCloseLinger.Default]
     * (linger up to 3 seconds), so a reply whose datagram is lost on the wire is still retransmitted
     * instead of dying with the connection.
     *
     * Ignored for the client role, where the application decides when to call
     * [QuicConnection.close] and nothing closes the connection behind its back.
     */
    val closeLinger: QuicCloseLinger = QuicCloseLinger.Default,
    /** Maximum UDP payload size (bytes). Must be >= 1200 per RFC 9000. */
    val maxUdpPayloadSize: Int = 1350,
    /** Initial congestion window in packets. Null uses quiche default. */
    val initialCongestionWindowPackets: Long? = null,
    /**
     * Whether — and by whom — this connection's local path may move (RFC 9000 §9). Defaults to
     * [MigrationPolicy.Automatic], because surviving a network change is the reason to run QUIC over TCP.
     *
     * One decision replacing the `disableActiveMigration` + `autoMigrateOnNetworkChange` pair, whose
     * fourth combination (advertise `disable_active_migration` *and* react to network changes) was a
     * contradiction the constructor resolved silently. See [MigrationPolicy].
     */
    val migration: MigrationPolicy = MigrationPolicy.Automatic,
    /**
     * Which [com.ditchoom.socket.NetworkMonitor] this connection observes — for
     * [MigrationPolicy.Automatic], for [QuicConnection.networkAtClose], and for the trace's NET/NET_CAP
     * lines when [trace] asks for them. Resolved **once per connection** and shared by all three, so the
     * observation sequence they report indexes one stream rather than three unrelated ones.
     *
     * Defaults to [NetworkMonitorSource.ProcessDefault]. Supply [NetworkMonitorSource.Supplied] to
     * override per connection (a test double, or a pre-built Android monitor) — an injected monitor is
     * **owned by you**, nothing here closes it. To observe nothing, supply
     * [com.ditchoom.socket.NetworkMonitor.AlwaysAvailable].
     */
    val networkMonitor: NetworkMonitorSource = NetworkMonitorSource.ProcessDefault,
    /**
     * Number of connection IDs the endpoint is willing to maintain (RFC 9000 §5.1.1,
     * `active_connection_id_limit`). Must be >= 2 for active migration: the peer issues up to this
     * many NEW_CONNECTION_ID frames, and migrating to a new path consumes one spare destination CID.
     *
     * ## What the spare pool is, and when it binds
     * The spares a client holds are `min(this, the peer's limit) - 1` — quiche issues no more source
     * CIDs than the lower of the two advertise — so against a peer at 4 a client at 8 holds three. The
     * pool binds in one situation: a handoff away from a path that is already dead. On a live path each
     * migration retires its old CID and the peer replaces it over the path still carrying traffic, so a
     * connection migrates indefinitely even at the RFC minimum of 2. When the old path is dark that
     * round trip cannot happen and nothing is replenished.
     *
     * An unanswered probe keeps its socket and its CID, and a retry from the same local address probes
     * that path again with the same CID (RFC 9000 §9.5 forbids reuse only across local addresses). A
     * retry binds a fresh socket, spending a spare, only while at least one more stays in reserve for a
     * handoff to another link. So a dead-link handoff probes for as long as the connection lives at any
     * limit, and a larger pool buys fresh 4-tuples for its first retries, not more retries.
     *
     * ## What raising it costs
     * Nothing is preallocated: quiche treats this as a cap (`Slab::with_capacity(1)` for the path
     * table, `VecDeque::with_capacity(1)` for the CID deque), so unused headroom is free. What it does
     * do is size `max_concurrent_paths` from the same value — quiche's own comment is "do not allocate
     * more than the number of active CIDs" — so this is one number for two ceilings, and each recorded
     * path carries its own congestion and loss state. Since these options configure servers as well as
     * clients, the ceiling applies per accepted connection too. As a server's option it is the
     * spare pool of every client that connects, so there is no reason to lower it below 4.
     */
    val activeConnectionIdLimit: Long = 8,
    /** Verify the peer's TLS certificate. */
    val verifyPeer: Boolean = true,
    /**
     * Trusted CA certificates (PEM, one `-----BEGIN CERTIFICATE-----` block per entry)
     * to pin as the accepted trust anchors instead of the default roots.
     *
     * Use this to talk to a server whose chain roots in a private CA (e.g. a local
     * test harness) without installing that CA into a system trust store.
     *
     * **Platform support:** every platform with a QUIC engine, Apple included, verifies the chain in
     * quiche's BoringSSL, and the anchors are loaded into it via
     * `quiche_config_load_verify_locations_from_file`. Each anchor must be a CA certificate
     * (`basicConstraints` `CA:TRUE`). Supplying anchors forces peer verification on (overriding
     * [verifyPeer] = false), so validation is real chain evaluation against the pinned anchors —
     * not a bypass.
     *
     * Empty (the default) verifies against the default roots: the JVM trust store (`cacerts`, or
     * `AndroidCAStore` on Android), the system CA bundle on Linux, `/etc/ssl/cert.pem` on macOS, and
     * a bundled Mozilla root set on iOS. The Apple keychain is never consulted, so MDM-installed
     * roots are not trusted.
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
     * that ships a QUIC engine — JVM/Android (`java.security`), Linux (BoringSSL), and macOS and iOS (a
     * shared structural DER walk, because Apple's only *portable* cert-validity API needs macOS 15 /
     * iOS 18 — above K/N's deployment floor — and the older `SecCertificateCopyValues` is macOS-only, so
     * neither can back a shared Apple implementation).
     * tvOS/watchOS and JS/wasmJs ship no QUIC engine, so nothing here is enforced on them:
     * `connect()` throws before a certificate exists. Branch on [serverCertificateConstraintSupport]
     * rather than on the platform to see what actually runs.
     *
     * **Every platform with a QUIC engine parses the pinned leaf's fields and enforces the
     * constraints**, so a pinned leaf whose hash matches can still be rejected with a
     * [com.ditchoom.socket.CertificateHashPinningException]:
     * `NotTemporallyValid` (expired or not yet valid), `ValidityPeriodTooLong` (validity > 14 days),
     * `UnsupportedPublicKey` (not ECDSA P-256 — including an EC key carrying explicit domain parameters
     * instead of a namedCurve OID, which the walk reads successfully and reports as *not* the named
     * P-256), or `CertificateParseFailed` for a leaf whose DER the shared walk cannot read at all — it
     * accepts the ordinary shapes but not, for example, a UTCTime without seconds or a GeneralizedTime
     * with fractional seconds or a numeric UTC offset. Confirm the pinned leaf is EC P-256, currently
     * valid, and issued for 14 days or less. Callers who pin no hashes are unaffected.
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
    /**
     * **Server-side**: accept 0-RTT from resuming clients, and issue session tickets that permit it
     * (RFC 9001 §4.6.1). Off by default: 0-RTT data can be replayed (RFC 8446 §8, RFC 9001 §9.2), so
     * enable it only for an application protocol whose early requests are safe to process twice.
     *
     * Ignored for the client role, which asks for 0-RTT per connection through [resumption].
     */
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
     * Which to keep on an engine that cannot carry a datagram flow and inbound streams on the same
     * connection — see [DatagramStreamConflictPolicy].
     */
    @Deprecated(
        "No engine reads this: every engine carries a datagram flow and inbound streams on the same " +
            "connection, so there is nothing to choose between. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    val datagramStreamConflictPolicy: DatagramStreamConflictPolicy = DatagramStreamConflictPolicy.PreferDatagrams,
    /** Whether to accept a server certificate flight above an Apple-specific size limit. */
    @Deprecated(
        "Read by no engine: the Apple QUIC server is quiche, the same as on every other platform, " +
            "and applies no Apple-specific limit to the size of its certificate flight. Removed in 5.0.",
        level = DeprecationLevel.WARNING,
    )
    val appleAllowOversizedServerCert: Boolean = false,
    /**
     * Opt-in deterministic-replay trace capture (RFC_DETERMINISTIC_SIMULATION.md §5). Null (the
     * default) disables capture and is byte-identical to the pre-capture path. Set a
     * [QuicTraceCapture] to record this connection's (or server's) QUIC traffic — and, with
     * [QuicTraceCapture.recordNetworkObservations], the connectivity stream [networkMonitor] resolves —
     * onto the supplied
     * [com.ditchoom.socket.testkit.trace.TraceSink] for later replay through the sim harness. Capture
     * errors stay typed: the recorder emits the throwable/`QuicError` class name, never a bare
     * string (see `QuicTraceRecorder`).
     */
    val trace: QuicTraceCapture? = null,
    /**
     * **Client-side**: the session this connection offers — [QuicResumption.None] for a full
     * handshake, or a ticket an earlier connection received ([QuicScope.sessionTicket]), resumed with
     * ([QuicResumption.ResumeWithEarlyData]) or without ([QuicResumption.Resume]) 0-RTT data. The
     * handshake's answer is [QuicScope.resumption].
     *
     * Every attempt of a connect offers it, and a raced connect makes several — so a
     * [QuicResumption.ResumeWithEarlyData] block runs once per attempt. See its documentation.
     *
     * Ignored for the server role.
     */
    val resumption: QuicResumption = QuicResumption.None,
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
