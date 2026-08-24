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
 * That is close-after-write truncation (issue #321), and it is silent — the reply looks sent from both
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
     * instead of dying with the connection (issue #321).
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
     * ## Why 8, measured rather than guessed
     * The spare pool is exactly this value minus the one in use, and it binds in **one** situation:
     * a handoff away from a path that is already dead. On a live path it does not bind at all —
     * each migration retires its old CID and the peer replaces it over the path still carrying
     * traffic, so a connection migrates indefinitely even at the RFC minimum of 2 (measured in the
     * deterministic migration sim: 40 consecutive migrations at every limit from 2 to 32). When the
     * old path is dark that round trip cannot happen, nothing is replenished, and every unanswered
     * probe costs a CID that never comes back.
     *
     * That is the #453 case, and the reactor's retry budget is what the pool has to feed. Measured
     * against a dead old path with every probe unanswered, and the automatic reactor driving:
     *
     * | limit | spares held | probes that reached the wire | reactor gave up at |
     * |-------|-------------|------------------------------|--------------------|
     * | 2     | 1           | 1                            | 10.75s             |
     * | 4     | 3           | 3                            | 16.75s             |
     * | 8     | 7           | 6                            | 25.75s             |
     * | 16    | 15          | 6                            | 25.75s             |
     *
     * At the old default of 4, half the retry budget was spent on attempts quiche refused for want of
     * a spare CID *before* opening a socket — real answers, but not real probes — and the reactor gave
     * up with a third of the [idleTimeout] window unused. At 8 the pool stops being the binding
     * constraint and every attempt is a probe that actually reaches the network. Above 8 nothing
     * changes, because the reactor's own deadline binds first.
     *
     * ## What raising it costs
     * Nothing is preallocated: quiche treats this as a cap (`Slab::with_capacity(1)` for the path
     * table, `VecDeque::with_capacity(1)` for the CID deque), so unused headroom is free. What it does
     * do is size `max_concurrent_paths` from the same value — quiche's own comment is "do not allocate
     * more than the number of active CIDs" — so this is one number for two ceilings, and each recorded
     * path carries its own congestion and loss state. Since these options configure servers as well as
     * clients, the ceiling applies per accepted connection too. Raise it if [idleTimeout] is long
     * enough for a handoff to spend more than 7 probes; there is no reason to lower it below 4.
     */
    val activeConnectionIdLimit: Long = 8,
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
     * that ships a QUIC engine — JVM/Android (`java.security`), Linux (BoringSSL), and macOS and iOS (a
     * shared structural DER walk, because Apple's only *portable* cert-validity API needs macOS 15 /
     * iOS 18 — above K/N's deployment floor — and the older `SecCertificateCopyValues` is macOS-only, so
     * neither can back a shared Apple implementation).
     * tvOS/watchOS and JS/wasmJs ship no QUIC engine, so nothing here is enforced on them:
     * `connect()` throws before a certificate exists. Branch on [serverCertificateConstraintSupport]
     * rather than on the platform to see what actually runs.
     *
     * **Behaviour change on Apple (macOS/iOS), issue #339.** Apple previously never parsed the pinned
     * leaf's fields, so *any* certificate whose hash matched a pin connected. The constraints are now
     * enforced there too, which means a pinned leaf that used to connect on Apple can now be rejected
     * with a [com.ditchoom.socket.CertificateHashPinningException]:
     * `NotTemporallyValid` (expired or not yet valid), `ValidityPeriodTooLong` (validity > 14 days),
     * `UnsupportedPublicKey` (not ECDSA P-256 — including an EC key carrying explicit domain parameters
     * instead of a namedCurve OID, which the walk reads successfully and reports as *not* the named
     * P-256), or `CertificateParseFailed` for a leaf whose DER the shared walk cannot read at all — it
     * accepts the ordinary shapes but not, for example, a UTCTime without seconds or a GeneralizedTime
     * with fractional seconds or a numeric UTC offset. Every other platform already
     * behaved this way, so an Apple-only pin is the case to check: confirm the pinned leaf is EC P-256,
     * currently valid, and issued for 14 days or less. Nothing changed for callers who pin no hashes.
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
    /**
     * Apple-only escape hatch for the Network.framework QUIC **server** anti-amplification guard.
     *
     * Apple's libquic under-credits a non-Apple client's first flight for the RFC 9000 §8.1
     * anti-amplification limit, so an oversized server certificate flight (notably an RSA-2048 leaf)
     * can never be delivered and the handshake deadlocks against quiche/Chrome. To fail loud instead of
     * silently timing out, the Apple server [bind] estimates the leaf's TLS flight at bind time and
     * throws when it exceeds NW's budget unless this is true. Default false (guard ON) — keep it and
     * present a small EC (ECDSA P-256) leaf for out-of-the-box interop. Set true only when the server
     * will serve **Apple clients exclusively** (Apple↔Apple is unaffected) or you have another reason to
     * accept the deadlock risk. Ignored on every non-Apple target and for the client role (those don't
     * have the bug). See the limitation note on `buildAppleQuicServer` / [QuicTlsConfig.pkcs12Path].
     */
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
