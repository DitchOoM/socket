@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.toNativeData
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
import kotlinx.cinterop.convert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSDataBase64DecodingIgnoreUnknownCharacters
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Network.nw_connection_group_t
import platform.Network.nw_connection_t
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

    val datagramsEnabled = quicOptions.datagrams != null
    val maxFrameSize: UShort = if (datagramsEnabled) DATAGRAM_FRAME_SIZE_MAX else 0u

    val group =
        nw_helper_create_quic_group(
            hostname,
            port.toUShort(),
            alpnList,
            NSNumber(bool = quicOptions.verifyPeer),
            caDerList,
            quicOptions.idleTimeout.inWholeSeconds.toInt(),
            quicOptions.keepAliveInterval?.inWholeSeconds?.toInt() ?: 0,
            timeout.inWholeSeconds.toInt(),
            maxFrameSize,
        ) ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create QUIC connection group")

    // Peer-initiated streams land here via the group's new-connection handler (wired below,
    // before start) and are drained by acceptStream()/streams(). Declared here so the handler
    // — which the API requires to be set before nw_helper_quic_group_start — can target it.
    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    val acceptedStreamId = kotlin.concurrent.AtomicLong(1L) // server-initiated bidi ids (synthetic; NW hides the real id)

    // Wait for the group (QUIC handshake) to become ready. Group states: 2=ready, 3=failed, 4=cancelled.
    //
    // Serialized process-wide via [appleQuicEstablishMutex]: Network.framework fails a QUIC
    // group handshake with POSIX ENOMEM ("Cannot allocate memory") whenever another group is
    // already establishing — reproduced with as few as two concurrent connections against both
    // an in-process listener AND a public endpoint (cloudflare-quic.com). Already-established
    // groups coexist fine; only the establishment step must be one-at-a-time. The lock is held
    // just for the handshake (released before the user's block runs), so long-lived connections
    // don't block new ones.
    appleQuicEstablishMutex.withLock {
        suspendCancellableCoroutine { cont ->
            nw_helper_quic_group_set_state_handler(group) { state, _, errorCode, errorDesc ->
                when (state) {
                    2 -> if (cont.isActive) cont.resume(Unit)
                    3 ->
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SocketConnectionException.Refused(
                                    hostname,
                                    port,
                                    platformError = "QUIC group handshake failed: code=$errorCode ${errorDesc ?: ""}",
                                ),
                            )
                        }
                    4 -> if (cont.isActive) cont.resumeWithException(QuicCloseException(QuicError.NoError, "QUIC group cancelled"))
                    1 ->
                        // "waiting" = NW couldn't establish and is holding to retry. During the
                        // bounded-timeout CLIENT handshake there's no recovery path, so an
                        // error-carrying "waiting" (no route, refused, etc.) should fail fast
                        // rather than burn the whole connect timeout retrying. A benign pre-ready
                        // "waiting" carries no error (errorCode == 0) and is left in-progress.
                        // (Note: a pinned-anchor TLS rejection does NOT arrive here — NW reports
                        // no group state for it at all; nw_quic_helpers.h cancels the group from
                        // the verify_block instead, which surfaces as `cancelled` below.)
                        if (errorCode != 0 && cont.isActive) {
                            cont.resumeWithException(
                                QuicCloseException(
                                    QuicError.CryptoError(0),
                                    "QUIC handshake failed connecting to $hostname:$port: code=$errorCode ${errorDesc ?: ""}",
                                ),
                            )
                        }
                    else -> {} // invalid=0 — in progress
                }
            }
            // Wire peer-initiated streams into acceptStream() — MUST be set before start.
            // The block runs on the group's serial callback queue; trySend into an UNLIMITED
            // channel is non-blocking and thread-safe.
            nw_helper_quic_group_set_new_connection_handler(group) { streamConn ->
                // Take ownership (action 1 of NW's three): start the flow on the shared serial
                // queue, then enqueue it. Runs synchronously inside the block, so the connection
                // is owned before the block returns.
                nw_helper_quic_start(streamConn)
                val sid = QuicStreamId(acceptedStreamId.getAndAdd(4))
                incomingStreams.trySend(
                    QuicByteStream(sid, NWQuicByteStream(streamConn, sid.id)),
                )
            }
            nw_helper_quic_group_start(group)
            cont.invokeOnCancellation { nw_helper_quic_group_cancel(group) }
        }
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
        )
    return quicConn
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
        if (closed) throw QuicCloseException(closeReason(), "connection closed")
        val flow =
            datagramFlow
                ?: throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
        val remaining = buffer.remaining()
        when (val max = maxDatagramSize()) {
            is MaxDatagramSize.Unavailable ->
                throw IllegalStateException("QUIC datagrams are not enabled, or the peer did not advertise support")
            is MaxDatagramSize.Bytes ->
                require(remaining <= max.bytes) { "datagram too large: $remaining > ${max.bytes} bytes" }
        }

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
                    data != null && data.length.toInt() > 0 -> {
                        val buf = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                        buf.position(data.length.toInt())
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

    override fun maxDatagramSize(): MaxDatagramSize {
        if (closed) return MaxDatagramSize.Unavailable
        val flow = datagramFlow ?: return MaxDatagramSize.Unavailable
        val bytes = nw_helper_quic_max_datagram_size(flow).toInt()
        return if (bytes > 0) MaxDatagramSize.Bytes(bytes) else MaxDatagramSize.Unavailable
    }

    override suspend fun close(error: QuicError) {
        if (closed) return
        closed = true
        _state.value = QuicConnectionState.Draining
        datagramFlow?.let { nw_helper_quic_cancel(it) }
        nw_helper_quic_group_cancel(group)
        incomingStreams.close()
        _state.value = QuicConnectionState.Closed(error)
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
) : HalfCloseableByteStream,
    ResettableByteStream {
    @Volatile
    private var streamClosed = false

    @Volatile
    private var sendFinished = false

    @Volatile
    private var keepAliveApplied = false

    override val isOpen: Boolean get() = !streamClosed

    // QUIC stream policy refinement to UntilClosed (persistent WebTransport streams) is Phase 3
    // work; the request/response-shaped Bounded default is correct for the current stream surface.
    override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)

    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

    override suspend fun read(deadline: Duration): ReadResult =
        withTimeout(deadline) {
            suspendCancellableCoroutine { cont ->
                nw_helper_quic_receive(nwConn, 1u, 65536u) { data, isComplete, _, errorCode, _ ->
                    when {
                        data != null && data.length.toInt() > 0 -> {
                            // Zero-copy: wrap NSData directly. position()+resetForRead()
                            // flips the buffer to read mode (limit=length, position=0) so
                            // ReadResult.Data hands the caller a read-positioned buffer with
                            // remaining()==length — without the flip it stays write-positioned
                            // (remaining()==0) and the read appears empty (cf.
                            // DriverStreamAdapter.streamRead in QuicheDriver.kt). (Issue #81.)
                            val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                            buffer.position(data.length.toInt())
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
            check(!sendFinished) { "NWQuicByteStream send side is finished" }
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
        if (streamClosed || sendFinished) return
        sendFinished = true
        // Send-side FIN only; the read side stays open for the response (HTTP/3 §4
        // half-close). Network.framework has no separate shutdown — the FIN is an
        // empty FINAL_MESSAGE, same wire effect as quiche's stream_send(fin=true).
        sendFin()
    }

    override suspend fun close() {
        if (streamClosed) return
        streamClosed = true
        // Avoid a duplicate FIN if the send side was already shut down.
        if (!sendFinished) sendFin()
    }

    override suspend fun reset(errorCode: Long) {
        if (streamClosed) return
        streamClosed = true
        // Abort both directions with the application error code — RESET_STREAM (send)
        // + STOP_SENDING (read), RFC 9000 §19.4/§19.5 — matching QuicheStreamByteStream.
        // NW stamps the error onto the stream metadata and cancels (no FIN); fire-and-
        // forget, so no send-complete callback to await (unlike sendFin). (Issue #81.)
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
        // Upper bound for flushing the stream FIN during close(). The FIN should
        // complete near-instantly once the send queue is unblocked; this only
        // guards against a wedged Network.framework send callback.
        private val FIN_TIMEOUT: Duration = 5.seconds
    }
}

/**
 * Parse every `-----BEGIN CERTIFICATE-----` block in [pem] into DER-encoded
 * [NSData] for `SecCertificateCreateWithData`. Issue #81: lets the Apple QUIC
 * verify_block pin a private-CA trust anchor without touching the OS keychain.
 *
 * Foundation decodes the base64 body straight into DER-backed [NSData] — no
 * intermediate `ByteArray`.
 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
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
        NSData
            .create(
                base64EncodedString = body,
                options = NSDataBase64DecodingIgnoreUnknownCharacters,
            )?.let { ders += it }
        search = e + end.length
    }
    return ders
}
