@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.toNativeData
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_group
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_datagram_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_datagram_flow
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_stream
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_uni_stream
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_new_connection_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_max_datagram_size
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_receive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_reset_stream
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_keepalive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_stream_application_error
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Network.nw_connection_group_t
import platform.Network.nw_connection_t
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Process-wide lock serializing QUIC connection-group *establishment* (issue #112).
 *
 * Network.framework returns POSIX ENOMEM for a QUIC group handshake while another group is
 * already establishing (reproduced with two concurrent connections to both an in-process
 * listener and a public endpoint). Established groups coexist fine, so the lock is held only
 * for the handshake and released before the caller's block runs.
 */
private val appleQuicEstablishMutex = Mutex()

/**
 * The single Apple QUIC connect path, over a multiplex [nw_connection_group_t].
 *
 * Issue #109 migrated the former single-[nw_connection_t] path onto the group so one
 * QUIC connection can carry real per-stream flows AND — when [QuicOptions.datagrams] is
 * set — a dedicated datagram flow (RFC 9221), which Network.framework models as a
 * separate flow rather than on the byte-stream path.
 *
 *  1. Create the group (advertising `max_datagram_frame_size` only when datagrams are
 *     enabled) and await group `ready` — the QUIC handshake, including the #81
 *     CA-pinning verify_block.
 *  2. When datagrams are enabled, extract the one datagram flow (macOS 13 / iOS 16) and
 *     await its `ready` so [nw_helper_quic_max_datagram_size] reflects the negotiated
 *     path MTU before the user's block runs. Otherwise no datagram flow is extracted and
 *     the connection reports datagrams as [MaxDatagramSize.Unavailable].
 *  3. Return an established [AppleQuicGroupConnection]; streams are extracted lazily. The
 *     [withQuicConnection] wrapper owns the block + close lifecycle.
 */
internal suspend fun connectQuicGroup(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: TransportConfig,
    timeout: Duration,
): AppleQuicGroupConnection {
    // Build ALPN array — pass as List which K/N bridges to NSArray.
    val alpnList: List<Any?> = quicOptions.alpnProtocols

    // Pinned-CA trust anchors (issue #81): PEM → DER NSData, bridged to NSArray<NSData *>.
    // Empty → null so the native helper keeps NW's default system trust. See
    // nw_quic_helpers.h for the verify_block.
    val caDerList: List<NSData>? =
        quicOptions.trustedCaCertificatesPem
            .flatMap { pemToDerCertificates(it) }
            .ifEmpty { null }

    // W3C serverCertificateHashes leaf-hash pinning (Option 1): each CertificateHash.value is a 32-byte
    // SHA-256 → NSData (non-consuming), bridged to NSArray<NSData *>. The verify_block in
    // nw_quic_helpers.h hashes the peer's leaf DER (CC_SHA256) and compares. Empty → null (no pinning).
    // RequireBoth additionally demands the chain validate; HashOnly makes the hash the sole trust check.
    val serverCertHashes: List<NSData>? =
        quicOptions.serverCertificateHashes
            .map { it.value.toNativeData().nsData }
            .ifEmpty { null }
    val requireChain = quicOptions.certificateHashVerification == CertificateHashVerification.RequireBoth

    val datagramsEnabled = quicOptions.datagrams != null
    val maxFrameSize: UShort = if (datagramsEnabled) DATAGRAM_FRAME_SIZE_MAX else 0u

    // Out-params the leaf-hash verify_block writes on rejection (only allocated when pinning is active):
    // a reason code (1 = HashMismatch, 2 = NoPeerCertificate; 0 = not a pin failure) and the 32-byte
    // computed leaf digest (for HashMismatch). Read in the `cancelled` state below to throw a typed
    // CertificateHashPinningException matching the quiche backends; freed once the handshake resolves
    // (the verify_block is handshake-time only, so it can't fire after).
    val pinFailureOut = if (serverCertHashes != null) nativeHeap.alloc<IntVar>().apply { value = 0 } else null
    val pinHashOut = if (serverCertHashes != null) nativeHeap.allocArray<UByteVar>(SHA256_DIGEST_BYTES) else null

    // On a successful hash match the verify_block also hands back the matched leaf's full DER (snprintf-
    // style into this buffer + length), so this side can run the W3C `serverCertificateHashes` certificate
    // constraints (validity ≤ 14 days, currently valid, ECDSA P-256) post-handshake via Security.framework
    // — but only where this platform reports them enforceable (macOS; see serverCertificateConstraintSupport).
    // A real leaf DER is < 4 KiB; the buffer is generous and an overflow fails closed.
    val pinLeafDerOut = if (serverCertHashes != null) nativeHeap.allocArray<UByteVar>(PINNED_LEAF_DER_CAPACITY) else null
    val pinLeafDerLenOut = if (serverCertHashes != null) nativeHeap.alloc<IntVar>().apply { value = 0 } else null

    fun freePinOutParams() {
        pinFailureOut?.let { nativeHeap.free(it) }
        pinHashOut?.let { nativeHeap.free(it) }
        pinLeafDerOut?.let { nativeHeap.free(it) }
        pinLeafDerLenOut?.let { nativeHeap.free(it) }
    }

    val group =
        (
            try {
                nw_helper_create_quic_group(
                    hostname,
                    port.toUShort(),
                    alpnList,
                    NSNumber(bool = quicOptions.verifyPeer),
                    caDerList,
                    serverCertHashes,
                    NSNumber(bool = requireChain),
                    pinFailureOut?.ptr,
                    pinHashOut,
                    pinLeafDerOut,
                    PINNED_LEAF_DER_CAPACITY,
                    pinLeafDerLenOut?.ptr,
                    quicOptions.idleTimeout.inWholeSeconds.toInt(),
                    quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0,
                    timeout.inWholeSeconds.toInt(),
                    maxFrameSize,
                )
            } catch (t: Throwable) {
                freePinOutParams()
                throw t
            }
        ) ?: run {
            freePinOutParams()
            throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create QUIC connection group")
        }

    // Peer-initiated streams land here via the group's new-connection handler (wired below,
    // before start) and are drained by acceptStream()/streams(). Declared here so the handler
    // — which the API requires to be set before nw_helper_quic_group_start — can target it.
    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    val acceptedStreamId = kotlin.concurrent.AtomicLong(1L) // synthetic fallback id (used only if the real id is unreadable)
    // Peer-stream acceptance peeks the first read (to filter NW's phantom stream AND to make the flow
    // live so the REAL stream id — hence the correct directionality bit — is readable), which suspends,
    // so it can't run on NW's serial callback queue. This scope runs it; it's cancelled on connection
    // teardown (see AppleQuicGroupConnection.close/onTransportClosed).
    val streamAcceptScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    val acceptKeepAliveSeconds = quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0

    // Durable state bridge: NW keeps delivering group state changes for the connection's whole
    // life, not just the handshake. The bridge resolves the establish await on the first terminal
    // event, then — once the live connection is attached — drives a *post-handshake* failure or
    // cancellation into [AppleQuicGroupConnection], unblocking parked readers / acceptStream().
    // This is the Apple analog of the quiche driver's StateFlow being the single source of truth;
    // before it, every state change after `ready` was silently dropped (a mid-session peer close
    // left _state Established and acceptStream() hanging until the caller's own timeout).
    //
    // The cancelled (state 4) establish failure is pin-failure-aware: a leaf-hash pin rejection
    // cancels the group from the verify_block (NW reports no other signal), so its reason out-param
    // is mapped to the SAME typed CertificateHashPinningException the quiche backends throw. This
    // lambda is evaluated ONLY pre-`ready` (a post-`ready` cancel drives onTransportClosed with the
    // generic reason and never reads the pin out-params, which are freed once establish resolves).
    val stateBridge =
        AppleQuicGroupStateBridge(
            hostname,
            port,
            cancelledEstablishException = {
                when (pinFailureOut?.value ?: 0) {
                    1 ->
                        CertificateHashPinningException(
                            CertificateHashPinningFailure.HashMismatch(
                                quicOptions.serverCertificateHashes.size,
                                "sha-256:" + (pinHashOut?.let { computedHashHex(it) } ?: ""),
                            ),
                        )
                    2 -> CertificateHashPinningException(CertificateHashPinningFailure.NoPeerCertificate)
                    else -> QuicCloseException(QuicError.NoError, "QUIC group cancelled")
                }
            },
        )

    // Wait for the group (QUIC handshake) to become ready (resolved via the bridge below).
    //
    // Serialized process-wide via [appleQuicEstablishMutex]: Network.framework fails a QUIC
    // group handshake with POSIX ENOMEM ("Cannot allocate memory") whenever another group is
    // already establishing — reproduced with as few as two concurrent connections against both
    // an in-process listener AND a public endpoint (cloudflare-quic.com). Already-established
    // groups coexist fine; only the establishment step must be one-at-a-time. The lock is held
    // just for the handshake (released before the user's block runs), so long-lived connections
    // don't block new ones.
    val constraintViolation: CertificateHashPinningFailure? =
        try {
            appleQuicEstablishMutex.withLock {
                nw_helper_quic_group_set_state_handler(group) { state, _, errorCode, errorDesc ->
                    stateBridge.onState(state, errorCode, errorDesc)
                }
                // Wire peer-initiated streams into acceptStream() — MUST be set before start.
                // The block runs on the group's serial callback queue; trySend into an UNLIMITED
                // channel is non-blocking and thread-safe.
                nw_helper_quic_group_set_new_connection_handler(group) { streamConn ->
                    // Peek-then-classify off the serial callback queue (it suspends): filter NW's
                    // phantom stream AND read the REAL stream id on the now-live flow, so a peer
                    // *unidirectional* stream (e.g. the HTTP/3 server's control/SETTINGS stream) is
                    // labeled unidirectional rather than mislabeled bidirectional. Shared with the
                    // server accept path. nw_helper_quic_start is called inside the helper.
                    streamAcceptScope.launch {
                        filterPhantomAndEnqueue(streamConn, incomingStreams, acceptedStreamId, acceptKeepAliveSeconds)
                    }
                }
                nw_helper_quic_group_start(group)
                try {
                    // Throws the bridge's establish exception on failed/cancelled/error-waiting, or
                    // CancellationException if the connect timeout fires.
                    stateBridge.established.await()
                } catch (e: Throwable) {
                    // Establish failed, timed out, or was cancelled: cancel the group so NW abandons
                    // the handshake and the establish mutex is freed for the next connector (no
                    // watchdog hang).
                    nw_helper_quic_group_cancel(group)
                    throw e
                }
            }
            // Handshake succeeded (group ready). The leaf already hash-matched in the verify_block; now run
            // the W3C `serverCertificateHashes` certificate constraints on the captured leaf DER — but only
            // where this platform can extract them (macOS, via Security.framework). iOS/tvOS/watchOS report
            // LeafHashOnly (no public cert-validity API) → leaf-hash-only, no constraint check.
            if (serverCertHashes != null && serverCertificateConstraintSupport is ServerCertificateConstraintSupport.Enforced) {
                checkApplePinnedCertificateConstraints(pinLeafDerOut, pinLeafDerLenOut?.value ?: 0, PINNED_LEAF_DER_CAPACITY)
            } else {
                null
            }
        } finally {
            // The verify_block runs only during the handshake; once it has resolved (success or failure)
            // the pin out-params are no longer referenced, so release them on every exit path.
            freePinOutParams()
        }

    // A constraint violation means the leaf matched the pin but is not W3C-compliant (over-long validity,
    // expired/not-yet-valid, or a non-P-256 key). Tear down the established group and surface the SAME typed
    // exception the quiche backends throw, so the reject is uniform across every backend.
    if (constraintViolation != null) {
        nw_helper_quic_group_cancel(group)
        throw CertificateHashPinningException(constraintViolation)
    }

    // Extract THE datagram flow (one per connection) only when datagrams are enabled.
    val datagramFlow: nw_connection_t? =
        if (!datagramsEnabled) {
            null
        } else {
            // NULL below macOS 13 / iOS 16 — the only place the datagram feature is gated.
            val flow =
                nw_helper_quic_group_extract_datagram_flow(group, DATAGRAM_FRAME_SIZE_MAX)
                    ?: run {
                        nw_helper_quic_group_cancel(group)
                        throw SocketConnectionException.Refused(
                            hostname,
                            port,
                            platformError = "QUIC datagrams require macOS 13 / iOS 16",
                        )
                    }

            // Await the flow's readiness so its maximum datagram size is known before the
            // block runs. Connection states: 3=ready, 4=failed, 5=cancelled.
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_set_state_handler(flow) { state, _, errorCode, errorDesc ->
                    when (state) {
                        3 -> if (cont.isActive) cont.resume(Unit)
                        4 ->
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    SocketConnectionException.Refused(
                                        hostname,
                                        port,
                                        platformError = "QUIC datagram flow failed: code=$errorCode ${errorDesc ?: ""}",
                                    ),
                                )
                            }
                        5 ->
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    QuicCloseException(QuicError.NoError, "QUIC datagram flow cancelled"),
                                )
                            }
                        else -> {}
                    }
                }
                nw_helper_quic_start(flow)
                cont.invokeOnCancellation { nw_helper_quic_cancel(flow) }
            }
            flow
        }

    val quicConn =
        AppleQuicGroupConnection(
            group,
            datagramFlow,
            incomingStreams,
            connectionOptions.quicBufferFactory(),
            keepAliveSeconds = quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0,
            streamAcceptScope = streamAcceptScope,
        )
    // Route any post-handshake group failure/cancellation into the live connection. attach()
    // also replays a close that raced in between `ready` and here (see the bridge).
    stateBridge.attach(quicConn)
    return quicConn
}

/**
 * Bridges a Network.framework connection-group's state-changed callbacks to Kotlin across the
 * group's *entire* lifetime — the durable counterpart to the establish-only handler that used to
 * drop every state change after `ready`.
 *
 * NW delivers group state on a single serial queue, so [onState] is never re-entrant. The two
 * consumers live at different times: first the connecting coroutine awaiting [established], then
 * the live [AppleQuicGroupConnection] once [attach]ed. The handoff between them — and the window
 * where a failure arrives after `ready` but before [attach] — is made race-free by the lock-free
 * [sink] CAS, while [established] (a [CompletableDeferred], idempotent) absorbs the establish race.
 *
 * [cancelledEstablishException] supplies the *pre-`ready`* failure for a cancelled group (state 4):
 * the client path maps a leaf-hash pin rejection to a typed [CertificateHashPinningException]; the
 * server path uses the generic default. It is evaluated only when [established] has not yet resolved
 * — a post-`ready` cancel drives [AppleQuicGroupConnection.onTransportClosed] with the structured
 * reason instead and never reads the (by-then-freed) pin out-params.
 */
internal class AppleQuicGroupStateBridge(
    private val hostname: String,
    private val port: Int,
    private val cancelledEstablishException: () -> Throwable = {
        QuicCloseException(QuicError.NoError, "QUIC group cancelled")
    },
) {
    /** Completed on the first terminal establish event: [Unit] on ready, else the connect failure. */
    val established = CompletableDeferred<Unit>()

    private sealed interface Sink {
        /** Live (post-`ready`) phase; [conn] is null until [attach]. */
        class Live(
            val conn: AppleQuicGroupConnection?,
        ) : Sink

        /** A post-handshake transport close already happened; [reason] is replayed to a late [attach]. */
        class Closed(
            val reason: QuicError,
        ) : Sink
    }

    private val sink = AtomicReference<Sink>(Sink.Live(null))

    /** Group states (NB: no `preparing` for groups): 0=invalid, 1=waiting, 2=ready, 3=failed, 4=cancelled. */
    fun onState(
        state: Int,
        errorCode: Int,
        errorDesc: String?,
    ) {
        when (state) {
            2 -> established.complete(Unit) // ready
            3 ->
                terminal(QuicError.InternalError("QUIC group failed: code=$errorCode ${errorDesc ?: ""}")) {
                    SocketConnectionException.Refused(
                        hostname,
                        port,
                        platformError = "QUIC group handshake failed: code=$errorCode ${errorDesc ?: ""}",
                    )
                }
            4 -> terminal(QuicError.NoError) { cancelledEstablishException() }
            1 ->
                // "waiting" = NW couldn't establish and is holding to retry. ONLY terminal during the
                // bounded-timeout handshake: there's no recovery path then, so an error-carrying
                // "waiting" (no route, refused, etc.) fails fast rather than burning the whole connect
                // timeout. A benign "waiting" carries no error (errorCode == 0). POST-`ready`, "waiting"
                // is NW transiently holding (e.g. loopback path re-evaluation) and recovers to ready —
                // NOT a close; treating it as one prematurely tore down a healthy connection.
                // (A pinned-anchor TLS rejection does NOT arrive here — NW reports no group state for
                // it; nw_quic_helpers.h cancels the group from the verify_block, surfacing as state 4.)
                if (errorCode != 0 && !established.isCompleted) {
                    terminal(QuicError.CryptoError(0)) {
                        QuicCloseException(
                            QuicError.CryptoError(0),
                            "QUIC handshake failed connecting to $hostname:$port: code=$errorCode ${errorDesc ?: ""}",
                        )
                    }
                }
            else -> {} // invalid=0 — in progress
        }
    }

    private fun terminal(
        reason: QuicError,
        establishError: () -> Throwable,
    ) {
        // Pre-`ready`: surface as the connect failure. The [establishError] lambda is evaluated only
        // here (so the pin out-params are read only while alive). Serial NW queue ⇒ no concurrent
        // completion, so this isCompleted check is race-free against the connecting coroutine.
        if (!established.isCompleted) {
            established.completeExceptionally(establishError())
            return
        }
        // Post-`ready` close: drive the live connection (or replay to a late attach).
        while (true) {
            val cur = sink.value
            if (cur is Sink.Closed) return // already closed once
            if (sink.compareAndSet(cur, Sink.Closed(reason))) {
                (cur as Sink.Live).conn?.onTransportClosed(reason)
                return
            }
        }
    }

    /** Register the live connection so post-handshake failures reach it (replaying one that raced in). */
    fun attach(conn: AppleQuicGroupConnection) {
        while (true) {
            when (val cur = sink.value) {
                is Sink.Closed -> {
                    conn.onTransportClosed(cur.reason)
                    return
                }
                is Sink.Live -> if (sink.compareAndSet(cur, Sink.Live(conn))) return
            }
        }
    }
}

/**
 * Advertised `max_datagram_frame_size` (RFC 9221) — the largest a single DATAGRAM
 * frame may be. We advertise the 16-bit maximum; the *usable* per-datagram size is
 * the smaller path-MTU value reported live by [QuicScope.maxDatagramSize].
 */
internal const val DATAGRAM_FRAME_SIZE_MAX: UShort = 65535u

/**
 * The Apple QUIC connection — a multiplex [nw_connection_group_t] (issue #109).
 *
 * Each [openStream] extracts a REAL distinct QUIC stream from the group. [datagramFlow]
 * is the dedicated RFC 9221 datagram flow extracted at establish, or null when
 * [QuicOptions.datagrams] was not set — in which case the datagram surface reports
 * [MaxDatagramSize.Unavailable] and the send/receive methods throw, matching the
 * [QuicScope] defaults a non-datagram connection should present.
 *
 * [acceptStream]/[streams] are fed by the group's new-connection handler (wired in
 * connectQuicGroup), so peer-initiated streams — e.g. an HTTP/3 server's control and
 * QPACK unidirectional streams — are delivered to the application. NB: Network.framework
 * does not expose the real QUIC stream id/type, so accepted streams carry a synthetic id.
 */
internal class AppleQuicGroupConnection(
    private val group: nw_connection_group_t,
    private val datagramFlow: nw_connection_t?,
    // Peer-initiated streams, fed by the group's new-connection handler wired in connectQuicGroup.
    private val incomingStreams: Channel<QuicByteStream>,
    override val bufferFactory: BufferFactory,
    private val keepAliveSeconds: Int = 0,
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    // Runs the suspending peer-stream peek/classify launched from the group's new-connection handler;
    // owned here so connection teardown cancels any in-flight accept (mirrors closing incomingStreams).
    private val streamAcceptScope: CoroutineScope? = null,
) : QuicConnection,
    CoroutineScope by scope {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Established("h3"))
    override val state: StateFlow<QuicConnectionState> = _state

    private val nextClientStreamId = kotlin.concurrent.AtomicLong(0L)

    // Client-initiated unidirectional ids: low 2 bits = 0b10, so id % 4 == 2 (RFC 9000 §2.1).
    // Synthetic, like the bidi ids — NW hides the real id — but flavored so
    // QuicStreamId.isUnidirectional is correct.
    private val nextClientUniStreamId = kotlin.concurrent.AtomicLong(2L)

    @Volatile
    private var closed = false

    // Single claim shared by close() and onTransportClosed() so the teardown body runs exactly
    // once whether the close is caller-initiated or NW-initiated (no check-then-set race).
    private val closeClaim = AtomicInt(0)

    override suspend fun openStream(): QuicByteStream {
        check(!closed) { "AppleQuicGroupConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        val streamConn =
            nw_helper_quic_group_extract_stream(group)
                ?: throw QuicCloseException(closeReason(), "Failed to open QUIC stream")
        nw_helper_quic_start(streamConn)
        val streamId = QuicStreamId(nextClientStreamId.getAndAdd(4))
        return QuicByteStream(streamId, NWQuicByteStream(streamConn, streamId.id, keepAliveSeconds))
    }

    override suspend fun openUniStream(): QuicByteStream {
        check(!closed) { "AppleQuicGroupConnection is closed" }
        check(_state.value is QuicConnectionState.Established) {
            "Cannot open stream in state ${_state.value}"
        }
        // Extracted with the group's own QUIC options + is_unidirectional (see the shim);
        // a fresh-options extract would be torn down with ENETDOWN like the datagram flow.
        val streamConn =
            nw_helper_quic_group_extract_uni_stream(group)
                ?: throw QuicCloseException(closeReason(), "Failed to open unidirectional QUIC stream")
        nw_helper_quic_start(streamConn)
        val streamId = QuicStreamId(nextClientUniStreamId.getAndAdd(4))
        return QuicByteStream(streamId, NWQuicByteStream(streamConn, streamId.id))
    }

    override suspend fun acceptStream(): QuicByteStream {
        check(!closed) { "AppleQuicGroupConnection is closed" }
        // Suspends until the group's new-connection handler delivers a peer-initiated
        // stream, or throws when the channel closes (connection gone).
        return incomingStreams.receive()
    }

    override fun streams(): Flow<QuicByteStream> = incomingStreams.consumeAsFlow()

    // --- Unreliable datagrams (RFC 9221) ---
    // Mirrors the quiche-backed DriverDatagramAdapter contract: size-check against the
    // live max, caller retains ownership on send, ownership transfers to caller on receive.

    override suspend fun sendDatagram(buffer: ReadBuffer) {
        val flow =
            datagramFlow
                ?: throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
        val remaining = buffer.remaining()
        // Validate the size against the last-known max FIRST — a pure local precondition, exactly as
        // the quiche DriverDatagramAdapter validates against driver.lastMaxDatagramSize. An oversized
        // datagram is a programming error (IllegalArgumentException) independent of connection
        // liveness, so the check must not be gated behind the closed-check below (a peer that closed
        // the connection between negotiating the size and this call must not turn it into a
        // QuicCloseException — that divergence broke datagramTooLargeThrows once close became
        // observable).
        when (val max = maxDatagramSize()) {
            is MaxDatagramSize.Unavailable ->
                throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
            is MaxDatagramSize.Bytes ->
                require(remaining <= max.bytes) { "datagram too large: $remaining > ${max.bytes} bytes" }
        }
        if (closed) throw QuicCloseException(closeReason(), "connection closed")

        // dispatch_data_create (in the helper) copies the bytes synchronously, so the
        // caller may free `buffer` the instant we return; we still suspend until the
        // send-complete callback to surface errors and provide backpressure.
        val nsData = buffer.toNativeData().nsData
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_datagram_send(flow, nsData) { _, errorCode, errorDesc ->
                if (errorCode != 0) {
                    if (cont.isActive) {
                        cont.resumeWithException(
                            QuicCloseException(
                                closeReason(QuicError.InternalError("datagram send error: $errorCode")),
                                "QUIC datagram send error: $errorCode ${errorDesc ?: ""}",
                            ),
                        )
                    }
                } else {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    override suspend fun receiveDatagram(): DatagramReceiveResult {
        val flow =
            datagramFlow
                ?: throw UnsupportedOperationException("QUIC datagrams are not enabled on this connection")
        if (closed) return DatagramReceiveResult.ConnectionClosed(closeReason())
        return suspendCancellableCoroutine { cont ->
            // One datagram per receive — the datagram flow delivers each as a complete
            // message (is_complete=true). Zero-copy: wrap the NSData, flip to read mode.
            nw_helper_quic_receive(flow, 1u, DATAGRAM_FRAME_SIZE_MAX.convert()) { data, _, _, errorCode, _ ->
                when {
                    data != null && nsDataLengthInt(data) > 0 -> {
                        val buf = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                        buf.position(nsDataLengthInt(data))
                        buf.resetForRead()
                        if (cont.isActive) cont.resume(DatagramReceiveResult.Received(buf))
                    }
                    // No data (flow ended / min_length unmet) or an error → the flow is gone.
                    // NB: a zero-length datagram (valid per RFC 9221) lands here too — an
                    // accepted limitation of the NW datagram-flow receive boundary.
                    else -> if (cont.isActive) cont.resume(DatagramReceiveResult.ConnectionClosed(closeReason()))
                }
            }
        }
    }

    override fun datagrams(): Flow<ReadBuffer> {
        if (datagramFlow == null) return emptyFlow()
        return flow {
            while (true) {
                when (val result = receiveDatagram()) {
                    is DatagramReceiveResult.Received -> emit(result.buffer)
                    is DatagramReceiveResult.ConnectionClosed -> return@flow
                }
            }
        }
    }

    // Last positive max-datagram-size NW reported, cached so it survives connection close — the
    // quiche driver does the same via lastMaxDatagramSize. Without it, a live read after close
    // returns Unavailable and a size precondition check can't be evaluated.
    @Volatile
    private var lastMaxDatagram: MaxDatagramSize = MaxDatagramSize.Unavailable

    override fun maxDatagramSize(): MaxDatagramSize {
        val flow = datagramFlow ?: return MaxDatagramSize.Unavailable
        if (closed) return lastMaxDatagram
        val bytes = nw_helper_quic_max_datagram_size(flow).toInt()
        val result = if (bytes > 0) MaxDatagramSize.Bytes(bytes) else lastMaxDatagram
        lastMaxDatagram = result
        return result
    }

    override suspend fun close(error: QuicError) {
        if (!closeClaim.compareAndSet(0, 1)) return
        closed = true
        _state.value = QuicConnectionState.Draining
        streamAcceptScope?.cancel()
        datagramFlow?.let { nw_helper_quic_cancel(it) }
        nw_helper_quic_group_cancel(group)
        incomingStreams.close()
        _state.value = QuicConnectionState.Closed(error)
    }

    /**
     * NW reported the underlying group failed/cancelled *after* the handshake (peer close, idle
     * timeout, network loss) — driven here by [AppleQuicGroupStateBridge]. Mirrors the quiche
     * driver transitioning its StateFlow to Closed: publishes the reason and closes
     * [incomingStreams] (with the reason as cause) so a parked acceptStream()/streams() unblocks
     * with a [QuicCloseException] instead of hanging until the caller's timeout. Idempotent and
     * safe against a concurrent caller close() via the shared [closeClaim].
     */
    internal fun onTransportClosed(reason: QuicError) {
        if (!closeClaim.compareAndSet(0, 1)) return
        closed = true
        _state.value = QuicConnectionState.Closed(reason)
        streamAcceptScope?.cancel()
        // Close WITHOUT a cause, matching the quiche driver (QuicheDriver.cleanup), so a parked
        // acceptStream()/streams() ends the same way on every platform (clean channel close, not a
        // thrown QuicCloseException). The structured reason is still available via state/closeReason().
        incomingStreams.close()
        // NW has already torn the group down; cancel is a harmless no-op but releases the
        // datagram flow we extracted.
        datagramFlow?.let { nw_helper_quic_cancel(it) }
        nw_helper_quic_group_cancel(group)
    }

    /** The structured close reason if known, else [fallback]. */
    private fun closeReason(fallback: QuicError = QuicError.NoError): QuicError =
        (_state.value as? QuicConnectionState.Closed)?.error ?: fallback
}

/**
 * ByteStream backed by a Network.framework NWConnection QUIC stream.
 *
 * Zero-copy read: NSData from receive callback → NSDataBuffer (wraps, no copy)
 * Zero-copy write: NSDataBuffer.data → dispatch_data_t (wraps, no copy)
 */
internal class NWQuicByteStream(
    private val nwConn: nw_connection_t,
    private val streamId: Long = -1L,
    private val keepAliveSeconds: Int = 0,
) : ByteStream,
    HalfCloseable,
    Resettable {
    // Lifecycle state machine, atomic so concurrent shutdownSend()/close()/reset() can never
    // double-FIN or skip teardown — a TOCTOU the prior @Volatile check-then-set flags allowed:
    //   LIFECYCLE_OPEN → LIFECYCLE_SEND_FINISHED (half-close) → LIFECYCLE_CLOSED.
    private val lifecycle = AtomicInt(LIFECYCLE_OPEN)

    @Volatile
    private var keepAliveApplied = false

    override val isOpen: Boolean get() = lifecycle.value != LIFECYCLE_CLOSED

    // QUIC stream policy refinement to UntilClosed (persistent WebTransport streams) is Phase 3
    // work; the request/response-shaped Bounded default is correct for the current stream surface.
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)

    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult =
        withTimeout(deadline) {
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_receive(nwConn, 1u, 65536u) { data, isComplete, _, errorCode, _ ->
                    when {
                        data != null && nsDataLengthInt(data) > 0 -> {
                            // Zero-copy: wrap NSData directly. position()+resetForRead()
                            // flips the buffer to read mode (limit=length, position=0) so
                            // ReadResult.Data hands the caller a read-positioned buffer with
                            // remaining()==length — without the flip it stays write-positioned
                            // (remaining()==0) and the read appears empty (cf.
                            // DriverStreamAdapter.streamRead in QuicheDriver.kt). (Issue #81.)
                            val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                            buffer.position(nsDataLengthInt(data))
                            buffer.resetForRead()
                            applyKeepAliveOnce()
                            if (cont.isActive) cont.resume(ReadResult.Data(buffer))
                        }
                        errorCode != 0 -> {
                            // A peer RESET_STREAM/STOP_SENDING surfaces as an nw_error AND
                            // is_complete=true (the stream is done), so the error must be
                            // examined before the is_complete check below — otherwise an
                            // abrupt reset is misreported as a graceful end-of-stream. But a
                            // transport-level close (idle timeout, connection close) is ALSO an
                            // nw_error; only a real stream reset carries a QUIC application
                            // error code. Use it to tell them apart. (Issue #81.)
                            val appErr = nw_helper_quic_stream_application_error(nwConn)
                            val result = if (appErr != ULong.MAX_VALUE) ReadResult.Reset else ReadResult.End
                            if (cont.isActive) cont.resume(result)
                        }
                        isComplete?.boolValue == true -> {
                            if (cont.isActive) cont.resume(ReadResult.End)
                        }
                        else -> {
                            if (cont.isActive) cont.resume(ReadResult.End)
                        }
                    }
                }
            }
        }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        withTimeout(deadline) {
            check(lifecycle.value == LIFECYCLE_OPEN) { "NWQuicByteStream send side is finished" }
            val remaining = buffer.remaining()
            val nsData = buffer.toNativeData().nsData

            suspendCancellableCoroutine { cont ->
                // Stream-vs-connection split (issue #134, mirroring the quiche driver): a peer
                // STOP_SENDING/RESET_STREAM on ONE stream must raise a stream-scoped
                // [QuicStreamException] (the connection survives) rather than tearing it down with
                // [QuicCloseException]. Network.framework gives the same (err_domain, err_code) —
                // POSIX/57 (ENOTCONN) — for BOTH a peer stream reset AND a real connection close, so
                // the error code can't discriminate (verified on macOS hardware). The reliable signal
                // is the QUIC stream application error: nw_quic_get_stream_application_error returns the
                // peer's reset code on a stream reset, or UINT64_MAX otherwise. (Issue #134.)
                nw_helper_quic_send(nwConn, nsData, NSNumber(bool = false)) { _, errorCode, _ ->
                    if (errorCode != 0) {
                        if (cont.isActive) cont.resumeWithException(writeError(errorCode))
                    } else {
                        applyKeepAliveOnce()
                        if (cont.isActive) cont.resume(BytesWritten(remaining))
                    }
                }
            }
        }

    override suspend fun shutdownSend() {
        // Claim open→send-finished exactly once; no-op if already half- or fully-closed.
        // Send-side FIN only; the read side stays open for the response (HTTP/3 §4 half-close).
        // Network.framework has no separate shutdown — the FIN is an empty FINAL_MESSAGE, same
        // wire effect as quiche's stream_send(fin=true).
        if (!lifecycle.compareAndSet(LIFECYCLE_OPEN, LIFECYCLE_SEND_FINISHED)) return
        sendFin()
    }

    override suspend fun close() {
        when {
            // Open → closed: the send side was never finished, so flush a FIN now.
            lifecycle.compareAndSet(LIFECYCLE_OPEN, LIFECYCLE_CLOSED) -> sendFin()
            // Half-closed → closed: shutdownSend() already sent the FIN; just finalize.
            lifecycle.compareAndSet(LIFECYCLE_SEND_FINISHED, LIFECYCLE_CLOSED) -> {}
            // Already closed → no-op.
            else -> {}
        }
    }

    override suspend fun reset(errorCode: Long) {
        // Claim closed from any prior state; no-op if already closed. Abort both directions with
        // the application error code — RESET_STREAM (send) + STOP_SENDING (read), RFC 9000
        // §19.4/§19.5 — matching QuicheStreamByteStream. NW stamps the error onto the stream
        // metadata and cancels (no FIN); fire-and-forget, so no send-complete callback to await
        // (unlike sendFin). (Issue #81.)
        if (lifecycle.getAndSet(LIFECYCLE_CLOSED) == LIFECYCLE_CLOSED) return
        nw_helper_quic_reset_stream(nwConn, errorCode.toULong())
    }

    /**
     * Classify a non-zero NW send error: a peer stream reset (the stream carries a QUIC
     * application error code) is a stream-scoped [QuicStreamException] — the connection stays
     * usable; anything else is a connection-level [QuicCloseException]. A peer reset surfacing
     * on our write side means the peer no longer wants our data (STOP_SENDING semantics), so the
     * abort is reported as [QuicStreamAbort.StopSending] carrying the peer's code. (Issue #134.)
     */
    private fun writeError(errorCode: Int): Throwable {
        val appErr = nw_helper_quic_stream_application_error(nwConn)
        return if (appErr != ULong.MAX_VALUE) {
            QuicStreamException(
                streamId,
                QuicStreamAbort.StopSending(appErr.toLong()),
                "QUIC stream $streamId reset by peer (application error $appErr)",
            )
        } else {
            QuicCloseException(
                QuicError.InternalError("QUIC write error: $errorCode"),
                "QUIC write error: $errorCode",
            )
        }
    }

    /**
     * Send the stream FIN: empty data with is_complete=true (FINAL_MESSAGE context).
     * Bounded by a timeout: stream teardown must never block forever on the
     * send-complete callback. The root-cause hang (a prior write leaving the
     * DEFAULT message open and queuing this FIN behind it) is fixed in
     * nw_quic_helpers.h, but a best-effort, bounded send is the correct contract
     * regardless. (Issue #81.)
     */
    private suspend fun sendFin() {
        withTimeoutOrNull(FIN_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                val emptyData = NSData()
                nw_helper_quic_send(nwConn, emptyData, NSNumber(bool = true)) { _, _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /**
     * Arm QUIC keepalive on the first live I/O. [QuicOptions.keepAliveInterval] must be
     * applied via nw_quic_set_keepalive_interval, which takes the connection's
     * nw_protocol_metadata_t — only obtainable from a STARTED flow (it is nil before the
     * flow is ready, which is why setting it on the create-params options object, or right
     * after extract, is a no-op). The first successful read/write proves the flow is live,
     * so we set it here, once. All flows share one QUIC connection, so any flow configures
     * it. (Fixes the Apple half of the #130 keepalive feature.)
     */
    private fun applyKeepAliveOnce() {
        if (keepAliveSeconds <= 0 || keepAliveApplied) return
        keepAliveApplied = true
        nw_helper_quic_set_keepalive(nwConn, keepAliveSeconds.toUShort())
    }

    private companion object {
        // Lifecycle states (see [lifecycle]).
        private const val LIFECYCLE_OPEN = 0
        private const val LIFECYCLE_SEND_FINISHED = 1
        private const val LIFECYCLE_CLOSED = 2

        // Upper bound for flushing the stream FIN during close(). The FIN should
        // complete near-instantly once the send queue is unblocked; this only
        // guards against a wedged Network.framework send callback.
        private val FIN_TIMEOUT: Duration = 5.seconds
    }
}

/** SHA-256 digest length in bytes — the only algorithm `serverCertificateHashes` defines. */
private const val SHA256_DIGEST_BYTES = 32

/**
 * Capacity of the buffer the verify_block copies the matched leaf DER into for the post-handshake W3C
 * constraint check. A real leaf certificate DER is < 4 KiB; this is generous, and an overflow fails closed
 * (the verify_block still reports the true length, which the Kotlin side treats as a parse failure).
 */
private const val PINNED_LEAF_DER_CAPACITY = 8192

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex of the 32-byte computed leaf digest the NW verify_block wrote to [digest]. */
private fun computedHashHex(digest: CPointer<UByteVar>): String {
    val sb = StringBuilder(SHA256_DIGEST_BYTES * 2)
    for (i in 0 until SHA256_DIGEST_BYTES) {
        val b = digest[i].toInt() and 0xFF
        sb.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0xF])
    }
    return sb.toString()
}

/**
 * Parse every `-----BEGIN CERTIFICATE-----` block in [pem] into DER-encoded
 * [NSData] for `SecCertificateCreateWithData`. Issue #81: lets the Apple QUIC
 * verify_block pin a private-CA trust anchor without touching the OS keychain.
 *
 * Foundation decodes the base64 body straight into DER-backed [NSData] — no
 * intermediate `ByteArray`.
 */
private fun pemToDerCertificates(pem: String): List<NSData> {
    val begin = "-----BEGIN CERTIFICATE-----"
    val end = "-----END CERTIFICATE-----"
    val ders = mutableListOf<NSData>()
    var search = 0
    while (true) {
        val b = pem.indexOf(begin, search)
        if (b < 0) break
        val e = pem.indexOf(end, b + begin.length)
        if (e < 0) break
        val body = pem.substring(b + begin.length, e)
        decodeBase64ToNSData(body)?.let { ders += it }
        search = e + end.length
    }
    return ders
}
