@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketConnectionException
import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_group
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_datagram_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_datagram_flow
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_extract_stream
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_new_connection_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_group_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_max_datagram_size
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_receive
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_send
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * Apple [withQuicConnection] using Network.framework.
 *
 * Zero-copy read path: Network.framework → dispatch_data_t → NSData → NSDataBuffer (no copy)
 * Zero-copy write path: NSDataBuffer → toNSData() → dispatch_data_t (no copy for NSData-backed buffers)
 *
 * Requires iOS 15+ / macOS 12+.
 */
actual suspend fun <R> withQuicConnection(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: ConnectionOptions,
    timeout: Duration,
    block: suspend QuicScope.() -> R,
): R =
    withTimeout(timeout) {
        connectQuicGroup(hostname, port, quicOptions, connectionOptions, timeout, block)
    }

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
 *  3. Hand a [AppleQuicGroupConnection] to [block]; streams are extracted lazily.
 */
private suspend fun <R> connectQuicGroup(
    hostname: String,
    port: Int,
    quicOptions: QuicOptions,
    connectionOptions: ConnectionOptions,
    timeout: Duration,
    block: suspend QuicScope.() -> R,
): R {
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
            timeout.inWholeSeconds.toInt(),
            maxFrameSize,
        ) ?: throw SocketConnectionException.Refused(hostname, port, platformError = "Failed to create QUIC connection group")

    // Peer-initiated streams land here via the group's new-connection handler (wired below,
    // before start) and are drained by acceptStream()/streams(). Declared here so the handler
    // — which the API requires to be set before nw_helper_quic_group_start — can target it.
    val incomingStreams = Channel<QuicByteStream>(Channel.UNLIMITED)
    val acceptedStreamId = kotlin.concurrent.AtomicLong(1L) // server-initiated bidi ids (synthetic; NW hides the real id)

    // Wait for the group (QUIC handshake) to become ready. Group states: 2=ready, 3=failed, 4=cancelled.
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
                else -> {} // invalid=0, waiting=1 — in progress
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
            incomingStreams.trySend(
                QuicByteStream(QuicStreamId(acceptedStreamId.getAndAdd(4)), NWQuicByteStream(streamConn)),
            )
        }
        nw_helper_quic_group_start(group)
        cont.invokeOnCancellation { nw_helper_quic_group_cancel(group) }
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

    val quicConn = AppleQuicGroupConnection(group, datagramFlow, incomingStreams, connectionOptions.bufferFactory)
    return try {
        quicConn.block()
    } finally {
        quicConn.close()
    }
}

/**
 * Advertised `max_datagram_frame_size` (RFC 9221) — the largest a single DATAGRAM
 * frame may be. We advertise the 16-bit maximum; the *usable* per-datagram size is
 * the smaller path-MTU value reported live by [QuicScope.maxDatagramSize].
 */
private const val DATAGRAM_FRAME_SIZE_MAX: UShort = 65535u

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
private class AppleQuicGroupConnection(
    private val group: nw_connection_group_t,
    private val datagramFlow: nw_connection_t?,
    // Peer-initiated streams, fed by the group's new-connection handler wired in connectQuicGroup.
    private val incomingStreams: Channel<QuicByteStream>,
    private val bufferFactory: BufferFactory,
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
) : QuicConnection,
    CoroutineScope by scope {
    private val _state = MutableStateFlow<QuicConnectionState>(QuicConnectionState.Established("h3"))
    override val state: StateFlow<QuicConnectionState> = _state

    private val nextClientStreamId = kotlin.concurrent.AtomicLong(0L)

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
        return QuicByteStream(streamId, NWQuicByteStream(streamConn))
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
        val nsData = buffer.toNSData()
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
private class NWQuicByteStream(
    private val nwConn: nw_connection_t,
) : ByteStream {
    @Volatile
    private var streamClosed = false

    override val isOpen: Boolean get() = !streamClosed

    override suspend fun read(timeout: Duration): ReadResult =
        withTimeout(timeout) {
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
                            if (cont.isActive) cont.resume(ReadResult.Data(buffer))
                        }
                        isComplete?.boolValue == true -> {
                            if (cont.isActive) cont.resume(ReadResult.End)
                        }
                        errorCode != 0 -> {
                            if (cont.isActive) cont.resume(ReadResult.Reset)
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
        timeout: Duration,
    ): BytesWritten =
        withTimeout(timeout) {
            val remaining = buffer.remaining()
            val nsData = buffer.toNSData()

            suspendCancellableCoroutine { cont ->
                nw_helper_quic_send(nwConn, nsData, NSNumber(bool = false)) { _, errorCode, _ ->
                    if (errorCode != 0) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                QuicCloseException(
                                    QuicError.InternalError("QUIC write error: $errorCode"),
                                    "QUIC write error: $errorCode",
                                ),
                            )
                        }
                    } else {
                        if (cont.isActive) cont.resume(BytesWritten(remaining))
                    }
                }
            }
        }

    override suspend fun close() {
        if (streamClosed) return
        streamClosed = true
        // Send FIN: empty data with is_complete=true (FINAL_MESSAGE context).
        // Bounded by a timeout: stream teardown must never block forever on the
        // send-complete callback. The root-cause hang (a prior write leaving the
        // DEFAULT message open and queuing this FIN behind it) is fixed in
        // nw_quic_helpers.h, but a best-effort, bounded close is the correct
        // contract regardless. (Issue #81.)
        withTimeoutOrNull(FIN_TIMEOUT) {
            suspendCancellableCoroutine { cont ->
                val emptyData = NSData()
                nw_helper_quic_send(nwConn, emptyData, NSNumber(bool = true)) { _, _, _ ->
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        // Upper bound for flushing the stream FIN during close(). The FIN should
        // complete near-instantly once the send queue is unblocked; this only
        // guards against a wedged Network.framework send callback.
        private val FIN_TIMEOUT: Duration = 5.seconds
    }
}

/**
 * Convert ReadBuffer to NSData for sending via Network.framework.
 *
 * Zero-copy paths:
 * - NSDataBuffer: returns underlying NSData directly
 * - NativeMemoryAccess (deterministic buffers): NSData wraps native address (no copy)
 *
 * The caller's buffer must outlive the NSData (Network.framework copies internally on send).
 */
private fun ReadBuffer.toNSData(): NSData {
    // Fast path: already backed by NSData
    if (this is NSDataBuffer) return data

    // Zero-copy path: use nativeMemoryAccess address directly
    val nma = this.nativeMemoryAccess
    if (nma != null) {
        val addr = (nma.nativeAddress + position().toLong()).toCPointer<kotlinx.cinterop.ByteVar>()
        if (addr != null) {
            // bytesNoCopy: NSData wraps our buffer's memory without copying.
            // freeWhenDone=false: we manage the buffer lifecycle, not NSData.
            return NSData.create(bytesNoCopy = addr, length = remaining().convert(), freeWhenDone = false)
        }
    }

    // Last resort: empty data
    return NSData()
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
